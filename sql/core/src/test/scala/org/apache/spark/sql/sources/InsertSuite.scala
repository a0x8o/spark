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

package org.apache.spark.sql.sources

import java.io.{File, IOException}
import java.sql.Date
import java.time.{Duration, Period}

import org.apache.hadoop.fs.{FileAlreadyExistsException, FSDataOutputStream, Path, RawLocalFileSystem}

import org.apache.spark.SparkException
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog.{CatalogStorageFormat, CatalogTable, CatalogTableType}
import org.apache.spark.sql.catalyst.parser.ParseException
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.execution.datasources.DataSourceUtils
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf.PartitionOverwriteMode
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._
import org.apache.spark.util.Utils

class SimpleInsertSource extends SchemaRelationProvider {
  override def createRelation(
      sqlContext: SQLContext,
      parameters: Map[String, String],
      schema: StructType): BaseRelation = {
    SimpleInsert(schema)(sqlContext.sparkSession)
  }
}

case class SimpleInsert(userSpecifiedSchema: StructType)(@transient val sparkSession: SparkSession)
  extends BaseRelation with InsertableRelation {

  override def sqlContext: SQLContext = sparkSession.sqlContext

  override def schema: StructType = userSpecifiedSchema

  override def insert(input: DataFrame, overwrite: Boolean): Unit = {
    input.collect
  }
}

class InsertSuite extends DataSourceTest with SharedSparkSession {
  import testImplicits._

  protected override lazy val sql = spark.sql _
  private var path: File = null

  override def beforeAll(): Unit = {
    super.beforeAll()
    path = Utils.createTempDir()
    val ds = (1 to 10).map(i => s"""{"a":$i, "b":"str$i"}""").toDS()
    spark.read.json(ds).createOrReplaceTempView("jt")
    sql(
      s"""
        |CREATE TEMPORARY VIEW jsonTable (a int, b string)
        |USING org.apache.spark.sql.json.DefaultSource
        |OPTIONS (
        |  path '${path.toURI.toString}'
        |)
      """.stripMargin)
  }

  override def afterAll(): Unit = {
    try {
      spark.catalog.dropTempView("jsonTable")
      spark.catalog.dropTempView("jt")
      Utils.deleteRecursively(path)
    } finally {
      super.afterAll()
    }
  }

