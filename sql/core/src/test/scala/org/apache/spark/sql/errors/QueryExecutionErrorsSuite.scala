/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.errors

import java.io.{File, IOException}
import java.net.{URI, URL}
import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet, ResultSetMetaData}
import java.util.{Locale, Properties, ServiceConfigurationError}

import org.apache.hadoop.fs.{LocalFileSystem, Path}
import org.apache.hadoop.fs.permission.FsPermission
import org.mockito.Mockito.{mock, when}
import test.org.apache.spark.sql.connector.JavaSimpleWritableDataSource

import org.apache.spark.{SparkArithmeticException, SparkClassNotFoundException, SparkException, SparkIllegalArgumentException, SparkRuntimeException, SparkSecurityException, SparkSQLException, SparkUnsupportedOperationException, SparkUpgradeException}
import org.apache.spark.sql.{AnalysisException, DataFrame, QueryTest, SaveMode}
import org.apache.spark.sql.catalyst.util.BadRecordException
import org.apache.spark.sql.connector.SimpleWritableDataSource
import org.apache.spark.sql.execution.QueryExecutionException
import org.apache.spark.sql.execution.datasources.jdbc.{DriverRegistry, JDBCOptions}
import org.apache.spark.sql.execution.datasources.orc.OrcTest
import org.apache.spark.sql.execution.datasources.parquet.ParquetTest
import org.apache.spark.sql.functions.{lit, lower, struct, sum, udf}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf.LegacyBehaviorPolicy.EXCEPTION
import org.apache.spark.sql.jdbc.{JdbcDialect, JdbcDialects}
import org.apache.spark.sql.types.{DataType, DecimalType, MetadataBuilder, StructType}
import org.apache.spark.util.Utils