  test("Simple INSERT OVERWRITE a JSONRelation") {
    sql(
      s"""
        |INSERT OVERWRITE TABLE jsonTable SELECT a, b FROM jt
      """.stripMargin)

    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      (1 to 10).map(i => Row(i, s"str$i"))
    )
  }

  test("insert into a temp view that does not point to an insertable data source") {
    import testImplicits._
    withTempView("t1", "t2") {
      sql(
        """
          |CREATE TEMPORARY VIEW t1
          |USING org.apache.spark.sql.sources.SimpleScanSource
          |OPTIONS (
          |  From '1',
          |  To '10')
        """.stripMargin)
      sparkContext.parallelize(1 to 10).toDF("a").createOrReplaceTempView("t2")

      val message = intercept[AnalysisException] {
        sql("INSERT INTO TABLE t1 SELECT a FROM t2")
      }.getMessage
      assert(message.contains("does not allow insertion"))
    }
  }

  test("PreInsert casting and renaming") {
    sql(
      s"""
        |INSERT OVERWRITE TABLE jsonTable SELECT a * 2, a * 4 FROM jt
      """.stripMargin)

    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      (1 to 10).map(i => Row(i * 2, s"${i * 4}"))
    )

    sql(
      s"""
        |INSERT OVERWRITE TABLE jsonTable SELECT a * 4 AS A, a * 6 as c FROM jt
      """.stripMargin)

    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      (1 to 10).map(i => Row(i * 4, s"${i * 6}"))
    )
  }

  test("SELECT clause generating a different number of columns is not allowed.") {
    val message = intercept[AnalysisException] {
      sql(
        s"""
        |INSERT OVERWRITE TABLE jsonTable SELECT a FROM jt
      """.stripMargin)
    }.getMessage
    assert(message.contains("target table has 2 column(s) but the inserted data has 1 column(s)")
    )
  }

  test("INSERT OVERWRITE a JSONRelation multiple times") {
    sql(
      s"""
         |INSERT OVERWRITE TABLE jsonTable SELECT a, b FROM jt
    """.stripMargin)
    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      (1 to 10).map(i => Row(i, s"str$i"))
    )

    // Writing the table to less part files.
    val rdd1 = sparkContext.parallelize((1 to 10).map(i => s"""{"a":$i, "b":"str$i"}"""), 5)
    spark.read.json(rdd1.toDS()).createOrReplaceTempView("jt1")
    sql(
      s"""
         |INSERT OVERWRITE TABLE jsonTable SELECT a, b FROM jt1
    """.stripMargin)
    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      (1 to 10).map(i => Row(i, s"str$i"))
    )

    // Writing the table to more part files.
    val rdd2 = sparkContext.parallelize((1 to 10).map(i => s"""{"a":$i, "b":"str$i"}"""), 10)
    spark.read.json(rdd2.toDS()).createOrReplaceTempView("jt2")
    sql(
      s"""
         |INSERT OVERWRITE TABLE jsonTable SELECT a, b FROM jt2
    """.stripMargin)
    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      (1 to 10).map(i => Row(i, s"str$i"))
    )

    sql(
      s"""
         |INSERT OVERWRITE TABLE jsonTable SELECT a * 10, b FROM jt1
    """.stripMargin)
    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      (1 to 10).map(i => Row(i * 10, s"str$i"))
    )

    spark.catalog.dropTempView("jt1")
    spark.catalog.dropTempView("jt2")
  }

  test("INSERT INTO JSONRelation for now") {
    sql(
      s"""
      |INSERT OVERWRITE TABLE jsonTable SELECT a, b FROM jt
    """.stripMargin)
    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      sql("SELECT a, b FROM jt").collect()
    )

    sql(
      s"""
         |INSERT INTO TABLE jsonTable SELECT a, b FROM jt
    """.stripMargin)
    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      sql("SELECT a, b FROM jt UNION ALL SELECT a, b FROM jt").collect()
    )
  }

  test("INSERT INTO TABLE with Comment in columns") {
    val tabName = "tab1"
    withTable(tabName) {
      sql(
        s"""
           |CREATE TABLE $tabName(col1 int COMMENT 'a', col2 int)
           |USING parquet
         """.stripMargin)
      sql(s"INSERT INTO TABLE $tabName SELECT 1, 2")

      checkAnswer(
        sql(s"SELECT col1, col2 FROM $tabName"),
        Row(1, 2) :: Nil
      )
    }
  }

  test("INSERT INTO TABLE - complex type but different names") {
    val tab1 = "tab1"
    val tab2 = "tab2"
    withTable(tab1, tab2) {
      sql(
        s"""
           |CREATE TABLE $tab1 (s struct<a: string, b: string>)
           |USING parquet
         """.stripMargin)
      sql(s"INSERT INTO TABLE $tab1 SELECT named_struct('col1','1','col2','2')")

      sql(
        s"""
           |CREATE TABLE $tab2 (p struct<c: string, d: string>)
           |USING parquet
         """.stripMargin)
      sql(s"INSERT INTO TABLE $tab2 SELECT * FROM $tab1")

      checkAnswer(
        spark.table(tab1),
        spark.table(tab2)
      )
    }
  }

  test("it is not allowed to write to a table while querying it.") {
    val message = intercept[AnalysisException] {
      sql(
        s"""
        |INSERT OVERWRITE TABLE jsonTable SELECT a, b FROM jsonTable
      """.stripMargin)
    }.getMessage
    assert(
      message.contains("Cannot overwrite a path that is also being read from."),
      "INSERT OVERWRITE to a table while querying it should not be allowed.")
  }

  test("SPARK-30112: it is allowed to write to a table while querying it for " +
    "dynamic partition overwrite.") {
    Seq(PartitionOverwriteMode.DYNAMIC.toString,
        PartitionOverwriteMode.STATIC.toString).foreach { mode =>
      withSQLConf(SQLConf.PARTITION_OVERWRITE_MODE.key -> mode) {
        withTable("insertTable") {
          sql(
            """
              |CREATE TABLE insertTable(i int, part1 int, part2 int) USING PARQUET
              |PARTITIONED BY (part1, part2)
            """.stripMargin)

          sql("INSERT INTO TABLE insertTable PARTITION(part1=1, part2=1) SELECT 1")
          checkAnswer(spark.table("insertTable"), Row(1, 1, 1))
          sql("INSERT OVERWRITE TABLE insertTable PARTITION(part1=1, part2=2) SELECT 2")
          checkAnswer(spark.table("insertTable"), Row(1, 1, 1) :: Row(2, 1, 2) :: Nil)

          if (mode == PartitionOverwriteMode.DYNAMIC.toString) {
            sql(
              """
                |INSERT OVERWRITE TABLE insertTable PARTITION(part1=1, part2)
                |SELECT i + 1, part2 FROM insertTable
              """.stripMargin)
            checkAnswer(spark.table("insertTable"), Row(2, 1, 1) :: Row(3, 1, 2) :: Nil)

            sql(
              """
                |INSERT OVERWRITE TABLE insertTable PARTITION(part1=1, part2)
                |SELECT i + 1, part2 + 1 FROM insertTable
              """.stripMargin)
            checkAnswer(spark.table("insertTable"),
              Row(2, 1, 1) :: Row(3, 1, 2) :: Row(4, 1, 3) :: Nil)
          } else {
            val message = intercept[AnalysisException] {
              sql(
                """
                  |INSERT OVERWRITE TABLE insertTable PARTITION(part1=1, part2)
                  |SELECT i + 1, part2 FROM insertTable
                """.stripMargin)
            }.getMessage
            assert(
              message.contains("Cannot overwrite a path that is also being read from."),
              "INSERT OVERWRITE to a table while querying it should not be allowed.")
          }
        }
      }
    }
  }

  test("Caching")  {
    // write something to the jsonTable
    sql(
      s"""
         |INSERT OVERWRITE TABLE jsonTable SELECT a, b FROM jt
      """.stripMargin)
    // Cached Query Execution
    spark.catalog.cacheTable("jsonTable")
    assertCached(sql("SELECT * FROM jsonTable"))
    checkAnswer(
      sql("SELECT * FROM jsonTable"),
      (1 to 10).map(i => Row(i, s"str$i")))

    assertCached(sql("SELECT a FROM jsonTable"))
    checkAnswer(
      sql("SELECT a FROM jsonTable"),
      (1 to 10).map(Row(_)).toSeq)

    assertCached(sql("SELECT a FROM jsonTable WHERE a < 5"))
    checkAnswer(
      sql("SELECT a FROM jsonTable WHERE a < 5"),
      (1 to 4).map(Row(_)).toSeq)

    assertCached(sql("SELECT a * 2 FROM jsonTable"))
    checkAnswer(
      sql("SELECT a * 2 FROM jsonTable"),
      (1 to 10).map(i => Row(i * 2)).toSeq)

    assertCached(sql(
      "SELECT x.a, y.a FROM jsonTable x JOIN jsonTable y ON x.a = y.a + 1"), 2)
    checkAnswer(sql(
      "SELECT x.a, y.a FROM jsonTable x JOIN jsonTable y ON x.a = y.a + 1"),
      (2 to 10).map(i => Row(i, i - 1)).toSeq)

    // Insert overwrite and keep the same schema.
    sql(
      s"""
        |INSERT OVERWRITE TABLE jsonTable SELECT a * 2, b FROM jt
      """.stripMargin)
    // jsonTable should be recached.
    assertCached(sql("SELECT * FROM jsonTable"))

    // The cached data is the new data.
    checkAnswer(
      sql("SELECT a, b FROM jsonTable"),
      sql("SELECT a * 2, b FROM jt").collect())

    // Verify uncaching
    spark.catalog.uncacheTable("jsonTable")
    assertCached(sql("SELECT * FROM jsonTable"), 0)
  }

  test("it's not allowed to insert into a relation that is not an InsertableRelation") {
    sql(
      """
        |CREATE TEMPORARY VIEW oneToTen
        |USING org.apache.spark.sql.sources.SimpleScanSource
        |OPTIONS (
        |  From '1',
        |  To '10'
        |)
      """.stripMargin)

    checkAnswer(
      sql("SELECT * FROM oneToTen"),
      (1 to 10).map(Row(_)).toSeq
    )

    val message = intercept[AnalysisException] {
      sql(
        s"""
        |INSERT OVERWRITE TABLE oneToTen SELECT CAST(a AS INT) FROM jt
        """.stripMargin)
    }.getMessage
    assert(
      message.contains("does not allow insertion."),
      "It is not allowed to insert into a table that is not an InsertableRelation."
    )

    spark.catalog.dropTempView("oneToTen")
  }

  test("SPARK-15824 - Execute an INSERT wrapped in a WITH statement immediately") {
    def test: Unit = withTable("target", "target2") {
      sql(s"CREATE TABLE target(a INT, b STRING) USING JSON")
      sql("WITH tbl AS (SELECT * FROM jt) INSERT OVERWRITE TABLE target SELECT a, b FROM tbl")
      checkAnswer(
        sql("SELECT a, b FROM target"),
        sql("SELECT a, b FROM jt")
      )

      sql(s"CREATE TABLE target2(a INT, b STRING) USING JSON")
      val e = sql(
        """
          |WITH tbl AS (SELECT * FROM jt)
          |FROM tbl
          |INSERT INTO target2 SELECT a, b WHERE a <= 5
          |INSERT INTO target2 SELECT a, b WHERE a > 5
        """.stripMargin)
      checkAnswer(
        sql("SELECT a, b FROM target2"),
        sql("SELECT a, b FROM jt")
      )
    }
    withSQLConf(SQLConf.ENABLE_DEFAULT_COLUMNS.key -> "true") {
      test
    }
    withSQLConf(SQLConf.ENABLE_DEFAULT_COLUMNS.key -> "false") {
      test
    }
  }

  test("SPARK-21203 wrong results of insertion of Array of Struct") {
    val tabName = "tab1"
    withTable(tabName) {
      spark.sql(
        """
          |CREATE TABLE `tab1`
          |(`custom_fields` ARRAY<STRUCT<`id`: BIGINT, `value`: STRING>>)
          |USING parquet
        """.stripMargin)
      spark.sql(
        """
          |INSERT INTO `tab1`
          |SELECT ARRAY(named_struct('id', 1, 'value', 'a'), named_struct('id', 2, 'value', 'b'))
        """.stripMargin)

      checkAnswer(
        spark.sql("SELECT custom_fields.id, custom_fields.value FROM tab1"),
        Row(Array(1, 2), Array("a", "b")))
    }
  }

  test("insert overwrite directory") {
    withTempDir { dir =>
      val path = dir.toURI.getPath

      val v1 =
        s"""
           | INSERT OVERWRITE DIRECTORY '${path}'
           | USING json
           | OPTIONS (a 1, b 0.1, c TRUE)
           | SELECT 1 as a, 'c' as b
         """.stripMargin

      spark.sql(v1)

      checkAnswer(
        spark.read.json(dir.getCanonicalPath),
        sql("SELECT 1 as a, 'c' as b"))
    }
  }

  test("insert overwrite directory with path in options") {
    withTempDir { dir =>
      val path = dir.toURI.getPath

      val v1 =
        s"""
           | INSERT OVERWRITE DIRECTORY
           | USING json
           | OPTIONS ('path' '${path}')
           | SELECT 1 as a, 'c' as b
         """.stripMargin

      spark.sql(v1)

      checkAnswer(
        spark.read.json(dir.getCanonicalPath),
        sql("SELECT 1 as a, 'c' as b"))
    }
  }

  test("Insert overwrite directory using Hive serde without turning on Hive support") {
    withTempDir { dir =>
      val path = dir.toURI.getPath
      val e = intercept[AnalysisException] {
        sql(
          s"""
             |INSERT OVERWRITE LOCAL DIRECTORY '$path'
             |STORED AS orc
             |SELECT 1, 2
           """.stripMargin)
      }.getMessage
      assert(e.contains(
        "Hive support is required to INSERT OVERWRITE DIRECTORY with the Hive format"))
    }
  }

  test("insert overwrite directory to data source not providing FileFormat") {
    withTempDir { dir =>
      val path = dir.toURI.getPath

      val v1 =
        s"""
           | INSERT OVERWRITE DIRECTORY '${path}'
           | USING JDBC
           | OPTIONS (a 1, b 0.1, c TRUE)
           | SELECT 1 as a, 'c' as b
         """.stripMargin
      val e = intercept[SparkException] {
        spark.sql(v1)
      }.getMessage

      assert(e.contains("Only Data Sources providing FileFormat are supported"))
    }
  }

  test("new partitions should be added to catalog after writing to catalog table") {
    val table = "partitioned_catalog_table"
    val tempTable = "partitioned_catalog_temp_table"
    val numParts = 210
    withTable(table) {
      withTempView(tempTable) {
        val df = (1 to numParts).map(i => (i, i)).toDF("part", "col1")
        df.createOrReplaceTempView(tempTable)
        sql(s"CREATE TABLE $table (part Int, col1 Int) USING parquet PARTITIONED BY (part)")
        sql(s"INSERT INTO TABLE $table SELECT * from $tempTable")
        val partitions = spark.sessionState.catalog.listPartitionNames(TableIdentifier(table))
        assert(partitions.size == numParts)
      }
    }
  }

  test("SPARK-20236: dynamic partition overwrite without catalog table") {
    withSQLConf(SQLConf.PARTITION_OVERWRITE_MODE.key -> PartitionOverwriteMode.DYNAMIC.toString) {
      withTempPath { path =>
        Seq((1, 1, 1)).toDF("i", "part1", "part2")
          .write.partitionBy("part1", "part2").parquet(path.getAbsolutePath)
        checkAnswer(spark.read.parquet(path.getAbsolutePath), Row(1, 1, 1))

        Seq((2, 1, 1)).toDF("i", "part1", "part2")
          .write.partitionBy("part1", "part2").mode("overwrite").parquet(path.getAbsolutePath)
        checkAnswer(spark.read.parquet(path.getAbsolutePath), Row(2, 1, 1))

        Seq((2, 2, 2)).toDF("i", "part1", "part2")
          .write.partitionBy("part1", "part2").mode("overwrite").parquet(path.getAbsolutePath)
        checkAnswer(spark.read.parquet(path.getAbsolutePath), Row(2, 1, 1) :: Row(2, 2, 2) :: Nil)
      }
    }
  }

  test("SPARK-20236: dynamic partition overwrite") {
    withSQLConf(SQLConf.PARTITION_OVERWRITE_MODE.key -> PartitionOverwriteMode.DYNAMIC.toString) {
      withTable("t") {
        sql(
          """
            |create table t(i int, part1 int, part2 int) using parquet
            |partitioned by (part1, part2)
          """.stripMargin)

        sql("insert into t partition(part1=1, part2=1) select 1")
        checkAnswer(spark.table("t"), Row(1, 1, 1))

        sql("insert overwrite table t partition(part1=1, part2=1) select 2")
        checkAnswer(spark.table("t"), Row(2, 1, 1))

        sql("insert overwrite table t partition(part1=2, part2) select 2, 2")
        checkAnswer(spark.table("t"), Row(2, 1, 1) :: Row(2, 2, 2) :: Nil)

        sql("insert overwrite table t partition(part1=1, part2=2) select 3")
        checkAnswer(spark.table("t"), Row(2, 1, 1) :: Row(2, 2, 2) :: Row(3, 1, 2) :: Nil)

        sql("insert overwrite table t partition(part1=1, part2) select 4, 1")
        checkAnswer(spark.table("t"), Row(4, 1, 1) :: Row(2, 2, 2) :: Row(3, 1, 2) :: Nil)
      }
    }
  }

  test("SPARK-20236: dynamic partition overwrite with customer partition path") {
    withSQLConf(SQLConf.PARTITION_OVERWRITE_MODE.key -> PartitionOverwriteMode.DYNAMIC.toString) {
      withTable("t") {
        sql(
          """
            |create table t(i int, part1 int, part2 int) using parquet
            |partitioned by (part1, part2)
          """.stripMargin)

        val path1 = Utils.createTempDir()
        sql(s"alter table t add partition(part1=1, part2=1) location '$path1'")
        sql(s"insert into t partition(part1=1, part2=1) select 1")
        checkAnswer(spark.table("t"), Row(1, 1, 1))

        sql("insert overwrite table t partition(part1=1, part2=1) select 2")
        checkAnswer(spark.table("t"), Row(2, 1, 1))

        sql("insert overwrite table t partition(part1=2, part2) select 2, 2")
        checkAnswer(spark.table("t"), Row(2, 1, 1) :: Row(2, 2, 2) :: Nil)

        val path2 = Utils.createTempDir()
        sql(s"alter table t add partition(part1=1, part2=2) location '$path2'")
        sql("insert overwrite table t partition(part1=1, part2=2) select 3")
        checkAnswer(spark.table("t"), Row(2, 1, 1) :: Row(2, 2, 2) :: Row(3, 1, 2) :: Nil)

        sql("insert overwrite table t partition(part1=1, part2) select 4, 1")
        checkAnswer(spark.table("t"), Row(4, 1, 1) :: Row(2, 2, 2) :: Row(3, 1, 2) :: Nil)
      }
    }
  }

  test("Throw exception on unsafe cast with strict casting policy") {
    withSQLConf(
      SQLConf.USE_V1_SOURCE_LIST.key -> "parquet",
      SQLConf.STORE_ASSIGNMENT_POLICY.key -> SQLConf.StoreAssignmentPolicy.STRICT.toString) {
      withTable("t") {
        sql("create table t(i int, d double) using parquet")
        var msg = intercept[AnalysisException] {
          sql("insert into t select 1L, 2")
        }.getMessage
        assert(msg.contains("Cannot safely cast 'i': bigint to int"))

        msg = intercept[AnalysisException] {
          sql("insert into t select 1, 2.0")
        }.getMessage
        assert(msg.contains("Cannot safely cast 'd': decimal(2,1) to double"))

        msg = intercept[AnalysisException] {
          sql("insert into t select 1, 2.0D, 3")
        }.getMessage
        assert(msg.contains("`t` requires that the data to be inserted have the same number of " +
          "columns as the target table: target table has 2 column(s)" +
          " but the inserted data has 3 column(s)"))

        msg = intercept[AnalysisException] {
          sql("insert into t select 1")
        }.getMessage
        assert(msg.contains("`t` requires that the data to be inserted have the same number of " +
          "columns as the target table: target table has 2 column(s)" +
          " but the inserted data has 1 column(s)"))

        // Insert into table successfully.
        sql("insert into t select 1, 2.0D")
        checkAnswer(sql("select * from t"), Row(1, 2.0D))
      }
    }
  }

  test("Throw exception on unsafe cast with ANSI casting policy") {
    withSQLConf(
      SQLConf.USE_V1_SOURCE_LIST.key -> "parquet",
      SQLConf.STORE_ASSIGNMENT_POLICY.key -> SQLConf.StoreAssignmentPolicy.ANSI.toString) {
      withTable("t") {
        sql("create table t(i int, d double) using parquet")
        var msg = intercept[AnalysisException] {
          sql("insert into t values('a', 'b')")
        }.getMessage
        assert(msg.contains("Cannot safely cast 'i': string to int") &&
          msg.contains("Cannot safely cast 'd': string to double"))
        msg = intercept[AnalysisException] {
          sql("insert into t values(now(), now())")
        }.getMessage
        assert(msg.contains("Cannot safely cast 'i': timestamp to int") &&
          msg.contains("Cannot safely cast 'd': timestamp to double"))
        msg = intercept[AnalysisException] {
          sql("insert into t values(true, false)")
        }.getMessage
        assert(msg.contains("Cannot safely cast 'i': boolean to int") &&
          msg.contains("Cannot safely cast 'd': boolean to double"))
      }
    }
  }

  test("Allow on writing any numeric value to numeric type with ANSI policy") {
    withSQLConf(
      SQLConf.USE_V1_SOURCE_LIST.key -> "parquet",
      SQLConf.STORE_ASSIGNMENT_POLICY.key -> SQLConf.StoreAssignmentPolicy.ANSI.toString) {
      withTable("t") {
        sql("create table t(i int, d float) using parquet")
        sql("insert into t values(1L, 2.0)")
        sql("insert into t values(3.0, 4)")
        sql("insert into t values(5.0, 6L)")
        checkAnswer(sql("select * from t"), Seq(Row(1, 2.0F), Row(3, 4.0F), Row(5, 6.0F)))
      }
    }
  }

  test("Allow on writing timestamp value to date type with ANSI policy") {
    withSQLConf(
      SQLConf.USE_V1_SOURCE_LIST.key -> "parquet",
      SQLConf.STORE_ASSIGNMENT_POLICY.key -> SQLConf.StoreAssignmentPolicy.ANSI.toString) {
      withTable("t") {
        sql("create table t(i date) using parquet")
        sql("insert into t values(TIMESTAMP('2010-09-02 14:10:10'))")
        checkAnswer(sql("select * from t"), Seq(Row(Date.valueOf("2010-09-02"))))
      }
    }
  }

  test("Throw exceptions on inserting out-of-range int value with ANSI casting policy") {
    withSQLConf(
      SQLConf.STORE_ASSIGNMENT_POLICY.key -> SQLConf.StoreAssignmentPolicy.ANSI.toString) {
      withTable("t") {
        sql("create table t(b int) using parquet")
        val outOfRangeValue1 = (Int.MaxValue + 1L).toString
        val expectedMsg = "Fail to insert a value of \"BIGINT\" type into the \"INT\" type column" +
          " `b` due to an overflow."
        var msg = intercept[SparkException] {
          sql(s"insert into t values($outOfRangeValue1)")
        }.getMessage
        assert(msg.contains(expectedMsg))

        val outOfRangeValue2 = (Int.MinValue - 1L).toString
        msg = intercept[SparkException] {
          sql(s"insert into t values($outOfRangeValue2)")
        }.getMessage
        assert(msg.contains(expectedMsg))
      }
    }
  }

  test("Throw exceptions on inserting out-of-range long value with ANSI casting policy") {
    withSQLConf(
      SQLConf.STORE_ASSIGNMENT_POLICY.key -> SQLConf.StoreAssignmentPolicy.ANSI.toString) {
      withTable("t") {
        sql("create table t(b long) using parquet")
        val outOfRangeValue1 = Math.nextUp(Long.MaxValue)
        val expectedMsg = "Fail to insert a value of \"DOUBLE\" type into the \"BIGINT\" type " +
          "column `b` due to an overflow."
        var msg = intercept[SparkException] {
          sql(s"insert into t values(${outOfRangeValue1}D)")
        }.getMessage
        assert(msg.contains(expectedMsg))

        val outOfRangeValue2 = Math.nextDown(Long.MinValue)
        msg = intercept[SparkException] {
          sql(s"insert into t values(${outOfRangeValue2}D)")
        }.getMessage
        assert(msg.contains(expectedMsg))
      }
    }
  }

  test("Throw exceptions on inserting out-of-range decimal value with ANSI casting policy") {
    withSQLConf(
      SQLConf.STORE_ASSIGNMENT_POLICY.key -> SQLConf.StoreAssignmentPolicy.ANSI.toString) {
      withTable("t") {
        sql("create table t(b decimal(3,2)) using parquet")
        val outOfRangeValue = "123.45"
        val expectedMsg = "Fail to insert a value of \"DECIMAL(5,2)\" type into the " +
          "\"DECIMAL(3,2)\" type column `b` due to an overflow."
        val msg = intercept[SparkException] {
          sql(s"insert into t values(${outOfRangeValue})")
        }.getMessage
        assert(msg.contains(expectedMsg))
      }
    }
  }

  test("SPARK-33354: Throw exceptions on inserting invalid cast with ANSI casting policy") {
    withSQLConf(
      SQLConf.STORE_ASSIGNMENT_POLICY.key -> SQLConf.StoreAssignmentPolicy.ANSI.toString) {
      withTable("t") {
        sql("CREATE TABLE t(i int, t timestamp) USING parquet")
        val msg = intercept[AnalysisException] {
          sql("INSERT INTO t VALUES (TIMESTAMP('2010-09-02 14:10:10'), 1)")
        }.getMessage
        assert(msg.contains("Cannot safely cast 'i': timestamp to int"))
        assert(msg.contains("Cannot safely cast 't': int to timestamp"))
      }

      withTable("t") {
        sql("CREATE TABLE t(i int, d date) USING parquet")
        val msg = intercept[AnalysisException] {
          sql("INSERT INTO t VALUES (date('2010-09-02'), 1)")
        }.getMessage
        assert(msg.contains("Cannot safely cast 'i': date to int"))
        assert(msg.contains("Cannot safely cast 'd': int to date"))
      }

      withTable("t") {
        sql("CREATE TABLE t(b boolean, t timestamp) USING parquet")
        val msg = intercept[AnalysisException] {
          sql("INSERT INTO t VALUES (TIMESTAMP('2010-09-02 14:10:10'), true)")
        }.getMessage
        assert(msg.contains("Cannot safely cast 'b': timestamp to boolean"))
        assert(msg.contains("Cannot safely cast 't': boolean to timestamp"))
      }

      withTable("t") {
        sql("CREATE TABLE t(b boolean, d date) USING parquet")
        val msg = intercept[AnalysisException] {
          sql("INSERT INTO t VALUES (date('2010-09-02'), true)")
        }.getMessage
        assert(msg.contains("Cannot safely cast 'b': date to boolean"))
        assert(msg.contains("Cannot safely cast 'd': boolean to date"))
      }
    }
  }

  test("SPARK-24860: dynamic partition overwrite specified per source without catalog table") {
    withTempPath { path =>
      Seq((1, 1), (2, 2)).toDF("i", "part")
        .write.partitionBy("part")
        .parquet(path.getAbsolutePath)
      checkAnswer(spark.read.parquet(path.getAbsolutePath), Row(1, 1) :: Row(2, 2) :: Nil)

      Seq((1, 2), (1, 3)).toDF("i", "part")
        .write.partitionBy("part").mode("overwrite")
        .option(DataSourceUtils.PARTITION_OVERWRITE_MODE, PartitionOverwriteMode.DYNAMIC.toString)
        .parquet(path.getAbsolutePath)
      checkAnswer(spark.read.parquet(path.getAbsolutePath),
        Row(1, 1) :: Row(1, 2) :: Row(1, 3) :: Nil)

      Seq((1, 2), (1, 3)).toDF("i", "part")
        .write.partitionBy("part").mode("overwrite")
        .option(DataSourceUtils.PARTITION_OVERWRITE_MODE, PartitionOverwriteMode.STATIC.toString)
        .parquet(path.getAbsolutePath)
      checkAnswer(spark.read.parquet(path.getAbsolutePath), Row(1, 2) :: Row(1, 3) :: Nil)
    }
  }

  test("SPARK-24583 Wrong schema type in InsertIntoDataSourceCommand") {
    withTable("test_table") {
      val schema = new StructType()
        .add("i", LongType, false)
        .add("s", StringType, false)
      val newTable = CatalogTable(
        identifier = TableIdentifier("test_table", None),
        tableType = CatalogTableType.MANAGED,
        storage = CatalogStorageFormat(
          locationUri = None,
          inputFormat = None,
          outputFormat = None,
          serde = None,
          compressed = false,
          properties = Map.empty),
        schema = schema,
        provider = Some(classOf[SimpleInsertSource].getName))

      spark.sessionState.catalog.createTable(newTable, false)

      sql("INSERT INTO TABLE test_table SELECT 1, 'a'")
      val msg = intercept[SparkException] {
        sql("INSERT INTO TABLE test_table SELECT 2, null")
      }.getCause.getMessage
      assert(msg.contains("Null value appeared in non-nullable field"))
    }
  }

  test("Allow user to insert specified columns into insertable view") {
    withSQLConf(SQLConf.USE_NULLS_FOR_MISSING_DEFAULT_COLUMN_VALUES.key -> "true") {
      sql("INSERT OVERWRITE TABLE jsonTable SELECT a, DEFAULT FROM jt")
      checkAnswer(
        sql("SELECT a, b FROM jsonTable"),
        (1 to 10).map(i => Row(i, null))
      )

      sql("INSERT OVERWRITE TABLE jsonTable(a) SELECT a FROM jt")
      checkAnswer(
        sql("SELECT a, b FROM jsonTable"),
        (1 to 10).map(i => Row(i, null))
      )

      sql("INSERT OVERWRITE TABLE jsonTable(b) SELECT b FROM jt")
      checkAnswer(
        sql("SELECT a, b FROM jsonTable"),
        (1 to 10).map(i => Row(null, s"str$i"))
      )
    }

    val message = intercept[AnalysisException] {
      sql("INSERT OVERWRITE TABLE jsonTable SELECT a FROM jt")
    }.getMessage
    assert(message.contains("target table has 2 column(s) but the inserted data has 1 column(s)"))
  }

  test("SPARK-38336 INSERT INTO statements with tables with default columns: positive tests") {
    // When the USE_NULLS_FOR_MISSING_DEFAULT_COLUMN_VALUES configuration is enabled, and no
    // explicit DEFAULT value is available when the INSERT INTO statement provides fewer
    // values than expected, NULL values are appended in their place.
    withSQLConf(SQLConf.USE_NULLS_FOR_MISSING_DEFAULT_COLUMN_VALUES.key -> "true") {
      withTable("t") {
        sql("create table t(i boolean, s bigint) using parquet")
        sql("insert into t(i) values(true)")
        checkAnswer(spark.table("t"), Row(true, null))
      }
    }
    // The default value for the DEFAULT keyword is the NULL literal.
    withTable("t") {
      sql("create table t(i boolean, s bigint) using parquet")
      sql("insert into t values(true, default)")
      checkAnswer(spark.table("t"), Row(true, null))
    }
    // There is a complex expression in the default value.
    withTable("t") {
      sql("create table t(i boolean, s string default concat('abc', 'def')) using parquet")
      sql("insert into t values(true, default)")
      checkAnswer(spark.table("t"), Row(true, "abcdef"))
    }
    // The default value parses correctly and the provided value type is different but coercible.
    withTable("t") {
      sql("create table t(i boolean, s bigint default 42) using parquet")
      sql("insert into t(i) values(false)")
      checkAnswer(spark.table("t"), Row(false, 42L))
    }
    // There are two trailing default values referenced implicitly by the INSERT INTO statement.
    withTable("t") {
      sql("create table t(i int, s bigint default 42, x bigint default 43) using parquet")
      sql("insert into t(i) values(1)")
      checkAnswer(sql("select s + x from t where i = 1"), Seq(85L).map(i => Row(i)))
    }
    // The table has a partitioning column and a default value is injected.
    withTable("t") {
      sql("create table t(i boolean, s bigint, q int default 42) using parquet partitioned by (i)")
      sql("insert into t partition(i='true') values(5, default)")
      checkAnswer(spark.table("t"), Row(5, 42, true))
    }
    // The table has a partitioning column and a default value is added per an explicit reference.
    withTable("t") {
      sql("create table t(i boolean, s bigint default 42) using parquet partitioned by (i)")
      sql("insert into t partition(i='true') (s) values(default)")
      checkAnswer(spark.table("t"), Row(42L, true))
    }
    // The default value parses correctly as a constant but non-literal expression.
    withTable("t") {
      sql("create table t(i boolean, s bigint default 41 + 1) using parquet")
      sql("insert into t values(false, default)")
      checkAnswer(spark.table("t"), Row(false, 42L))
    }
    // Explicit defaults may appear in different positions within the inline table provided as input
    // to the INSERT INTO statement.
    withTable("t") {
      sql("create table t(i boolean default false, s bigint default 42) using parquet")
      sql("insert into t(i, s) values(false, default), (default, 42)")
      checkAnswer(spark.table("t"), Seq(Row(false, 42L), Row(false, 42L)))
    }
    // There is an explicit default value provided in the INSERT INTO statement in the VALUES,
    // with an alias over the VALUES.
    withTable("t") {
      sql("create table t(i boolean, s bigint default 42) using parquet")
      sql("insert into t select * from values (false, default) as tab(col, other)")
      checkAnswer(spark.table("t"), Row(false, 42L))
    }
    // The explicit default value arrives first before the other value.
    withTable("t") {
      sql("create table t(i boolean default false, s bigint) using parquet")
      sql("insert into t values (default, 43)")
      checkAnswer(spark.table("t"), Row(false, 43L))
    }
    // The 'create table' statement provides the default parameter first.
    withTable("t") {
      sql("create table t(i boolean default false, s bigint) using parquet")
      sql("insert into t values (default, 43)")
      checkAnswer(spark.table("t"), Row(false, 43L))
    }
    // The explicit default value is provided in the wrong order (first instead of second), but
    // this is OK because the provided default value evaluates to literal NULL.
    withTable("t") {
      sql("create table t(i boolean, s bigint default 42) using parquet")
      sql("insert into t values (default, 43)")
      checkAnswer(spark.table("t"), Row(null, 43L))
    }
    // There is an explicit default value provided in the INSERT INTO statement as a SELECT.
    // This is supported.
    withTable("t") {
      sql("create table t(i boolean, s bigint default 42) using parquet")
      sql("insert into t select false, default")
      checkAnswer(spark.table("t"), Row(false, 42L))
    }
    // There is a complex query plan in the SELECT query in the INSERT INTO statement.
    withTable("t") {
      sql("create table t(i boolean default false, s bigint default 42) using parquet")
      sql("insert into t select col, count(*) from values (default, default) " +
        "as tab(col, other) group by 1")
      checkAnswer(spark.table("t"), Row(false, 1))
    }
    // The explicit default reference resolves successfully with nested table subqueries.
    withTable("t") {
      sql("create table t(i boolean default false, s bigint) using parquet")
      sql("insert into t select * from (select * from values(default, 42))")
      checkAnswer(spark.table("t"), Row(false, 42L))
    }
    // There are three column types exercising various combinations of implicit and explicit
    // default column value references in the 'insert into' statements. Note these tests depend on
    // enabling the configuration to use NULLs for missing DEFAULT column values.
    withSQLConf(SQLConf.USE_NULLS_FOR_MISSING_DEFAULT_COLUMN_VALUES.key -> "true") {
      for (useDataFrames <- Seq(false, true)) {
        withTable("t1", "t2") {
          sql("create table t1(j int, s bigint default 42, x bigint default 43) using parquet")
          if (useDataFrames) {
            Seq((1, 42, 43)).toDF.write.insertInto("t1")
            Seq((2, 42, 43)).toDF.write.insertInto("t1")
            Seq((3, 42, 43)).toDF.write.insertInto("t1")
            Seq((4, 44, 43)).toDF.write.insertInto("t1")
            Seq((5, 44, 43)).toDF.write.insertInto("t1")
          } else {
            sql("insert into t1(j) values(1)")
            sql("insert into t1(j, s) values(2, default)")
            sql("insert into t1(j, s, x) values(3, default, default)")
            sql("insert into t1(j, s) values(4, 44)")
            sql("insert into t1(j, s, x) values(5, 44, 45)")
          }
          sql("create table t2(j int, s bigint default 42, x bigint default 43) using parquet")
          if (useDataFrames) {
            spark.table("t1").where("j = 1").write.insertInto("t2")
            spark.table("t1").where("j = 2").write.insertInto("t2")
            spark.table("t1").where("j = 3").write.insertInto("t2")
            spark.table("t1").where("j = 4").write.insertInto("t2")
            spark.table("t1").where("j = 5").write.insertInto("t2")
          } else {
            sql("insert into t2(j) select j from t1 where j = 1")
            sql("insert into t2(j, s) select j, default from t1 where j = 2")
            sql("insert into t2(j, s, x) select j, default, default from t1 where j = 3")
            sql("insert into t2(j, s) select j, s from t1 where j = 4")
            sql("insert into t2(j, s, x) select j, s, default from t1 where j = 5")
          }
          checkAnswer(
            spark.table("t2"),
            Row(1, 42L, 43L) ::
            Row(2, 42L, 43L) ::
            Row(3, 42L, 43L) ::
            Row(4, 44L, 43L) ::
            Row(5, 44L, 43L) :: Nil)
        }
      }
    }
  }

  test("SPARK-38336 INSERT INTO statements with tables with default columns: negative tests") {
    object Errors {
      val COMMON_SUBSTRING = " has a DEFAULT value"
      val COLUMN_DEFAULT_NOT_FOUND = "`default` cannot be resolved."
      val BAD_SUBQUERY = "subquery expressions are not allowed in DEFAULT values"
    }
    // The default value fails to analyze.
    withTable("t") {
      assert(intercept[AnalysisException] {
        sql("create table t(i boolean, s bigint default badvalue) using parquet")
      }.getMessage.contains(Errors.COMMON_SUBSTRING))
    }
    // The default value analyzes to a table not in the catalog.
    withTable("t") {
      assert(intercept[AnalysisException] {
        sql("create table t(i boolean, s bigint default (select min(x) from badtable)) " +
          "using parquet")
      }.getMessage.contains(Errors.BAD_SUBQUERY))
    }
    // The default value parses but refers to a table from the catalog.
    withTable("t", "other") {
      sql("create table other(x string) using parquet")
      assert(intercept[AnalysisException] {
        sql("create table t(i boolean, s bigint default (select min(x) from other)) using parquet")
      }.getMessage.contains(Errors.BAD_SUBQUERY))
    }
    // The default value has an explicit alias. It fails to evaluate when inlined into the VALUES
    // list at the INSERT INTO time.
    withTable("t") {
      assert(intercept[AnalysisException] {
        sql("create table t(i boolean default (select false as alias), s bigint) using parquet")
      }.getMessage.contains(Errors.BAD_SUBQUERY))
    }
    // Explicit default values may not participate in complex expressions in the VALUES list.
    withTable("t") {
      sql("create table t(i boolean, s bigint default 42) using parquet")
      assert(intercept[AnalysisException] {
        sql("insert into t values(false, default + 1)")
      }.getMessage.contains(
        QueryCompilationErrors.defaultReferencesNotAllowedInComplexExpressionsInInsertValuesList()
          .getMessage))
    }
    // Explicit default values may not participate in complex expressions in the SELECT query.
    withTable("t") {
      sql("create table t(i boolean, s bigint default 42) using parquet")
      assert(intercept[AnalysisException] {
        sql("insert into t select false, default + 1")
      }.getMessage.contains(
        QueryCompilationErrors.defaultReferencesNotAllowedInComplexExpressionsInInsertValuesList()
          .getMessage))
    }
    // Explicit default values have a reasonable error path if the table is not found.
    withTable("t") {
      assert(intercept[AnalysisException] {
        sql("insert into t values(false, default)")
      }.getMessage.contains(Errors.COLUMN_DEFAULT_NOT_FOUND))
    }
    // The default value parses but the type is not coercible.
    withTable("t") {
      assert(intercept[AnalysisException] {
        sql("create table t(i boolean, s bigint default false) using parquet")
      }.getMessage.contains(Errors.COMMON_SUBSTRING))
    }
    // The number of columns in the INSERT INTO statement is greater than the number of columns in
    // the table.
    withTable("t") {
      sql("create table num_data(id int, val decimal(38,10)) using parquet")
      sql("create table t(id1 int, int2 int, result decimal(38,10)) using parquet")
      assert(intercept[AnalysisException] {
        sql("insert into t select t1.id, t2.id, t1.val, t2.val, t1.val * t2.val " +
          "from num_data t1, num_data t2")
      }.getMessage.contains(
        "requires that the data to be inserted have the same number of columns as the target"))
    }
    // The default value is disabled per configuration.
    withTable("t") {
      withSQLConf(SQLConf.ENABLE_DEFAULT_COLUMNS.key -> "false") {
        assert(intercept[AnalysisException] {
          sql("create table t(i boolean, s bigint default 42L) using parquet")
        }.getMessage.contains("Support for DEFAULT column values is not allowed"))
      }
    }
    // There is one trailing default value referenced implicitly by the INSERT INTO statement.
    withTable("t") {
      sql("create table t(i int, s bigint default 42, x bigint) using parquet")
      assert(intercept[AnalysisException] {
        sql("insert into t values(1)")
      }.getMessage.contains("target table has 3 column(s) but the inserted data has 1 column(s)"))
    }
    // The table has a partitioning column with a default value; this is not allowed.
    withTable("t") {
      sql("create table t(i boolean default true, s bigint, q int default 42) " +
        "using parquet partitioned by (i)")
      checkError(
        exception = intercept[ParseException] {
          sql("insert into t partition(i=default) values(5, default)")
        },
        errorClass = "_LEGACY_ERROR_TEMP_0059",
        parameters = Map.empty,
        context = ExpectedContext(
          fragment = "partition(i=default)",
          start = 14,
          stop = 33))
    }
    // The configuration option to append missing NULL values to the end of the INSERT INTO
    // statement is not enabled.
    withSQLConf(SQLConf.USE_NULLS_FOR_MISSING_DEFAULT_COLUMN_VALUES.key -> "false") {
      withTable("t") {
        sql("create table t(i boolean, s bigint) using parquet")
        assert(intercept[AnalysisException] {
          sql("insert into t values(true)")
        }.getMessage.contains("target table has 2 column(s) but the inserted data has 1 column(s)"))
      }
    }
  }

  test("SPARK-38795 INSERT INTO with user specified columns and defaults: positive tests") {
    Seq(
      "insert into t (i, s) values (true, default)",
      "insert into t (s, i) values (default, true)",
      "insert into t (i) values (true)",
      "insert into t (i) values (default)",
      "insert into t (s) values (default)",
      "insert into t (s) select default from (select 1)",
      "insert into t (i) select true from (select 1)"
    ).foreach { insert =>
      withTable("t") {
        sql("create table t(i boolean default true, s bigint default 42) using parquet")
        sql(insert)
        checkAnswer(spark.table("t"), Row(true, 42L))
      }
    }
    // The table is partitioned and we insert default values with explicit column names.
    withTable("t") {
      sql("create table t(i boolean, s bigint default 4, q int default 42) using parquet " +
        "partitioned by (i)")
      sql("insert into t partition(i='true') (s) values(5)")
      sql("insert into t partition(i='false') (q) select 43")
      sql("insert into t partition(i='false') (q) select default")
      checkAnswer(spark.table("t"),
        Seq(Row(5, 42, true),
            Row(4, 43, false),
            Row(4, 42, false)))
    }
    // When the USE_NULLS_FOR_MISSING_DEFAULT_COLUMN_VALUES configuration is enabled, and no
    // explicit DEFAULT value is available when the INSERT INTO statement provides fewer
    // values than expected, NULL values are appended in their place.
    withSQLConf(SQLConf.USE_NULLS_FOR_MISSING_DEFAULT_COLUMN_VALUES.key -> "true") {
      withTable("t") {
        sql("create table t(i boolean, s bigint) using parquet")
        sql("insert into t (i) values (true)")
        checkAnswer(spark.table("t"), Row(true, null))
      }
      withTable("t") {
        sql("create table t(i boolean default true, s bigint) using parquet")
        sql("insert into t (i) values (default)")
        checkAnswer(spark.table("t"), Row(true, null))
      }
      withTable("t") {
        sql("create table t(i boolean, s bigint default 42) using parquet")
        sql("insert into t (s) values (default)")
        checkAnswer(spark.table("t"), Row(null, 42L))
      }
      withTable("t") {
        sql("create table t(i boolean, s bigint, q int) using parquet partitioned by (i)")
        sql("insert into t partition(i='true') (s) values(5)")
        sql("insert into t partition(i='false') (q) select 43")
        sql("insert into t partition(i='false') (q) select default")
        checkAnswer(spark.table("t"),
          Seq(Row(5, null, true),
            Row(null, 43, false),
            Row(null, null, false)))
      }
    }
  }

  test("SPARK- 38795 INSERT INTO with user specified columns and defaults: negative tests") {
    val addOneColButExpectedTwo = "target table has 2 column(s) but the inserted data has 1 col"
    val addTwoColButExpectedThree = "target table has 3 column(s) but the inserted data has 2 col"
    // The missing columns in these INSERT INTO commands do not have explicit default values.
    withTable("t") {
      sql("create table t(i boolean, s bigint, q int default 43) using parquet")
      assert(intercept[AnalysisException] {
        sql("insert into t (i, q) select true from (select 1)")
      }.getMessage.contains("Cannot write to table due to mismatched user specified column " +
        "size(3) and data column size(2)"))
    }
    // When the USE_NULLS_FOR_MISSING_DEFAULT_COLUMN_VALUES configuration is disabled, and no
    // explicit DEFAULT value is available when the INSERT INTO statement provides fewer
    // values than expected, the INSERT INTO command fails to execute.
    withSQLConf(SQLConf.USE_NULLS_FOR_MISSING_DEFAULT_COLUMN_VALUES.key -> "false") {
      withTable("t") {
        sql("create table t(i boolean, s bigint) using parquet")
        assert(intercept[AnalysisException] {
          sql("insert into t (i) values (true)")
        }.getMessage.contains(addOneColButExpectedTwo))
      }
      withTable("t") {
        sql("create table t(i boolean default true, s bigint) using parquet")
        assert(intercept[AnalysisException] {
          sql("insert into t (i) values (default)")
        }.getMessage.contains(addOneColButExpectedTwo))
      }
      withTable("t") {
        sql("create table t(i boolean, s bigint default 42) using parquet")
        assert(intercept[AnalysisException] {
          sql("insert into t (s) values (default)")
        }.getMessage.contains(addOneColButExpectedTwo))
      }
      withTable("t") {
        sql("create table t(i boolean, s bigint, q int) using parquet partitioned by (i)")
        assert(intercept[AnalysisException] {
          sql("insert into t partition(i='true') (s) values(5)")
        }.getMessage.contains(addTwoColButExpectedThree))
      }
      withTable("t") {
        sql("create table t(i boolean, s bigint, q int) using parquet partitioned by (i)")
        assert(intercept[AnalysisException] {
          sql("insert into t partition(i='false') (q) select 43")
        }.getMessage.contains(addTwoColButExpectedThree))
      }
      withTable("t") {
        sql("create table t(i boolean, s bigint, q int) using parquet partitioned by (i)")
        assert(intercept[AnalysisException] {
          sql("insert into t partition(i='false') (q) select default")
        }.getMessage.contains(addTwoColButExpectedThree))
      }
    }
    // When the CASE_SENSITIVE configuration is enabled, then using different cases for the required
    // and provided column names results in an analysis error.
    withSQLConf(SQLConf.CASE_SENSITIVE.key -> "true") {
      withTable("t") {
        sql("create table t(i boolean default true, s bigint default 42) using parquet")
        checkError(
          exception =
            intercept[AnalysisException](sql("insert into t (I) select true from (select 1)")),
          errorClass = "UNRESOLVED_COLUMN.WITH_SUGGESTION",
          sqlState = None,
          parameters = Map("objectName" -> "`I`", "proposal" -> "`i`, `s`"),
          context = ExpectedContext(
            fragment = "insert into t (I)", start = 0, stop = 16))
      }
    }
  }

  test("SPARK-38811 INSERT INTO on columns added with ALTER TABLE ADD COLUMNS: Positive tests") {
    // There is a complex expression in the default value.
    val createTableBooleanCol = "create table t(i boolean) using parquet"
    val createTableIntCol = "create table t(i int) using parquet"
    withTable("t") {
      sql(createTableBooleanCol)
      sql("alter table t add column s string default concat('abc', 'def')")
      sql("insert into t values(true, default)")
      checkAnswer(spark.table("t"), Row(true, "abcdef"))
    }
    // There are two trailing default values referenced implicitly by the INSERT INTO statement.
    withTable("t") {
      sql(createTableIntCol)
      sql("alter table t add column s bigint default 42")
      sql("alter table t add column x bigint default 43")
      sql("insert into t(i) values(1)")
      checkAnswer(spark.table("t"), Row(1, 42, 43))
    }
    // There are two trailing default values referenced implicitly by the INSERT INTO statement.
    withTable("t") {
      sql(createTableIntCol)
      sql("alter table t add columns s bigint default 42, x bigint default 43")
      sql("insert into t(i) values(1)")
      checkAnswer(spark.table("t"), Row(1, 42, 43))
    }
    // The table has a partitioning column and a default value is injected.
    withTable("t") {
      sql("create table t(i boolean, s bigint) using parquet partitioned by (i)")
      sql("alter table t add column q int default 42")
      sql("insert into t partition(i='true') values(5, default)")
      checkAnswer(spark.table("t"), Row(5, 42, true))
    }
    // The default value parses correctly as a constant but non-literal expression.
    withTable("t") {
      sql(createTableBooleanCol)
      sql("alter table t add column s bigint default 41 + 1")
      sql("insert into t(i) values(default)")
      checkAnswer(spark.table("t"), Row(null, 42))
    }
    // Explicit defaults may appear in different positions within the inline table provided as input
    // to the INSERT INTO statement.
    withTable("t") {
      sql("create table t(i boolean default false) using parquet")
      sql("alter table t add column s bigint default 42")
      sql("insert into t values(false, default), (default, 42)")
      checkAnswer(spark.table("t"), Seq(Row(false, 42), Row(false, 42)))
    }
    // There is an explicit default value provided in the INSERT INTO statement in the VALUES,
    // with an alias over the VALUES.
    withTable("t") {
      sql(createTableBooleanCol)
      sql("alter table t add column s bigint default 42")
      sql("insert into t select * from values (false, default) as tab(col, other)")
      checkAnswer(spark.table("t"), Row(false, 42))
    }
    // The explicit default value is provided in the wrong order (first instead of second), but
    // this is OK because the provided default value evaluates to literal NULL.
    withTable("t") {
      sql(createTableBooleanCol)
      sql("alter table t add column s bigint default 42")
      sql("insert into t values (default, 43)")
      checkAnswer(spark.table("t"), Row(null, 43))
    }
    // There is an explicit default value provided in the INSERT INTO statement as a SELECT.
    // This is supported.
    withTable("t") {
      sql(createTableBooleanCol)
      sql("alter table t add column s bigint default 42")
      sql("insert into t select false, default")
      checkAnswer(spark.table("t"), Row(false, 42))
    }
    // There is a complex query plan in the SELECT query in the INSERT INTO statement.
    withTable("t") {
      sql("create table t(i boolean default false) using parquet")
      sql("alter table t add column s bigint default 42")
      sql("insert into t select col, count(*) from values (default, default) " +
        "as tab(col, other) group by 1")
      checkAnswer(spark.table("t"), Row(false, 1))
    }
    // There are three column types exercising various combinations of implicit and explicit
    // default column value references in the 'insert into' statements. Note these tests depend on
    // enabling the configuration to use NULLs for missing DEFAULT column values.
    withSQLConf(SQLConf.USE_NULLS_FOR_MISSING_DEFAULT_COLUMN_VALUES.key -> "true") {
      withTable("t1", "t2") {
        sql("create table t1(j int) using parquet")
        sql("alter table t1 add column s bigint default 42")
        sql("alter table t1 add column x bigint default 43")
        sql("insert into t1(j) values(1)")
        sql("insert into t1(j, s) values(2, default)")
        sql("insert into t1(j, s, x) values(3, default, default)")
        sql("insert into t1(j, s) values(4, 44)")
        sql("insert into t1(j, s, x) values(5, 44, 45)")
        sql("create table t2(j int) using parquet")
        sql("alter table t2 add columns s bigint default 42, x bigint default 43")
        sql("insert into t2(j) select j from t1 where j = 1")
        sql("insert into t2(j, s) select j, default from t1 where j = 2")
        sql("insert into t2(j, s, x) select j, default, default from t1 where j = 3")
        sql("insert into t2(j, s) select j, s from t1 where j = 4")
        sql("insert into t2(j, s, x) select j, s, default from t1 where j = 5")
        checkAnswer(
          spark.table("t2"),
          Row(1, 42L, 43L) ::
          Row(2, 42L, 43L) ::
          Row(3, 42L, 43L) ::
          Row(4, 44L, 43L) ::
          Row(5, 44L, 43L) :: Nil)
      }
    }
  }

  test("SPARK-38811 INSERT INTO on columns added with ALTER TABLE ADD COLUMNS: Negative tests") {
    object Errors {
      val COMMON_SUBSTRING = " has a DEFAULT value"
      val BAD_SUBQUERY = "subquery expressions are not allowed in DEFAULT values"
    }
    // The default value fails to analyze.
    withTable("t") {
      sql("create table t(i boolean) using parquet")
      assert(intercept[AnalysisException] {
        sql("alter table t add column s bigint default badvalue")
      }.getMessage.contains(Errors.COMMON_SUBSTRING))
    }
    // The default value analyzes to a table not in the catalog.
    withTable("t") {
      sql("create table t(i boolean) using parquet")
      assert(intercept[AnalysisException] {
        sql("alter table t add column s bigint default (select min(x) from badtable)")
      }.getMessage.contains(Errors.BAD_SUBQUERY))
    }
    // The default value parses but refers to a table from the catalog.
    withTable("t", "other") {
      sql("create table other(x string) using parquet")
      sql("create table t(i boolean) using parquet")
      assert(intercept[AnalysisException] {
        sql("alter table t add column s bigint default (select min(x) from other)")
      }.getMessage.contains(Errors.BAD_SUBQUERY))
    }
    // The default value parses but the type is not coercible.
    withTable("t") {
      sql("create table t(i boolean) using parquet")
      assert(intercept[AnalysisException] {
        sql("alter table t add column s bigint default false")
      }.getMessage.contains("provided a value of incompatible type"))
    }
    // The default value is disabled per configuration.
    withTable("t") {
      withSQLConf(SQLConf.ENABLE_DEFAULT_COLUMNS.key -> "false") {
        sql("create table t(i boolean) using parquet")
        assert(intercept[AnalysisException] {
          sql("alter table t add column s bigint default 42L")
        }.getMessage.contains("Support for DEFAULT column values is not allowed"))
      }
    }
    // There is one trailing default value referenced implicitly by the INSERT INTO statement.
    withTable("t") {
      sql("create table t(i int) using parquet")
      sql("alter table t add column s bigint default 42")
      sql("alter table t add column x bigint")
      assert(intercept[AnalysisException] {
        sql("insert into t values(1)")
      }.getMessage.contains("target table has 3 column(s) but the inserted data has 1 column(s)"))
    }
  }

  test("SPARK-38838 INSERT INTO with defaults set by ALTER TABLE ALTER COLUMN: positive tests") {
    withTable("t") {
      sql("create table t(i boolean, s string, k bigint) using parquet")
      // The default value for the DEFAULT keyword is the NULL literal.
      sql("insert into t values(true, default, default)")
      // There is a complex expression in the default value.
      sql("alter table t alter column s set default concat('abc', 'def')")
      sql("insert into t values(true, default, default)")
      // The default value parses correctly and the provided value type is different but coercible.
      sql("alter table t alter column k set default 42")
      sql("insert into t values(true, default, default)")
      // After dropping the default, inserting more values should add NULLs.
      sql("alter table t alter column k drop default")
      sql("insert into t values(true, default, default)")
      checkAnswer(spark.table("t"),
        Seq(
          Row(true, null, null),
          Row(true, "abcdef", null),
          Row(true, "abcdef", 42),
          Row(true, "abcdef", null)
        ))
    }
  }

  test("SPARK-38838 INSERT INTO with defaults set by ALTER TABLE ALTER COLUMN: negative tests") {
    object Errors {
      val COMMON_SUBSTRING = " has a DEFAULT value"
      val BAD_SUBQUERY = "subquery expressions are not allowed in DEFAULT values"
    }
    val createTable = "create table t(i boolean, s bigint) using parquet"
    withTable("t") {
      sql(createTable)
      // The default value fails to analyze.
      assert(intercept[AnalysisException] {
        sql("alter table t alter column s set default badvalue")
      }.getMessage.contains(Errors.COMMON_SUBSTRING))
      // The default value analyzes to a table not in the catalog.
      assert(intercept[AnalysisException] {
        sql("alter table t alter column s set default (select min(x) from badtable)")
      }.getMessage.contains(Errors.BAD_SUBQUERY))
      // The default value has an explicit alias. It fails to evaluate when inlined into the VALUES
      // list at the INSERT INTO time.
      assert(intercept[AnalysisException] {
        sql("alter table t alter column s set default (select 42 as alias)")
      }.getMessage.contains(Errors.BAD_SUBQUERY))
      // The default value parses but the type is not coercible.
      assert(intercept[AnalysisException] {
        sql("alter table t alter column s set default false")
      }.getMessage.contains("provided a value of incompatible type"))
      // The default value is disabled per configuration.
      withSQLConf(SQLConf.ENABLE_DEFAULT_COLUMNS.key -> "false") {
        val sqlText = "alter table t alter column s set default 41 + 1"
        checkError(
          exception = intercept[ParseException] {
            sql(sqlText)
          },
          errorClass = "_LEGACY_ERROR_TEMP_0058",
          parameters = Map.empty,
          context = ExpectedContext(
            fragment = sqlText,
            start = 0,
            stop = 46))
      }
    }
    // Attempting to set a default value for a partitioning column is not allowed.
    withTable("t") {
      sql("create table t(i boolean, s bigint, q int default 42) using parquet partitioned by (i)")
      assert(intercept[AnalysisException] {
        sql("alter table t alter column i set default false")
      }.getMessage.contains("Can't find column `i` given table data columns [`s`, `q`]"))
    }
  }

  test("INSERT rows, ALTER TABLE ADD COLUMNS with DEFAULTs, then SELECT them") {
    case class Config(
        sqlConf: Option[(String, String)],
        useDataFrames: Boolean = false)
    def runTest(dataSource: String, config: Config): Unit = {
      def insertIntoT(): Unit = {
        sql("insert into t(a, i) values('xyz', 42)")
      }
      def withTableT(f: => Unit): Unit = {
        sql(s"create table t(a string, i int) using $dataSource")
        insertIntoT
        withTable("t") { f }
      }
      // Positive tests:
      // Adding a column with a valid default value into a table containing existing data works
      // successfully. Querying data from the altered table returns the new value.
      withTableT {
        sql("alter table t add column (s string default concat('abc', 'def'))")
        checkAnswer(spark.table("t"), Row("xyz", 42, "abcdef"))
        checkAnswer(sql("select i, s from t"), Row(42, "abcdef"))
        // Now alter the column to change the default value. This still returns the previous value,
        // not the new value, since the behavior semantics are the same as if the first command had
        // performed a backfill of the new default value in the existing rows.
        sql("alter table t alter column s set default concat('ghi', 'jkl')")
        checkAnswer(sql("select i, s from t"), Row(42, "abcdef"))
      }
      // Adding a column with a default value and then inserting explicit NULL values works.
      // Querying data back from the table differentiates between the explicit NULL values and
      // default values.
      withTableT {
        sql("alter table t add column (s string default concat('abc', 'def'))")
        if (config.useDataFrames) {
          Seq((null, null, null)).toDF.write.insertInto("t")
        } else {
          sql("insert into t values(null, null, null)")
        }
        sql("alter table t add column (x boolean default true)")
        val insertedSColumn = null
        checkAnswer(spark.table("t"),
          Seq(
            Row("xyz", 42, "abcdef", true),
            Row(null, null, insertedSColumn, true)))
        checkAnswer(sql("select i, s, x from t"),
          Seq(
            Row(42, "abcdef", true),
            Row(null, insertedSColumn, true)))
      }
      // Adding two columns where only the first has a valid default value works successfully.
      // Querying data from the altered table returns the default value as well as NULL for the
      // second column.
      withTableT {
        sql("alter table t add column (s string default concat('abc', 'def'))")
        sql("alter table t add column (x string)")
        checkAnswer(spark.table("t"), Row("xyz", 42, "abcdef", null))
        checkAnswer(sql("select i, s, x from t"), Row(42, "abcdef", null))
      }
      // Test other supported data types.
      withTableT {
        sql("alter table t add columns (" +
          "s boolean default true, " +
          "t byte default cast(null as byte), " +
          "u short default cast(42 as short), " +
          "v float default 0, " +
          "w double default 0, " +
          "x date default cast('2021-01-02' as date), " +
          "y timestamp default cast('2021-01-02 01:01:01' as timestamp), " +
          "z timestamp_ntz default cast('2021-01-02 01:01:01' as timestamp_ntz), " +
          "a1 timestamp_ltz default cast('2021-01-02 01:01:01' as timestamp_ltz), " +
          "a2 decimal(5, 2) default 123.45," +
          "a3 bigint default 43," +
          "a4 smallint default cast(5 as smallint)," +
          "a5 tinyint default cast(6 as tinyint))")
        insertIntoT()
        // Manually inspect the result row values rather than using the 'checkAnswer' helper method
        // in order to ensure the values' correctness while avoiding minor type incompatibilities.
        val result: Array[Row] =
          sql("select s, t, u, v, w, x, y, z, a1, a2, a3, a4, a5 from t").collect()
        for (row <- result) {
          assert(row.length == 13)
          assert(row(0) == true)
          assert(row(1) == null)
          assert(row(2) == 42)
          assert(row(3) == 0.0f)
          assert(row(4) == 0.0d)
          assert(row(5).toString == "2021-01-02")
          assert(row(6).toString == "2021-01-02 01:01:01.0")
          assert(row(7).toString.startsWith("2021-01-02"))
          assert(row(8).toString == "2021-01-02 01:01:01.0")
          assert(row(9).toString == "123.45")
          assert(row(10) == 43L)
          assert(row(11) == 5)
          assert(row(12) == 6)
        }
      }
    }

    // This represents one test configuration over a data source.
    case class TestCase(
        dataSource: String,
        configs: Seq[Config])
    // Run the test several times using each configuration.
    Seq(
      TestCase(
        dataSource = "csv",
        Seq(
          Config(
            None),
          Config(
            Some(SQLConf.CSV_PARSER_COLUMN_PRUNING.key -> "false")))),
      TestCase(
        dataSource = "json",
        Seq(
          Config(
            None),
          Config(
            Some(SQLConf.JSON_GENERATOR_IGNORE_NULL_FIELDS.key -> "false")))),
      TestCase(
        dataSource = "orc",
        Seq(
          Config(
            None),
          Config(
            Some(SQLConf.ORC_VECTORIZED_READER_ENABLED.key -> "false")))),
      TestCase(
        dataSource = "parquet",
        Seq(
          Config(
            None),
          Config(
            Some(SQLConf.PARQUET_VECTORIZED_READER_ENABLED.key -> "false"))))
    ).foreach { testCase: TestCase =>
      testCase.configs.foreach { config: Config =>
        // Run the test twice, once using SQL for the INSERT operations and again using DataFrames.
        for (useDataFrames <- Seq(false, true)) {
          config.sqlConf.map { kv: (String, String) =>
            withSQLConf(kv) {
              // Run the test with the pair of custom SQLConf values.
              runTest(testCase.dataSource, config.copy(useDataFrames = useDataFrames))
            }
          }.getOrElse {
            // Run the test with default settings.
            runTest(testCase.dataSource, config.copy(useDataFrames = useDataFrames))
          }
        }
      }
    }
  }

  test("SPARK-39985 Enable implicit DEFAULT column values in inserts from DataFrames") {
    // Negative test: explicit column "default" references are not supported in write operations
    // from DataFrames: since the operators are resolved one-by-one, any .select referring to
    // "default" generates a "column not found" error before any following .insertInto.
    withTable("t") {
      sql(s"create table t(a string, i int default 42) using parquet")
      assert(intercept[AnalysisException] {
        Seq(("xyz")).toDF.select("value", "default").write.insertInto("t")
      }.getMessage.contains("column or function parameter with name `default` cannot be resolved"))
    }
  }

  test("SPARK-40001 JSON DEFAULT columns = JSON_GENERATOR_WRITE_NULL_IF_WITH_DEFAULT_VALUE off") {
    val error = "DEFAULT values are not supported for JSON tables"
    // Check that the JSON_GENERATOR_WRITE_NULL_IF_WITH_DEFAULT_VALUE config overrides the
    // JSON_GENERATOR_IGNORE_NULL_FIELDS config.
    withSQLConf(SQLConf.JSON_GENERATOR_WRITE_NULL_IF_WITH_DEFAULT_VALUE.key -> "true",
      SQLConf.JSON_GENERATOR_IGNORE_NULL_FIELDS.key -> "true") {
      withTable("t") {
        sql("create table t (a int default 42) using json")
        sql("insert into t values (null)")
        checkAnswer(spark.table("t"), Row(null))
      }
    }
    withSQLConf(SQLConf.JSON_GENERATOR_WRITE_NULL_IF_WITH_DEFAULT_VALUE.key -> "false",
      SQLConf.JSON_GENERATOR_IGNORE_NULL_FIELDS.key -> "true") {
      withTable("t") {
        sql("create table t (a int default 42) using json")
        sql("insert into t values (null)")
        checkAnswer(spark.table("t"), Row(42))
      }
    }
  }

  test("SPARK-39359 Restrict DEFAULT columns to allowlist of supported data source types") {
    withSQLConf(SQLConf.DEFAULT_COLUMN_ALLOWED_PROVIDERS.key -> "csv,json,orc") {
      val unsupported = "DEFAULT values are not supported for target data source"
      assert(intercept[AnalysisException] {
        sql(s"create table t(a string default 'abc') using parquet")
      }.getMessage.contains(unsupported))
      withTable("t") {
        sql(s"create table t(a string, b int) using parquet")
        assert(intercept[AnalysisException] {
          sql("alter table t add column s bigint default 42")
        }.getMessage.contains(unsupported))
      }
    }
  }

  test("SPARK-39557 INSERT INTO statements with tables with array defaults") {
    // Positive tests: array types are supported as default values.
    case class Config(
        dataSource: String,
        useDataFrames: Boolean = false)
    Seq(
      Config(
        "parquet"),
      Config(
        "parquet",
        useDataFrames = true),
      Config(
        "orc"),
      Config(
        "orc",
        useDataFrames = true)).foreach { config =>
      withTable("t") {
        sql(s"create table t(i boolean) using ${config.dataSource}")
        if (config.useDataFrames) {
          Seq((false)).toDF.write.insertInto("t")
        } else {
          sql("insert into t select false")
        }
        sql("alter table t add column s array<int> default array(1, 2)")
        checkAnswer(spark.table("t"), Row(false, Seq(1, 2)))
      }
    }
    // Negative tests: provided array element types must match their corresponding DEFAULT
    // declarations, if applicable.
    val incompatibleDefault =
    "Failed to execute ALTER TABLE ADD COLUMNS command because the destination table column s " +
      "has a DEFAULT value with type"
    Seq(
      Config(
        "parquet"),
      Config(
        "parquet",
        true)).foreach { config =>
      withTable("t") {
        sql(s"create table t(i boolean) using ${config.dataSource}")
        if (config.useDataFrames) {
          Seq((false)).toDF.write.insertInto("t")
        } else {
          sql("insert into t select false")
        }
        assert(intercept[AnalysisException] {
          sql("alter table t add column s array<int> default array('abc', 'def')")
        }.getMessage.contains(incompatibleDefault))
      }
    }
  }

  test("SPARK-39557 INSERT INTO statements with tables with struct defaults") {
    // Positive tests: struct types are supported as default values.
    case class Config(
        dataSource: String,
        useDataFrames: Boolean = false)
    Seq(
      Config(
        "parquet"),
      Config(
        "parquet",
        useDataFrames = true),
      Config(
        "orc"),
      Config(
        "orc",
        useDataFrames = true)).foreach { config =>
      withTable("t") {
        sql(s"create table t(i boolean) using ${config.dataSource}")
        if (config.useDataFrames) {
          Seq((false)).toDF.write.insertInto("t")
        } else {
          sql("insert into t select false")
        }
        sql("alter table t add column s struct<x boolean, y string> default struct(true, 'abc')")
        checkAnswer(spark.table("t"), Row(false, Row(true, "abc")))
      }
    }

    // Negative tests: provided map element types must match their corresponding DEFAULT
    // declarations, if applicable.
    val incompatibleDefault =
    "Failed to execute ALTER TABLE ADD COLUMNS command because the destination table column s " +
      "has a DEFAULT value with type"
    Seq(
      Config(
        "parquet"),
      Config(
        "parquet",
        true)).foreach { config =>
      withTable("t") {
        sql(s"create table t(i boolean) using ${config.dataSource}")
        if (config.useDataFrames) {
          Seq((false)).toDF.write.insertInto("t")
        } else {
          sql("insert into t select false")
        }
        assert(intercept[AnalysisException] {
          sql("alter table t add column s struct<x boolean, y string> default struct(42, 56)")
        }.getMessage.contains(incompatibleDefault))
      }
    }
  }

  test("SPARK-39557 INSERT INTO statements with tables with map defaults") {
    // Positive tests: map types are supported as default values.
    case class Config(
        dataSource: String,
        useDataFrames: Boolean = false)
    Seq(
      Config(
        "parquet"),
      Config(
        "parquet",
        useDataFrames = true),
      Config(
        "orc"),
      Config(
        "orc",
        useDataFrames = true)).foreach { config =>
      withTable("t") {
        sql(s"create table t(i boolean) using ${config.dataSource}")
        if (config.useDataFrames) {
          Seq((false)).toDF.write.insertInto("t")
        } else {
          sql("insert into t select false")
        }
        sql("alter table t add column s map<boolean, string> default map(true, 'abc')")
        checkAnswer(spark.table("t"), Row(false, Map(true -> "abc")))
      }
      withTable("t") {
        sql(
          s"""
            create table t(
              i int,
              s struct<
                x array<
                  struct<a int, b int>>,
                y array<
                  map<boolean, string>>>
              default struct(
                array(
                  struct(1, 2)),
                array(
                  map(false, 'def', true, 'jkl'))))
              using ${config.dataSource}""")
        sql("insert into t select 1, default")
        sql("alter table t alter column s drop default")
        if (config.useDataFrames) {
          Seq((2, null)).toDF.write.insertInto("t")
        } else {
          sql("insert into t select 2, default")
        }
        sql(
          """
            alter table t alter column s
            set default struct(
              array(
                struct(3, 4)),
              array(
                map(false, 'mno', true, 'pqr')))""")
        sql("insert into t select 3, default")
        sql(
          """
            alter table t
            add column t array<
              map<boolean, string>>
            default array(
              map(true, 'xyz'))""")
        sql("insert into t(i, s) select 4, default")
        checkAnswer(spark.table("t"),
          Seq(
            Row(1,
              Row(Seq(Row(1, 2)), Seq(Map(false -> "def", true -> "jkl"))),
              Seq(Map(true -> "xyz"))),
            Row(2,
              null,
              Seq(Map(true -> "xyz"))),
            Row(3,
              Row(Seq(Row(3, 4)), Seq(Map(false -> "mno", true -> "pqr"))),
              Seq(Map(true -> "xyz"))),
            Row(4,
              Row(Seq(Row(3, 4)), Seq(Map(false -> "mno", true -> "pqr"))),
              Seq(Map(true -> "xyz")))))
      }
    }
    // Negative tests: provided map element types must match their corresponding DEFAULT
    // declarations, if applicable.
    val incompatibleDefault =
    "Failed to execute ALTER TABLE ADD COLUMNS command because the destination table column s " +
      "has a DEFAULT value with type"
    Seq(
      Config(
        "parquet"),
      Config(
        "parquet",
        true)).foreach { config =>
      withTable("t") {
        sql(s"create table t(i boolean) using ${config.dataSource}")
        if (config.useDataFrames) {
          Seq((false)).toDF.write.insertInto("t")
        } else {
          sql("insert into t select false")
        }
        assert(intercept[AnalysisException] {
          sql("alter table t add column s map<boolean, string> default map(42, 56)")
        }.getMessage.contains(incompatibleDefault))
      }
    }
  }

  test("SPARK-39643 Prohibit subquery expressions in DEFAULT values") {
    Seq(
      "create table t(a string default (select 'abc')) using parquet",
      "create table t(a string default exists(select 42 where true)) using parquet",
      "create table t(a string default 1 in (select 1 union all select 2)) using parquet"
    ).foreach { query =>
      assert(intercept[AnalysisException] {
        sql(query)
      }.getMessage.contains(
        QueryCompilationErrors.defaultValuesMayNotContainSubQueryExpressions().getMessage))
    }
  }

  test("SPARK-39844 Restrict adding DEFAULT columns for existing tables to certain sources") {
    Seq("csv", "json", "orc", "parquet").foreach { provider =>
      withTable("t1") {
        // Set the allowlist of table providers to include the new table type for all SQL commands.
        withSQLConf(SQLConf.DEFAULT_COLUMN_ALLOWED_PROVIDERS.key -> provider) {
          // It is OK to create a new table with a column DEFAULT value assigned if the table
          // provider is in the allowlist.
          sql(s"create table t1(a int default 42) using $provider")
          // It is OK to add a new column to the table with a DEFAULT value to the existing table
          // since this table provider is not yet present in the
          // 'ADD_DEFAULT_COLUMN_EXISTING_TABLE_BANNED_PROVIDERS' denylist.
          sql(s"alter table t1 add column (b string default 'abc')")
          // Insert a row into the table and check that the assigned DEFAULT value is correct.
          sql(s"insert into t1 values (42, default)")
          checkAnswer(spark.table("t1"), Row(42, "abc"))
        }
        // Now update the allowlist of table providers to prohibit ALTER TABLE ADD COLUMN commands
        // from assigning DEFAULT values.
        withSQLConf(SQLConf.DEFAULT_COLUMN_ALLOWED_PROVIDERS.key -> s"$provider*") {
          assert(intercept[AnalysisException] {
            // Try to add another column to the existing table again. This fails because the table
            // provider is now in the denylist.
            sql(s"alter table t1 add column (b string default 'abc')")
          }.getMessage.contains(
            QueryCompilationErrors.addNewDefaultColumnToExistingTableNotAllowed(
              "ALTER TABLE ADD COLUMNS", provider).getMessage))
          withTable("t2") {
            // It is still OK to create a new table with a column DEFAULT value assigned, even if
            // the table provider is in the above denylist.
            sql(s"create table t2(a int default 42) using $provider")
            // Insert a row into the table and check that the assigned DEFAULT value is correct.
            sql(s"insert into t2 values (default)")
            checkAnswer(spark.table("t2"), Row(42))
          }
        }
      }
    }
  }

  test("SPARK-43071: INSERT INTO from queries whose final operators are not projections") {
    def runTest(insert: String, expected: Seq[Row]): Unit = {
      withTable("t1", "t2") {
        sql("create table t1(i boolean, s bigint default 42) using parquet")
        sql("insert into t1 values (true, 41), (false, default)")
        sql("create table t2(i boolean default true, s bigint default 42, " +
          "t string default 'abc') using parquet")
        sql(insert)
        checkAnswer(spark.table("t2"), expected)
      }
    }
    def expectFail(insert: String): Unit = {
      withTable("t1", "t2") {
        sql("create table t1(i boolean, s bigint default 42) using parquet")
        sql("insert into t1 values (true, 41), (false, default)")
        sql("create table t2(i boolean default true, s bigint default 42, " +
          "t string default 'abc') using parquet")
        assert(intercept[AnalysisException](sql(insert)).errorClass.get.startsWith(
          "UNRESOLVED_COLUMN"))
      }
    }
    // The DEFAULT references in these query patterns are detected and replaced.
    runTest("insert into t2 (i, s) select default, s from t1 order by s limit 1",
      Seq(Row(true, 41L, "abc")))
    runTest("insert into t2 (i, s) select default, s from t1 order by s limit 1 offset 1",
      Seq(Row(true, 42L, "abc")))
    runTest("insert into t2 (i, s) select default, default from t1 inner join t1 using (i, s)",
      Seq(Row(true, 42L, "abc"),
        Row(true, 42L, "abc")))
    // The DEFAULT references in these query patterns are not detected.
    expectFail("insert into t2 (i, s) select default, 41L union all select default, 42L")
    expectFail("insert into t2 (i, s) select default, min(s) from t1 group by i")
  }

  test("Stop task set if FileAlreadyExistsException was thrown") {
    val tableName = "t"
    Seq(true, false).foreach { fastFail =>
      withSQLConf("fs.file.impl" -> classOf[FileExistingTestFileSystem].getName,
        "fs.file.impl.disable.cache" -> "true",
        SQLConf.FASTFAIL_ON_FILEFORMAT_OUTPUT.key -> fastFail.toString) {
        withTable(tableName) {
          sql(
            s"""
              |CREATE TABLE $tableName(i INT, part1 INT) USING PARQUET
              |PARTITIONED BY (part1)
          """.stripMargin)

          val df = Seq((1, 1)).toDF("i", "part1")
          val err = intercept[SparkException] {
            df.write.mode("overwrite").format("parquet").insertInto(tableName)
          }

          if (fastFail) {
            assert(err.getMessage.contains("can not write to output file: " +
              "org.apache.hadoop.fs.FileAlreadyExistsException"))
          } else {
            checkError(
              exception = err.getCause.asInstanceOf[SparkException],
              errorClass = "TASK_WRITE_FAILED",
              parameters = Map("path" -> s".*$tableName"),
              matchPVals = true
            )
          }
        }
      }
    }
  }

  test("SPARK-29174 Support LOCAL in INSERT OVERWRITE DIRECTORY to data source") {
    withTempPath { dir =>
      val path = dir.toURI.getPath
      sql(s"""create table tab1 ( a int) using parquet location '$path'""")
      sql("insert into tab1 values(1)")
      checkAnswer(sql("select * from tab1"), Seq(1).map(i => Row(i)))
      sql("create table tab2 ( a int) using parquet")
      sql("insert into tab2 values(2)")
      checkAnswer(sql("select * from tab2"), Seq(2).map(i => Row(i)))
      sql(s"""insert overwrite local directory '$path' using parquet select * from tab2""")
      sql("refresh table tab1")
      checkAnswer(sql("select * from tab1"), Seq(2).map(i => Row(i)))
    }
  }

  test("SPARK-29174 fail LOCAL in INSERT OVERWRITE DIRECT remote path") {
    checkError(
      exception = intercept[ParseException] {
        sql("insert overwrite local directory 'hdfs:/abcd' using parquet select 1")
      },
      errorClass = "_LEGACY_ERROR_TEMP_0050",
      parameters = Map.empty,
      context = ExpectedContext(
        fragment = "insert overwrite local directory 'hdfs:/abcd' using parquet",
        start = 0,
        stop = 58))
  }

  test("SPARK-32508 " +
    "Disallow empty part col values in partition spec before static partition writing") {
    withTable("insertTable") {
      sql(
        """
          |CREATE TABLE insertTable(i int, part1 string, part2 string) USING PARQUET
          |PARTITIONED BY (part1, part2)
            """.stripMargin)
      val msg = "Partition spec is invalid"
      assert(intercept[AnalysisException] {
        sql("INSERT INTO TABLE insertTable PARTITION(part1=1, part2='') SELECT 1")
      }.getMessage.contains(msg))
      assert(intercept[AnalysisException] {
        sql("INSERT INTO TABLE insertTable PARTITION(part1='', part2) SELECT 1 ,'' AS part2")
      }.getMessage.contains(msg))

      sql("INSERT INTO TABLE insertTable PARTITION(part1='1', part2='2') SELECT 1")
      sql("INSERT INTO TABLE insertTable PARTITION(part1='1', part2) SELECT 1 ,'2' AS part2")
      sql("INSERT INTO TABLE insertTable PARTITION(part1='1', part2) SELECT 1 ,'' AS part2")
    }
  }

  test("SPARK-33294: Add query resolved check before analyze InsertIntoDir") {
    withTempPath { path =>
      val insert = s"INSERT OVERWRITE DIRECTORY '${path.getAbsolutePath}' USING PARQUET"
      checkError(
        exception = intercept[AnalysisException] {
          sql(
            s"""
              |$insert
              |SELECT * FROM (
              | SELECT c3 FROM (
              |  SELECT c1, c2 from values(1,2) t(c1, c2)
              |  )
              |)
            """.stripMargin)
        },
        errorClass = "UNRESOLVED_COLUMN.WITH_SUGGESTION",
        sqlState = "42703",
        parameters = Map(
          "objectName" -> "`c3`",
          "proposal" ->
            "`__auto_generated_subquery_name`.`c1`, `__auto_generated_subquery_name`.`c2`"),
        context = ExpectedContext(
          fragment = "c3",
          start = insert.length + 26,
          stop = insert.length + 27))
    }
  }

  test("SPARK-34926: PartitioningUtils.getPathFragment() should respect partition value is null") {
    withTable("t1", "t2") {
      sql("CREATE TABLE t1(id INT) USING PARQUET")
      sql(
        """
          |CREATE TABLE t2 (c1 INT, part STRING)
          |  USING parquet
          |PARTITIONED BY (part)
          |""".stripMargin)
      sql(
        """
          |INSERT INTO TABLE t2 PARTITION (part = null)
          |SELECT * FROM t1 where 1=0""".stripMargin)
      checkAnswer(spark.table("t2"), Nil)
    }
  }

  test("SPARK-35106: insert overwrite with custom partition path") {
    withTempPath { path =>
      withTable("t") {
      sql(
        """
          |create table t(i int, part1 int, part2 int) using parquet
          |partitioned by (part1, part2)
        """.stripMargin)

        sql(s"alter table t add partition(part1=1, part2=1) location '${path.getAbsolutePath}'")
        sql(s"insert into t partition(part1=1, part2=1) select 1")
        checkAnswer(spark.table("t"), Row(1, 1, 1))

        sql("insert overwrite table t partition(part1=1, part2=1) select 2")
        checkAnswer(spark.table("t"), Row(2, 1, 1))

        sql("insert overwrite table t partition(part1=2, part2) select 2, 2")
        checkAnswer(spark.table("t"), Row(2, 1, 1) :: Row(2, 2, 2) :: Nil)

        sql("insert overwrite table t partition(part1=1, part2=2) select 3")
        checkAnswer(spark.table("t"), Row(2, 1, 1) :: Row(2, 2, 2) :: Row(3, 1, 2) :: Nil)

        sql("insert overwrite table t partition(part1=1, part2) select 4, 1")
        checkAnswer(spark.table("t"), Row(4, 1, 1) :: Row(2, 2, 2) :: Nil)
      }
    }
  }

  test("SPARK-35106: dynamic partition overwrite with custom partition path") {
    withSQLConf(SQLConf.PARTITION_OVERWRITE_MODE.key -> PartitionOverwriteMode.DYNAMIC.toString) {
      withTempPath { path =>
        withTable("t") {
          sql(
            """
              |create table t(i int, part1 int, part2 int) using parquet
              |partitioned by (part1, part2)
            """.stripMargin)

          sql(s"insert into t partition(part1=1, part2=1) select 1")
          checkAnswer(spark.table("t"), Row(1, 1, 1))

          sql(s"alter table t add partition(part1=1, part2=2) location '${path.getAbsolutePath}'")

          // dynamic partition overwrite to empty custom partition
          sql(s"insert overwrite table t partition(part1=1, part2=2) select 1")
          checkAnswer(spark.table("t"), Row(1, 1, 1) :: Row(1, 1, 2) :: Nil)

          // dynamic partition overwrite to non-empty custom partition
          sql("insert overwrite table t partition(part1=1, part2=2) select 2")
          checkAnswer(spark.table("t"), Row(1, 1, 1) :: Row(2, 1, 2) :: Nil)
        }
      }
    }
  }

  test("SPARK-35106: Throw exception when rename custom partition paths returns false") {
    withSQLConf(
      "fs.file.impl" -> classOf[RenameFromSparkStagingToFinalDirAlwaysTurnsFalseFilesystem].getName,
      "fs.file.impl.disable.cache" -> "true") {
      withTempPath { path =>
        withTable("t") {
          sql(
            """
              |create table t(i int, part1 int, part2 int) using parquet
              |partitioned by (part1, part2)
            """.stripMargin)

          sql(s"alter table t add partition(part1=1, part2=1) location '${path.getAbsolutePath}'")

          val e = intercept[IOException] {
            sql(s"insert into t partition(part1=1, part2=1) select 1")
          }
          assert(e.getMessage.contains("Failed to rename"))
          assert(e.getMessage.contains("when committing files staged for absolute location"))
        }
      }
    }
  }

  test("SPARK-35106: Throw exception when rename dynamic partition paths returns false") {
    withSQLConf(
      "fs.file.impl" -> classOf[RenameFromSparkStagingToFinalDirAlwaysTurnsFalseFilesystem].getName,
      "fs.file.impl.disable.cache" -> "true",
      SQLConf.PARTITION_OVERWRITE_MODE.key -> PartitionOverwriteMode.DYNAMIC.toString) {

      withTable("t") {
        sql(
          """
            |create table t(i int, part1 int, part2 int) using parquet
            |partitioned by (part1, part2)
          """.stripMargin)

        val e = intercept[IOException] {
          sql(s"insert overwrite table t partition(part1, part2) values (1, 1, 1)")
        }
        assert(e.getMessage.contains("Failed to rename"))
        assert(e.getMessage.contains(
          "when committing files staged for overwriting dynamic partitions"))
      }
    }
  }

  test("SPARK-36980: Insert support query with CTE") {
    withTable("t") {
      sql("CREATE TABLE t(i int, part1 int, part2 int) using parquet")
      sql("INSERT INTO t WITH v1(c1) as (values (1)) select 1, 2, 3 from v1")
      checkAnswer(spark.table("t"), Row(1, 2, 3))
    }
  }

  test("SELECT clause with star wildcard") {
    withTable("t1") {
      sql("CREATE TABLE t1(c1 int, c2 string) using parquet")
      sql("INSERT INTO TABLE t1 select * from jt where a=1")
      checkAnswer(spark.table("t1"), Row(1, "str1"))
    }

    withSQLConf(SQLConf.USE_NULLS_FOR_MISSING_DEFAULT_COLUMN_VALUES.key -> "true") {
      withTable("t1") {
        sql("CREATE TABLE t1(c1 int, c2 string, c3 int) using parquet")
        sql("INSERT INTO TABLE t1(c1, c2) select * from jt where a=1")
        checkAnswer(spark.table("t1"), Row(1, "str1", null))
        sql("INSERT INTO TABLE t1 select *, 2 from jt where a=2")
        checkAnswer(spark.table("t1"), Seq(Row(1, "str1", null), Row(2, "str2", 2)))
      }
    }
  }

  test("SPARK-37294: insert ANSI intervals into a table partitioned by the interval columns") {
    val tbl = "interval_table"
    Seq(PartitionOverwriteMode.DYNAMIC, PartitionOverwriteMode.STATIC).foreach { mode =>
      withSQLConf(SQLConf.PARTITION_OVERWRITE_MODE.key -> mode.toString) {
        withTable(tbl) {
          sql(
            s"""
              |CREATE TABLE $tbl (i INT, part1 INTERVAL YEAR, part2 INTERVAL DAY) USING PARQUET
              |PARTITIONED BY (part1, part2)
              """.stripMargin)

          sql(
            s"""ALTER TABLE $tbl ADD PARTITION (
               |part1 = INTERVAL '2' YEAR,
               |part2 = INTERVAL '3' DAY)""".stripMargin)
          sql(s"INSERT OVERWRITE TABLE $tbl SELECT 1, INTERVAL '2' YEAR, INTERVAL '3' DAY")
          sql(s"INSERT INTO TABLE $tbl SELECT 4, INTERVAL '5' YEAR, INTERVAL '6' DAY")
          sql(
            s"""
               |INSERT INTO $tbl
               | PARTITION (part1 = INTERVAL '8' YEAR, part2 = INTERVAL '9' DAY)
               |SELECT 7""".stripMargin)

          checkAnswer(
            spark.table(tbl),
            Seq(Row(1, Period.ofYears(2), Duration.ofDays(3)),
              Row(4, Period.ofYears(5), Duration.ofDays(6)),
              Row(7, Period.ofYears(8), Duration.ofDays(9))))
        }
      }
    }
  }

  test("SPARK-42286: Insert into a table select from case when with cast, positive test") {
    withTable("t1", "t2") {
      sql("create table t1 (x int) using parquet")
      sql("insert into t1 values (1), (2)")
      sql("create table t2 (x Decimal(9, 0)) using parquet")
      sql("insert into t2 select 0 - (case when x = 1 then 1 else x end) from t1 where x = 1")
      checkAnswer(spark.table("t2"), Row(-1))
    }
  }
}

class FileExistingTestFileSystem extends RawLocalFileSystem {
  override def create(
      f: Path,
      overwrite: Boolean,
      bufferSize: Int,
      replication: Short,
      blockSize: Long): FSDataOutputStream = {
    throw new FileAlreadyExistsException(s"${f.toString} already exists")
  }
}

class RenameFromSparkStagingToFinalDirAlwaysTurnsFalseFilesystem extends RawLocalFileSystem {
  override def rename(src: Path, dst: Path): Boolean = {
    (!isSparkStagingDir(src) || isSparkStagingDir(dst)) && super.rename(src, dst)
  }

  private def isSparkStagingDir(path: Path): Boolean = {
    path.toString.contains(".spark-staging-")
  }
}