class QueryExecutionErrorsSuite
  extends QueryTest
  with ParquetTest
  with OrcTest
  with QueryErrorsSuiteBase {

  import testImplicits._

  private def getAesInputs(): (DataFrame, DataFrame) = {
    val encryptedText16 = "4Hv0UKCx6nfUeAoPZo1z+w=="
    val encryptedText24 = "NeTYNgA+PCQBN50DA//O2w=="
    val encryptedText32 = "9J3iZbIxnmaG+OIA9Amd+A=="
    val encryptedEmptyText16 = "jmTOhz8XTbskI/zYFFgOFQ=="
    val encryptedEmptyText24 = "9RDK70sHNzqAFRcpfGM5gQ=="
    val encryptedEmptyText32 = "j9IDsCvlYXtcVJUf4FAjQQ=="

    val df1 = Seq("Spark", "").toDF
    val df2 = Seq(
      (encryptedText16, encryptedText24, encryptedText32),
      (encryptedEmptyText16, encryptedEmptyText24, encryptedEmptyText32)
    ).toDF("value16", "value24", "value32")

    (df1, df2)
  }

  test("INVALID_PARAMETER_VALUE: invalid key lengths in AES functions") {
    val (df1, df2) = getAesInputs()
    def checkInvalidKeyLength(df: => DataFrame, inputBytes: Int): Unit = {
      checkError(
        exception = intercept[SparkException] {
          df.collect
        }.getCause.asInstanceOf[SparkRuntimeException],
        errorClass = "INVALID_PARAMETER_VALUE",
        parameters = Map("parameter" -> "key",
          "functionName" -> "`aes_encrypt`/`aes_decrypt`",
          "expected" -> ("expects a binary value with 16, 24 or 32 bytes, but got " +
            inputBytes.toString + " bytes.")),
        sqlState = "22023")
    }

    // Encryption failure - invalid key length
    checkInvalidKeyLength(df1.selectExpr("aes_encrypt(value, '12345678901234567')"), 17)
    checkInvalidKeyLength(df1.selectExpr("aes_encrypt(value, binary('123456789012345'))"),
      15)
    checkInvalidKeyLength(df1.selectExpr("aes_encrypt(value, binary(''))"), 0)

    // Decryption failure - invalid key length
    Seq("value16", "value24", "value32").foreach { colName =>
      checkInvalidKeyLength(df2.selectExpr(
        s"aes_decrypt(unbase64($colName), '12345678901234567')"), 17)
      checkInvalidKeyLength(df2.selectExpr(
        s"aes_decrypt(unbase64($colName), binary('123456789012345'))"), 15)
      checkInvalidKeyLength(df2.selectExpr(
        s"aes_decrypt(unbase64($colName), '')"), 0)
      checkInvalidKeyLength(df2.selectExpr(
        s"aes_decrypt(unbase64($colName), binary(''))"), 0)
    }
  }

  test("INVALID_PARAMETER_VALUE: AES decrypt failure - key mismatch") {
    val (_, df2) = getAesInputs()
    Seq(
      ("value16", "1234567812345678"),
      ("value24", "123456781234567812345678"),
      ("value32", "12345678123456781234567812345678")).foreach { case (colName, key) =>
      checkError(
        exception = intercept[SparkException] {
          df2.selectExpr(s"aes_decrypt(unbase64($colName), binary('$key'), 'ECB')").collect
        }.getCause.asInstanceOf[SparkRuntimeException],
        errorClass = "INVALID_PARAMETER_VALUE",
        parameters = Map("parameter" -> "expr, key",
          "functionName" -> "`aes_encrypt`/`aes_decrypt`",
          "expected" -> ("Detail message: " +
            "Given final block not properly padded. " +
            "Such issues can arise if a bad key is used during decryption.")),
        sqlState = "22023")
    }
  }

  test("UNSUPPORTED_FEATURE: unsupported combinations of AES modes and padding") {
    val key16 = "abcdefghijklmnop"
    val key32 = "abcdefghijklmnop12345678ABCDEFGH"
    val (df1, df2) = getAesInputs()
    def checkUnsupportedMode(df: => DataFrame, mode: String, padding: String): Unit = {
      checkError(
        exception = intercept[SparkException] {
          df.collect
        }.getCause.asInstanceOf[SparkRuntimeException],
        errorClass = "UNSUPPORTED_FEATURE",
        errorSubClass = "AES_MODE",
        parameters = Map("mode" -> mode,
        "padding" -> padding,
        "functionName" -> "`aes_encrypt`/`aes_decrypt`"),
        sqlState = "0A000")
    }

    // Unsupported AES mode and padding in encrypt
    checkUnsupportedMode(df1.selectExpr(s"aes_encrypt(value, '$key16', 'CBC')"),
      "CBC", "DEFAULT")
    checkUnsupportedMode(df1.selectExpr(s"aes_encrypt(value, '$key16', 'ECB', 'NoPadding')"),
      "ECB", "NoPadding")

    // Unsupported AES mode and padding in decrypt
    checkUnsupportedMode(df2.selectExpr(s"aes_decrypt(value16, '$key16', 'GSM')"),
    "GSM", "DEFAULT")
    checkUnsupportedMode(df2.selectExpr(s"aes_decrypt(value16, '$key16', 'GCM', 'PKCS')"),
    "GCM", "PKCS")
    checkUnsupportedMode(df2.selectExpr(s"aes_decrypt(value32, '$key32', 'ECB', 'None')"),
    "ECB", "None")
  }

  test("UNSUPPORTED_FEATURE: unsupported types (map and struct) in lit()") {
    def checkUnsupportedTypeInLiteral(v: Any, literal: String, dataType: String): Unit = {
      checkError(
        exception = intercept[SparkRuntimeException] { lit(v) },
        errorClass = "UNSUPPORTED_FEATURE",
        errorSubClass = "LITERAL_TYPE",
        parameters = Map("value" -> literal, "type" -> dataType),
        sqlState = "0A000")
    }
    checkUnsupportedTypeInLiteral(Map("key1" -> 1, "key2" -> 2),
      "Map(key1 -> 1, key2 -> 2)",
      "class scala.collection.immutable.Map$Map2")
    checkUnsupportedTypeInLiteral(("mike", 29, 1.0), "(mike,29,1.0)", "class scala.Tuple3")

    val e2 = intercept[SparkRuntimeException] {
      trainingSales
        .groupBy($"sales.year")
        .pivot(struct(lower(trainingSales("sales.course")), trainingSales("training")))
        .agg(sum($"sales.earnings"))
        .collect()
    }
    checkError(
      exception = e2,
      errorClass = "UNSUPPORTED_FEATURE",
      errorSubClass = "PIVOT_TYPE",
      parameters = Map("value" -> "[dotnet,Dummies]",
      "type" -> "\"STRUCT<col1: STRING, training: STRING>\""),
      sqlState = "0A000")
  }

  test("UNSUPPORTED_FEATURE: unsupported pivot operations") {
    val e1 = intercept[SparkUnsupportedOperationException] {
      trainingSales
        .groupBy($"sales.year")
        .pivot($"sales.course")
        .pivot($"training")
        .agg(sum($"sales.earnings"))
        .collect()
    }
    checkError(
      exception = e1,
      errorClass = "UNSUPPORTED_FEATURE",
      errorSubClass = "REPEATED_PIVOT",
      parameters = Map[String, String](),
      sqlState = "0A000")

    val e2 = intercept[SparkUnsupportedOperationException] {
      trainingSales
        .rollup($"sales.year")
        .pivot($"training")
        .agg(sum($"sales.earnings"))
        .collect()
    }
    checkError(
      exception = e2,
      errorClass = "UNSUPPORTED_FEATURE",
      errorSubClass = "PIVOT_AFTER_GROUP_BY",
      parameters = Map[String, String](),
      sqlState = "0A000")
  }

  test("INCONSISTENT_BEHAVIOR_CROSS_VERSION: " +
    "compatibility with Spark 2.4/3.2 in reading/writing dates") {

    // Fail to read ancient datetime values.
    withSQLConf(SQLConf.PARQUET_REBASE_MODE_IN_READ.key -> EXCEPTION.toString) {
      val fileName = "before_1582_date_v2_4_5.snappy.parquet"
      val filePath = getResourceParquetFilePath("test-data/" + fileName)
      val e = intercept[SparkException] {
        spark.read.parquet(filePath).collect()
      }.getCause.asInstanceOf[SparkUpgradeException]

      val format = "Parquet"
      val config = "\"" + SQLConf.PARQUET_REBASE_MODE_IN_READ.key + "\""
      val option = "\"datetimeRebaseMode\""
      checkError(
        exception = e,
        errorClass = "INCONSISTENT_BEHAVIOR_CROSS_VERSION",
        errorSubClass = "READ_ANCIENT_DATETIME",
        parameters = Map("format" -> format,
        "config" -> config,
        "option" -> option),
        sqlState = null)
    }

    // Fail to write ancient datetime values.
    withSQLConf(SQLConf.PARQUET_REBASE_MODE_IN_WRITE.key -> EXCEPTION.toString) {
      withTempPath { dir =>
        val df = Seq(java.sql.Date.valueOf("1001-01-01")).toDF("dt")
        val e = intercept[SparkException] {
          df.write.parquet(dir.getCanonicalPath)
        }.getCause.getCause.getCause.asInstanceOf[SparkUpgradeException]

        val format = "Parquet"
        val config = "\"" + SQLConf.PARQUET_REBASE_MODE_IN_WRITE.key + "\""
        checkError(
          exception = e,
          errorClass = "INCONSISTENT_BEHAVIOR_CROSS_VERSION",
          errorSubClass = "WRITE_ANCIENT_DATETIME",
          parameters = Map("format" -> format,
            "config" -> config),
          sqlState = null)
      }
    }
  }

  test("UNSUPPORTED_FEATURE - SPARK-36346: can't read Timestamp as TimestampNTZ") {
    withTempPath { file =>
      sql("select timestamp_ltz'2019-03-21 00:02:03'").write.orc(file.getCanonicalPath)
      withAllNativeOrcReaders {
        checkError(
          exception = intercept[SparkException] {
            spark.read.schema("time timestamp_ntz").orc(file.getCanonicalPath).collect()
          }.getCause.asInstanceOf[SparkUnsupportedOperationException],
          errorClass = "UNSUPPORTED_FEATURE",
          errorSubClass = "ORC_TYPE_CAST",
          parameters = Map("orcType" -> "\"TIMESTAMP\"",
            "toType" -> "\"TIMESTAMP_NTZ\""),
          sqlState = "0A000")
      }
    }
  }

  test("UNSUPPORTED_FEATURE - SPARK-38504: can't read TimestampNTZ as TimestampLTZ") {
    withTempPath { file =>
      sql("select timestamp_ntz'2019-03-21 00:02:03'").write.orc(file.getCanonicalPath)
      withAllNativeOrcReaders {
        checkError(
          exception = intercept[SparkException] {
            spark.read.schema("time timestamp_ltz").orc(file.getCanonicalPath).collect()
          }.getCause.asInstanceOf[SparkUnsupportedOperationException],
          errorClass = "UNSUPPORTED_FEATURE",
          errorSubClass = "ORC_TYPE_CAST",
          parameters = Map("orcType" -> "\"TIMESTAMP_NTZ\"",
            "toType" -> "\"TIMESTAMP\""),
          sqlState = "0A000")
      }
    }
  }

  test("DATETIME_OVERFLOW: timestampadd() overflows its input timestamp") {
    checkError(
      exception = intercept[SparkArithmeticException] {
        sql("select timestampadd(YEAR, 1000000, timestamp'2022-03-09 01:02:03')").collect()
      },
      errorClass = "DATETIME_OVERFLOW",
      parameters = Map("operation" -> "add 1000000 YEAR to TIMESTAMP '2022-03-09 01:02:03'"),
      sqlState = "22008")
  }

  test("CANNOT_PARSE_DECIMAL: unparseable decimal") {
    val e1 = intercept[SparkException] {
      withTempPath { path =>

        // original text
        val df1 = Seq(
          "money",
          "\"$92,807.99\""
        ).toDF()

        df1.coalesce(1).write.text(path.getAbsolutePath)

        val schema = new StructType().add("money", DecimalType.DoubleDecimal)
        spark
          .read
          .schema(schema)
          .format("csv")
          .option("header", "true")
          .option("locale", Locale.ROOT.toLanguageTag)
          .option("multiLine", "true")
          .option("inferSchema", "false")
          .option("mode", "FAILFAST")
          .load(path.getAbsolutePath).select($"money").collect()
      }
    }
    assert(e1.getCause.isInstanceOf[QueryExecutionException])

    val e2 = e1.getCause.asInstanceOf[QueryExecutionException]
    assert(e2.getCause.isInstanceOf[SparkException])

    val e3 = e2.getCause.asInstanceOf[SparkException]
    assert(e3.getCause.isInstanceOf[BadRecordException])

    val e4 = e3.getCause.asInstanceOf[BadRecordException]
    assert(e4.getCause.isInstanceOf[SparkRuntimeException])

    checkError(
      exception = e4.getCause.asInstanceOf[SparkRuntimeException],
      errorClass = "CANNOT_PARSE_DECIMAL",
      parameters = Map[String, String](),
      sqlState = "42000")
  }

  test("WRITING_JOB_ABORTED: read of input data fails in the middle") {
    Seq(classOf[SimpleWritableDataSource], classOf[JavaSimpleWritableDataSource]).foreach { cls =>
      withTempPath { file =>
        val path = file.getCanonicalPath
        assert(spark.read.format(cls.getName).option("path", path).load().collect().isEmpty)
        // test transaction
        val failingUdf = org.apache.spark.sql.functions.udf {
          var count = 0
          (id: Long) => {
            if (count > 5) {
              throw new RuntimeException("testing error")
            }
            count += 1
            id
          }
        }
        val input = spark.range(15).select(failingUdf($"id").as(Symbol("i")))
          .select($"i", -$"i" as Symbol("j"))
        checkError(
          exception = intercept[SparkException] {
            input.write.format(cls.getName).option("path", path).mode("overwrite").save()
          },
          errorClass = "WRITING_JOB_ABORTED",
          parameters = Map[String, String](),
          sqlState = "40000")
        // make sure we don't have partial data.
        assert(spark.read.format(cls.getName).option("path", path).load().collect().isEmpty)
      }
    }
  }

  test("FAILED_EXECUTE_UDF: execute user defined function") {
    val luckyCharOfWord = udf { (word: String, index: Int) => {
      word.substring(index, index + 1)
    }}
    val e1 = intercept[SparkException] {
      val words = Seq(("Jacek", 5), ("Agata", 5), ("Sweet", 6)).toDF("word", "index")
      words.select(luckyCharOfWord($"word", $"index")).collect()
    }
    assert(e1.getCause.isInstanceOf[SparkException])

    Utils.getSimpleName(luckyCharOfWord.getClass)

    checkError(
      exception = e1.getCause.asInstanceOf[SparkException],
      errorClass = "FAILED_EXECUTE_UDF",
      errorSubClass = None,
      parameters = Map("functionName" -> "QueryExecutionErrorsSuite\\$\\$Lambda\\$\\d+/\\w+",
        "signature" -> "string, int",
        "result" -> "string"),
      sqlState = None,
      matchPVals = true)
  }

  test("INCOMPARABLE_PIVOT_COLUMN: an incomparable column of the map type") {
    val e = intercept[AnalysisException] {
      trainingSales
      sql(
        """
          | select *
          | from (
          |   select *,map(sales.course, sales.year) as map
          |   from trainingSales
          | )
          | pivot (
          |   sum(sales.earnings) as sum
          |   for map in (
          |     map("dotNET", 2012), map("JAVA", 2012),
          |     map("dotNet", 2013), map("Java", 2013)
          |   )
          | )
          |""".stripMargin).collect()
    }
    checkError(
      exception = e,
      errorClass = "INCOMPARABLE_PIVOT_COLUMN",
      parameters = Map("columnName" -> "`__auto_generated_subquery_name`.`map`"),
      sqlState = "42000")
  }

  test("UNSUPPORTED_SAVE_MODE: unsupported null saveMode whether the path exists or not") {
    withTempPath { path =>
      val e1 = intercept[SparkIllegalArgumentException] {
        val saveMode: SaveMode = null
        Seq(1, 2).toDS().write.mode(saveMode).parquet(path.getAbsolutePath)
      }
      checkError(
        exception = e1,
        errorClass = "UNSUPPORTED_SAVE_MODE",
        errorSubClass = "NON_EXISTENT_PATH",
        parameters = Map("saveMode" -> "NULL"),
        sqlState = null)

      Utils.createDirectory(path)

      val e2 = intercept[SparkIllegalArgumentException] {
        val saveMode: SaveMode = null
        Seq(1, 2).toDS().write.mode(saveMode).parquet(path.getAbsolutePath)
      }
      checkError(
        exception = e2,
        errorClass = "UNSUPPORTED_SAVE_MODE",
        errorSubClass = "EXISTENT_PATH",
        parameters = Map("saveMode" -> "NULL"),
        sqlState = null)
    }
  }

  test("RESET_PERMISSION_TO_ORIGINAL: can't set permission") {
      withTable("t") {
        withSQLConf(
          "fs.file.impl" -> classOf[FakeFileSystemSetPermission].getName,
          "fs.file.impl.disable.cache" -> "true") {
          sql("CREATE TABLE t(c String) USING parquet")

          val e = intercept[AnalysisException] {
            sql("TRUNCATE TABLE t")
          }
          assert(e.getCause.isInstanceOf[SparkSecurityException])

          checkError(
            exception = e.getCause.asInstanceOf[SparkSecurityException],
            errorClass = "RESET_PERMISSION_TO_ORIGINAL",
            errorSubClass = None,
            parameters = Map("permission" -> ".+",
              "path" -> ".+",
              "message" -> ".+"),
            sqlState = None,
            matchPVals = true)
      }
    }
  }

  test("INCOMPATIBLE_DATASOURCE_REGISTER: create table using an incompatible data source") {
    val newClassLoader = new ClassLoader() {

      override def getResources(name: String): java.util.Enumeration[URL] = {
        if (name.equals("META-INF/services/org.apache.spark.sql.sources.DataSourceRegister")) {
          // scalastyle:off
          throw new ServiceConfigurationError(s"Illegal configuration-file syntax: $name",
            new NoClassDefFoundError("org.apache.spark.sql.sources.HadoopFsRelationProvider"))
          // scalastyle:on throwerror
        } else {
          super.getResources(name)
        }
      }
    }

    Utils.withContextClassLoader(newClassLoader) {
      val e = intercept[SparkClassNotFoundException] {
        sql("CREATE TABLE student (id INT, name STRING, age INT) USING org.apache.spark.sql.fake")
      }
      checkError(
        exception = e,
        errorClass = "INCOMPATIBLE_DATASOURCE_REGISTER",
        parameters = Map("message" -> ("Illegal configuration-file syntax: " +
          "META-INF/services/org.apache.spark.sql.sources.DataSourceRegister")))
    }
  }

  test("UNRECOGNIZED_SQL_TYPE: unrecognized SQL type -100") {
    Utils.classForName("org.h2.Driver")

    val properties = new Properties()
    properties.setProperty("user", "testUser")
    properties.setProperty("password", "testPass")

    val url = "jdbc:h2:mem:testdb0"
    val urlWithUserAndPass = "jdbc:h2:mem:testdb0;user=testUser;password=testPass"
    val tableName = "test.table1"
    val unrecognizedColumnType = -100

    var conn: java.sql.Connection = null
    try {
      conn = DriverManager.getConnection(url, properties)
      conn.prepareStatement("create schema test").executeUpdate()
      conn.commit()

      conn.prepareStatement(s"create table $tableName (a INT)").executeUpdate()
      conn.prepareStatement(
        s"insert into $tableName values (1)").executeUpdate()
      conn.commit()
    } finally {
      if (null != conn) {
        conn.close()
      }
    }

    val testH2DialectUnrecognizedSQLType = new JdbcDialect {
      override def canHandle(url: String): Boolean = url.startsWith("jdbc:h2")

      override def getCatalystType(sqlType: Int, typeName: String, size: Int,
        md: MetadataBuilder): Option[DataType] = {
        sqlType match {
          case _ => None
        }
      }

      override def createConnectionFactory(options: JDBCOptions): Int => Connection = {
        val driverClass: String = options.driverClass

        (_: Int) => {
          DriverRegistry.register(driverClass)

          val resultSetMetaData = mock(classOf[ResultSetMetaData])
          when(resultSetMetaData.getColumnCount).thenReturn(1)
          when(resultSetMetaData.getColumnType(1)).thenReturn(unrecognizedColumnType)

          val resultSet = mock(classOf[ResultSet])
          when(resultSet.next()).thenReturn(true).thenReturn(false)
          when(resultSet.getMetaData).thenReturn(resultSetMetaData)

          val preparedStatement = mock(classOf[PreparedStatement])
          when(preparedStatement.executeQuery).thenReturn(resultSet)

          val connection = mock(classOf[Connection])
          when(connection.prepareStatement(s"SELECT * FROM $tableName WHERE 1=0")).
            thenReturn(preparedStatement)

          connection
        }
      }
    }

    val existH2Dialect = JdbcDialects.get(urlWithUserAndPass)
    JdbcDialects.unregisterDialect(existH2Dialect)

    JdbcDialects.registerDialect(testH2DialectUnrecognizedSQLType)

    checkError(
      exception = intercept[SparkSQLException] {
        spark.read.jdbc(urlWithUserAndPass, tableName, new Properties()).collect()
      },
      errorClass = "UNRECOGNIZED_SQL_TYPE",
      parameters = Map("typeName" -> unrecognizedColumnType.toString),
      sqlState = "42000")

    JdbcDialects.unregisterDialect(testH2DialectUnrecognizedSQLType)
  }

  test("INVALID_BUCKET_FILE: error if there exists any malformed bucket files") {
    val df1 = (0 until 50).map(i => (i % 5, i % 13, i.toString)).
      toDF("i", "j", "k").as("df1")

    withTable("bucketed_table") {
      df1.write.format("parquet").bucketBy(8, "i").
        saveAsTable("bucketed_table")
      val warehouseFilePath = new URI(spark.sessionState.conf.warehousePath).getPath
      val tableDir = new File(warehouseFilePath, "bucketed_table")
      Utils.deleteRecursively(tableDir)
      df1.write.parquet(tableDir.getAbsolutePath)

      val aggregated = spark.table("bucketed_table").groupBy("i").count()

      checkError(
        exception = intercept[SparkException] {
          aggregated.count()
        },
        errorClass = "INVALID_BUCKET_FILE",
        errorSubClass = None,
        parameters = Map("path" -> ".+"),
        sqlState = None,
        matchPVals = true)
    }
  }

  test("MULTI_VALUE_SUBQUERY_ERROR: " +
    "more than one row returned by a subquery used as an expression") {
    checkError(
      exception = intercept[SparkException] {
        sql("select (select a from (select 1 as a union all select 2 as a) t) as b").collect()
      },
      errorClass = "MULTI_VALUE_SUBQUERY_ERROR",
      errorSubClass = None,
      parameters = Map("plan" ->
          """Subquery subquery#\w+, \[id=#\w+\]
            |\+\- AdaptiveSparkPlan isFinalPlan=true
            |   \+\- == Final Plan ==
            |      Union
            |      :\- \*\(1\) Project \[\w+ AS a#\w+\]
            |      :  \+\- \*\(1\) Scan OneRowRelation\[\]
            |      \+\- \*\(2\) Project \[\w+ AS a#\w+\]
            |         \+\- \*\(2\) Scan OneRowRelation\[\]
            |   \+\- == Initial Plan ==
            |      Union
            |      :\- Project \[\w+ AS a#\w+\]
            |      :  \+\- Scan OneRowRelation\[\]
            |      \+\- Project \[\w+ AS a#\w+\]
            |         \+\- Scan OneRowRelation\[\]
            |""".stripMargin),
      sqlState = None,
      matchPVals = true)
  }

  test("ELEMENT_AT_BY_INDEX_ZERO: element_at from array by index zero") {
    checkError(
      exception = intercept[SparkRuntimeException](
        sql("select element_at(array(1, 2, 3, 4, 5), 0)").collect()
      ),
      errorClass = "ELEMENT_AT_BY_INDEX_ZERO",
      Map.empty
    )
  }
}

class FakeFileSystemSetPermission extends LocalFileSystem {

  override def setPermission(src: Path, permission: FsPermission): Unit = {
    throw new IOException(s"fake fileSystem failed to set permission: $permission")
  }
}
