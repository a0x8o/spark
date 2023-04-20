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
package org.apache.spark.sql

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.Files

import scala.collection.JavaConverters._

import io.grpc.StatusRuntimeException
import java.util.Properties
import org.apache.commons.io.FileUtils
import org.apache.commons.io.output.TeeOutputStream
import org.apache.commons.lang3.{JavaVersion, SystemUtils}
import org.scalactic.TolerantNumerics

import org.apache.spark.SPARK_VERSION
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders.StringEncoder
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.catalyst.parser.ParseException
import org.apache.spark.sql.connect.client.util.{IntegrationTestUtils, RemoteSparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._

class ClientE2ETestSuite extends RemoteSparkSession with SQLHelper {

  // Spark Result
  test("spark result schema") {
    val df = spark.sql("select val from (values ('Hello'), ('World')) as t(val)")
    df.withResult { result =>
      val schema = result.schema
      assert(schema == StructType(StructField("val", StringType, nullable = false) :: Nil))
    }
  }

  test("spark result array") {
    val df = spark.sql("select val from (values ('Hello'), ('World')) as t(val)")
    val result = df.collect()
    assert(result.length == 2)
    assert(result(0).getString(0) == "Hello")
    assert(result(1).getString(0) == "World")
  }

  test("eager execution of sql") {
    assume(IntegrationTestUtils.isSparkHiveJarAvailable)
    withTable("test_martin") {
      // Fails, because table does not exist.
      assertThrows[StatusRuntimeException] {
        spark.sql("select * from test_martin").collect()
      }
      // Execute eager, DML
      spark.sql("create table test_martin (id int)")
      // Execute read again.
      val rows = spark.sql("select * from test_martin").collect()
      assert(rows.length == 0)
      spark.sql("insert into test_martin values (1), (2)")
      val rows_new = spark.sql("select * from test_martin").collect()
      assert(rows_new.length == 2)
    }
  }

  test("simple dataset") {
    val df = spark.range(10).limit(3)
    val result = df.collect()
    assert(result.length == 3)
    assert(result(0) == 0)
    assert(result(1) == 1)
    assert(result(2) == 2)
  }

  test("read and write") {
    val testDataPath = java.nio.file.Paths
      .get(
        IntegrationTestUtils.sparkHome,
        "connector",
        "connect",
        "common",
        "src",
        "test",
        "resources",
        "query-tests",
        "test-data",
        "people.csv")
      .toAbsolutePath
    val df = spark.read
      .format("csv")
      .option("path", testDataPath.toString)
      .options(Map("header" -> "true", "delimiter" -> ";"))
      .schema(
        StructType(
          StructField("name", StringType) ::
            StructField("age", IntegerType) ::
            StructField("job", StringType) :: Nil))
      .load()
    val outputFolderPath = Files.createTempDirectory("output").toAbsolutePath

    df.write
      .format("csv")
      .mode("overwrite")
      .options(Map("header" -> "true", "delimiter" -> ";"))
      .save(outputFolderPath.toString)

    // We expect only one csv file saved.
    val outputFile = outputFolderPath.toFile
      .listFiles()
      .filter(file => file.getPath.endsWith(".csv"))(0)

    assert(FileUtils.contentEquals(testDataPath.toFile, outputFile))
  }

  test("read path collision") {
    val testDataPath = java.nio.file.Paths
      .get(
        IntegrationTestUtils.sparkHome,
        "connector",
        "connect",
        "common",
        "src",
        "test",
        "resources",
        "query-tests",
        "test-data",
        "people.csv")
      .toAbsolutePath
    val df = spark.read
      .format("csv")
      .option("path", testDataPath.toString)
      .options(Map("header" -> "true", "delimiter" -> ";"))
      .schema(
        StructType(
          StructField("name", StringType) ::
            StructField("age", IntegerType) ::
            StructField("job", StringType) :: Nil))
      .csv(testDataPath.toString)
    // Failed because the path cannot be provided both via option and load method (csv).
    assertThrows[StatusRuntimeException] {
      df.collect()
    }
  }

  test("textFile") {
    val testDataPath = java.nio.file.Paths
      .get(
        IntegrationTestUtils.sparkHome,
        "connector",
        "connect",
        "common",
        "src",
        "test",
        "resources",
        "query-tests",
        "test-data",
        "people.txt")
      .toAbsolutePath
    val result = spark.read.textFile(testDataPath.toString).collect()
    val expected = Array("Michael, 29", "Andy, 30", "Justin, 19")
    assert(result.length == 3)
    assert(result === expected)
  }

  test("write table") {
    withTable("myTable") {
      val df = spark.range(10).limit(3)
      df.write.mode(SaveMode.Overwrite).saveAsTable("myTable")
      spark.range(2).write.insertInto("myTable")
      val result = spark.sql("select * from myTable").sort("id").collect()
      assert(result.length == 5)
      assert(result(0).getLong(0) == 0)
      assert(result(1).getLong(0) == 0)
      assert(result(2).getLong(0) == 1)
      assert(result(3).getLong(0) == 1)
      assert(result(4).getLong(0) == 2)
    }
  }

  test("write without table or path") {
    // Should receive no error to write noop
    spark.range(10).write.format("noop").mode("append").save()
  }

  test("write jdbc") {
    assume(IntegrationTestUtils.isSparkHiveJarAvailable)
    if (SystemUtils.isJavaVersionAtLeast(JavaVersion.JAVA_9)) {
      val url = "jdbc:derby:memory:1234"
      val table = "t1"
      try {
        spark.range(10).write.jdbc(url = s"$url;create=true", table, new Properties())
        val result = spark.read.jdbc(url = url, table, new Properties()).collect()
        assert(result.length == 10)
      } finally {
        // clean up
        assertThrows[StatusRuntimeException] {
          spark.read.jdbc(url = s"$url;drop=true", table, new Properties()).collect()
        }
      }
    }
  }

  test("writeTo with create") {
    withTable("testcat.myTableV2") {

      val rows = Seq(Row(1L, "a"), Row(2L, "b"), Row(3L, "c"))

      val schema = StructType(Array(StructField("id", LongType), StructField("data", StringType)))

      spark.createDataFrame(rows.asJava, schema).writeTo("testcat.myTableV2").create()

      val outputRows = spark.table("testcat.myTableV2").collect()
      assert(outputRows.length == 3)
    }
  }

  test("writeTo with create and using") {
    withTable("testcat.myTableV2") {
      val rows = Seq(Row(1L, "a"), Row(2L, "b"), Row(3L, "c"))

      val schema = StructType(Array(StructField("id", LongType), StructField("data", StringType)))

      spark.createDataFrame(rows.asJava, schema).writeTo("testcat.myTableV2").create()
      val outputRows = spark.table("testcat.myTableV2").collect()
      assert(outputRows.length == 3)

      val columns = spark.table("testcat.myTableV2").columns
      assert(columns.length == 2)

      val sqlOutputRows = spark.sql("select * from testcat.myTableV2").collect()
      assert(outputRows.length == 3)
      assert(sqlOutputRows(0).schema == schema)
      assert(sqlOutputRows(1).getString(1) == "b")
    }
  }

  test("writeTo with create and append") {
    withTable("testcat.myTableV2") {

      val rows = Seq(Row(1L, "a"), Row(2L, "b"), Row(3L, "c"))

      val schema = StructType(Array(StructField("id", LongType), StructField("data", StringType)))

      spark.sql("CREATE TABLE testcat.myTableV2 (id bigint, data string) USING foo")

      assert(spark.table("testcat.myTableV2").collect().isEmpty)

      spark.createDataFrame(rows.asJava, schema).writeTo("testcat.myTableV2").append()
      val outputRows = spark.table("testcat.myTableV2").collect()
      assert(outputRows.length == 3)
    }
  }

  test("WriteTo with overwrite") {
    withTable("testcat.myTableV2") {

      val rows1 = (1L to 3L).map { i =>
        Row(i, "" + (i - 1 + 'a'))
      }
      val rows2 = (4L to 7L).map { i =>
        Row(i, "" + (i - 1 + 'a'))
      }

      val schema = StructType(Array(StructField("id", LongType), StructField("data", StringType)))

      spark.sql(
        "CREATE TABLE testcat.myTableV2 (id bigint, data string) USING foo PARTITIONED BY (id)")

      assert(spark.table("testcat.myTableV2").collect().isEmpty)

      spark.createDataFrame(rows1.asJava, schema).writeTo("testcat.myTableV2").append()
      val outputRows = spark.table("testcat.myTableV2").collect()
      assert(outputRows.length == 3)

      spark
        .createDataFrame(rows2.asJava, schema)
        .writeTo("testcat.myTableV2")
        .overwrite(functions.expr("true"))
      val outputRows2 = spark.table("testcat.myTableV2").collect()
      assert(outputRows2.length == 4)

    }
  }

  test("WriteTo with overwritePartitions") {
    withTable("testcat.myTableV2") {

      val rows = (4L to 7L).map { i =>
        Row(i, "" + (i - 1 + 'a'))
      }

      val schema = StructType(Array(StructField("id", LongType), StructField("data", StringType)))

      spark.sql(
        "CREATE TABLE testcat.myTableV2 (id bigint, data string) USING foo PARTITIONED BY (id)")

      assert(spark.table("testcat.myTableV2").collect().isEmpty)

      spark
        .createDataFrame(rows.asJava, schema)
        .writeTo("testcat.myTableV2")
        .overwritePartitions()
      val outputRows = spark.table("testcat.myTableV2").collect()
      assert(outputRows.length == 4)

    }
  }

  test("write path collision") {
    val df = spark.range(10)
    val outputFolderPath = Files.createTempDirectory("output").toAbsolutePath
    // Failed because the path cannot be provided both via option and save method.
    assertThrows[StatusRuntimeException] {
      df.write.option("path", outputFolderPath.toString).save(outputFolderPath.toString)
    }
  }

  // TODO test large result when we can create table or view
  // test("test spark large result")
  private def captureStdOut(block: => Unit): String = {
    val currentOut = Console.out
    val capturedOut = new ByteArrayOutputStream()
    val newOut = new PrintStream(new TeeOutputStream(currentOut, capturedOut))
    Console.withOut(newOut) {
      block
    }
    capturedOut.toString
  }

  private def checkFragments(result: String, fragmentsToCheck: Seq[String]): Unit = {
    fragmentsToCheck.foreach { fragment =>
      assert(result.contains(fragment))
    }
  }

  private def testCapturedStdOut(block: => Unit, fragmentsToCheck: String*): Unit = {
    checkFragments(captureStdOut(block), fragmentsToCheck)
  }

  private def testCapturedStdOut(
      block: => Unit,
      expectedNumLines: Int,
      expectedMaxWidth: Int,
      fragmentsToCheck: String*): Unit = {
    val result = captureStdOut(block)
    val lines = result.split('\n')
    assert(lines.length === expectedNumLines)
    assert(lines.map((s: String) => s.length).max <= expectedMaxWidth)
    checkFragments(result, fragmentsToCheck)
  }

  private val simpleSchema = new StructType().add("value", "long", nullable = true)

  // Dataset tests
  test("Dataset inspection") {
    val df = spark.range(10)
    val local = spark.newDataFrame { builder =>
      builder.getLocalRelationBuilder.setSchema(simpleSchema.catalogString)
    }
    assert(!df.isLocal)
    assert(local.isLocal)
    assert(!df.isStreaming)
    assert(df.toString.contains("[value: bigint]"))
    assert(df.inputFiles.isEmpty)
  }

  test("Dataset schema") {
    val df = spark.range(10)
    assert(df.schema === simpleSchema)
    assert(df.dtypes === Array(("value", "LongType")))
    assert(df.columns === Array("value"))
    testCapturedStdOut(df.printSchema(), simpleSchema.treeString)
    testCapturedStdOut(df.printSchema(5), simpleSchema.treeString(5))
  }

  test("Dataframe schema") {
    val df = spark.sql("select * from range(10)")
    val expectedSchema = new StructType().add("id", "long", nullable = false)
    assert(df.schema === expectedSchema)
    assert(df.dtypes === Array(("id", "LongType")))
    assert(df.columns === Array("id"))
    testCapturedStdOut(df.printSchema(), expectedSchema.treeString)
    testCapturedStdOut(df.printSchema(5), expectedSchema.treeString(5))
  }

  test("Dataset explain") {
    val df = spark.range(10)
    val simpleExplainFragments = Seq("== Physical Plan ==")
    testCapturedStdOut(df.explain(), simpleExplainFragments: _*)
    testCapturedStdOut(df.explain(false), simpleExplainFragments: _*)
    testCapturedStdOut(df.explain("simple"), simpleExplainFragments: _*)
    val extendedExplainFragments = Seq(
      "== Parsed Logical Plan ==",
      "== Analyzed Logical Plan ==",
      "== Optimized Logical Plan ==") ++
      simpleExplainFragments
    testCapturedStdOut(df.explain(true), extendedExplainFragments: _*)
    testCapturedStdOut(df.explain("extended"), extendedExplainFragments: _*)
    testCapturedStdOut(
      df.explain("cost"),
      simpleExplainFragments :+ "== Optimized Logical Plan ==": _*)
    testCapturedStdOut(df.explain("codegen"), "WholeStageCodegen subtrees.")
    testCapturedStdOut(df.explain("formatted"), "Range", "Arguments: ")
  }

  test("Dataset result collection") {
    def checkResult(rows: TraversableOnce[java.lang.Long], expectedValues: Long*): Unit = {
      rows.toIterator.zipAll(expectedValues.iterator, null, null).foreach {
        case (actual, expected) => assert(actual === expected)
      }
    }
    val df = spark.range(10)
    checkResult(df.head() :: Nil, 0L)
    checkResult(df.head(5), 0L, 1L, 2L, 3L, 4L)
    checkResult(df.first() :: Nil, 0L)
    assert(!df.isEmpty)
    assert(df.filter("id > 100").isEmpty)
    checkResult(df.take(3), 0L, 1L, 2L)
    checkResult(df.tail(3), 7L, 8L, 9L)
    checkResult(df.takeAsList(10).asScala, 0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L)
    checkResult(df.filter("id % 3 = 0").collect(), 0L, 3L, 6L, 9L)
    checkResult(df.filter("id < 3").collectAsList().asScala, 0L, 1L, 2L)
    val iterator = df.filter("id > 5 and id < 9").toLocalIterator()
    try {
      checkResult(iterator.asScala, 6L, 7L, 8L)
    } finally {
      iterator.asInstanceOf[AutoCloseable].close()
    }
  }

  test("Dataset show") {
    val df = spark.range(20)
    testCapturedStdOut(df.show(), 24, 5, "+---+", "| id|", "|  0|", "| 19|")
    testCapturedStdOut(
      df.show(10),
      15,
      24,
      "+---+",
      "| id|",
      "|  0|",
      "|  9|",
      "only showing top 10 rows")
    val wideDf =
      spark.range(4).selectExpr("id", "concat('very_very_very_long_string', id) as val")
    testCapturedStdOut(
      wideDf.show(true),
      8,
      26,
      "+---+--------------------+",
      "| id|                 val|",
      "|  0|very_very_very_lo...|")
    testCapturedStdOut(
      wideDf.show(false),
      8,
      33,
      "+---+---------------------------+",
      "|id |val                        |",
      "|2  |very_very_very_long_string2|")
    testCapturedStdOut(
      wideDf.show(2, truncate = false),
      7,
      33,
      "+---+---------------------------+",
      "|id |val                        |",
      "|1  |very_very_very_long_string1|",
      "only showing top 2 rows")
    testCapturedStdOut(
      df.show(8, 10, vertical = true),
      17,
      23,
      "-RECORD 3--",
      "id  | 7",
      "only showing top 8 rows")
  }

  test("Dataset randomSplit") {
    implicit val tolerance = TolerantNumerics.tolerantDoubleEquality(0.01)

    val df = spark.range(100)
    def checkSample(
        ds: Dataset[java.lang.Long],
        lower: Double,
        upper: Double,
        seed: Long): Unit = {
      assert(ds.plan.getRoot.hasSample)
      val sample = ds.plan.getRoot.getSample
      assert(sample.getSeed === seed)
      assert(sample.getLowerBound === lower)
      assert(sample.getUpperBound === upper)
    }
    val Array(ds1, ds2, ds3) = df.randomSplit(Array(8, 9, 7), 123L)
    checkSample(ds1, 0, 8.0 / 24.0, 123L)
    checkSample(ds2, 8.0 / 24.0, 17.0 / 24.0, 123L)
    checkSample(ds3, 17.0 / 24.0, 1.0, 123L)

    val datasets = df.randomSplitAsList(Array(1, 2, 3, 4), 9L)
    assert(datasets.size() === 4)
    checkSample(datasets.get(0), 0, 1.0 / 10.0, 9L)
    checkSample(datasets.get(1), 1.0 / 10.0, 3.0 / 10.0, 9L)
    checkSample(datasets.get(2), 3.0 / 10.0, 6.0 / 10.0, 9L)
    checkSample(datasets.get(3), 6.0 / 10.0, 1.0, 9L)
  }

  test("Dataset count") {
    assert(spark.range(10).count() === 10)
  }

  test("Dataset collect tuple") {
    val session = spark
    import session.implicits._
    val result = session
      .range(3)
      .select(col("id"), (col("id") % 2).cast("int").as("a"), (col("id") / lit(10.0d)).as("b"))
      .as[(Long, Int, Double)]
      .collect()
    result.zipWithIndex.foreach { case ((id, a, b), i) =>
      assert(id == i)
      assert(a == id % 2)
      assert(b == id / 10.0d)
    }
  }

  private val generateMyTypeColumns = Seq(
    (col("id") / lit(10.0d)).as("b"),
    col("id"),
    lit("world").as("d"),
    (col("id") % 2).cast("int").as("a"))

  private def validateMyTypeResult(result: Array[MyType]): Unit = {
    result.zipWithIndex.foreach { case (MyType(id, a, b), i) =>
      assert(id == i)
      assert(a == id % 2)
      assert(b == id / 10.0d)
    }
  }

  private def validateMyTypeResult(result: Array[(MyType, MyType, MyType)]): Unit = {
    result.zipWithIndex.foreach { case (row, i) =>
      val t1 = row._1
      val t2 = row._2
      val t3 = row._3
      assert(t1 === t2)
      assert(t2 === t3)
      assert(t1.id == i)
      assert(t1.a == t1.id % 2)
      assert(t1.b == t1.id / 10.0d)
    }
  }

  test("Dataset collect complex type") {
    val session = spark
    import session.implicits._
    val result = session
      .range(3)
      .select(generateMyTypeColumns: _*)
      .as[MyType]
      .collect()
    validateMyTypeResult(result)
  }

  test("Dataset typed select - simple column") {
    val numRows = spark.range(1000).select(count("id")).first()
    assert(numRows === 1000)
  }

  test("Dataset typed select - multiple columns") {
    val result = spark.range(1000).select(count("id"), sum("id")).first()
    assert(result.getLong(0) === 1000)
    assert(result.getLong(1) === 499500)
  }

  test("Dataset typed select - complex column") {
    val session = spark
    import session.implicits._
    val ds = session
      .range(3)
      .select(struct(generateMyTypeColumns: _*).as[MyType])
    validateMyTypeResult(ds.collect())
  }

  test("Dataset typed select - multiple complex columns") {
    val session = spark
    import session.implicits._
    val s = struct(generateMyTypeColumns: _*).as[MyType]
    val ds = session
      .range(3)
      .select(s, s, s)
    validateMyTypeResult(ds.collect())
  }

  test("lambda functions") {
    // This test is mostly to validate lambda variables are properly resolved.
    val result = spark
      .range(3)
      .select(
        col("id"),
        array(sequence(col("id"), lit(10)), sequence(col("id") * 2, lit(10))).as("data"))
      .select(col("id"), transform(col("data"), x => transform(x, x => x + 1)).as("data"))
      .select(
        col("id"),
        transform(col("data"), x => aggregate(x, lit(0L), (x, y) => x + y)).as("summaries"))
      .collect()
    val expected = Array(Row(0L, Seq(66L, 66L)), Row(1L, Seq(65L, 63L)), Row(2L, Seq(63L, 56L)))
    assert(result === expected)
  }

  test("shuffle array") {
    // We cannot do structural tests for shuffle because its random seed will always change.
    val result = spark
      .sql("select 1")
      .select(shuffle(array(lit(1), lit(2), lit(3), lit(74))))
      .head()
      .getSeq[Int](0)
    assert(result.toSet === Set(1, 2, 3, 74))
  }

  test("ambiguous joins") {
    val left = spark.range(100).select(col("id"), rand(10).as("a"))
    val right = spark.range(100).select(col("id"), rand(12).as("a"))
    val joined = left.join(right, left("id") === right("id")).select(left("id"), right("a"))
    assert(joined.schema.catalogString === "struct<id:bigint,a:double>")

    val joined2 = left
      .join(right, left.colRegex("id") === right.colRegex("id"))
      .select(left("id"), right("a"))
    assert(joined2.schema.catalogString === "struct<id:bigint,a:double>")
  }

  test("broadcast join") {
    withSQLConf("spark.sql.autoBroadcastJoinThreshold" -> "-1") {
      val left = spark.range(100).select(col("id"), rand(10).as("a"))
      val right = spark.range(100).select(col("id"), rand(12).as("a"))
      val joined =
        left.join(broadcast(right), left("id") === right("id")).select(left("id"), right("a"))
      assert(joined.schema.catalogString === "struct<id:bigint,a:double>")
      testCapturedStdOut(joined.explain(), "BroadcastHashJoin")
    }
  }

  test("test temp view") {
    try {
      spark.range(100).createTempView("test1")
      assert(spark.sql("SELECT * FROM test1").count() == 100)
      spark.range(1000).createOrReplaceTempView("test1")
      assert(spark.sql("SELECT * FROM test1").count() == 1000)
      spark.range(100).createGlobalTempView("view1")
      assert(spark.sql("SELECT * FROM global_temp.view1").count() == 100)
      spark.range(1000).createOrReplaceGlobalTempView("view1")
      assert(spark.sql("SELECT * FROM global_temp.view1").count() == 1000)
    } finally {
      spark.sql("DROP VIEW IF EXISTS test1")
      spark.sql("DROP VIEW IF EXISTS global_temp.view1")
    }
  }

  test("time") {
    val timeFragments = Seq("Time taken: ", " ms")
    testCapturedStdOut(spark.time(spark.sql("select 1").collect()), timeFragments: _*)
  }

  test("RuntimeConfig") {
    intercept[NoSuchElementException](spark.conf.get("foo.bar"))
    assert(spark.conf.getOption("foo.bar").isEmpty)
    spark.conf.set("foo.bar", value = true)
    assert(spark.conf.getOption("foo.bar") === Option("true"))
    spark.conf.set("foo.bar.numBaz", 100L)
    assert(spark.conf.get("foo.bar.numBaz") === "100")
    spark.conf.set("foo.bar.name", "donkey")
    assert(spark.conf.get("foo.bar.name") === "donkey")
    spark.conf.unset("foo.bar.name")
    val allKeyValues = spark.conf.getAll
    assert(allKeyValues("foo.bar") === "true")
    assert(allKeyValues("foo.bar.numBaz") === "100")
    assert(!spark.conf.isModifiable("foo.bar")) // This is a bit odd.
    assert(spark.conf.isModifiable("spark.sql.ansi.enabled"))
    assert(!spark.conf.isModifiable("spark.sql.globalTempDatabase"))
    intercept[Exception](spark.conf.set("spark.sql.globalTempDatabase", "/dev/null"))
  }

  test("SparkVersion") {
    assert(spark.version.nonEmpty)
    assert(spark.version == SPARK_VERSION)
  }

  private def checkSameResult[E](expected: scala.collection.Seq[E], dataset: Dataset[E]): Unit = {
    dataset.withResult { result =>
      assert(expected === result.iterator.asScala.toBuffer)
    }
  }

  test("Local Relation implicit conversion") {
    val session = spark
    import session.implicits._

    val simpleValues = Seq(1, 24, 3)
    checkSameResult(simpleValues, simpleValues.toDS())
    checkSameResult(simpleValues.map(v => Row(v)), simpleValues.toDF())

    val complexValues = Seq((5, "a"), (6, "b"))
    checkSameResult(complexValues, complexValues.toDS())
    checkSameResult(
      complexValues.map(kv => KV(kv._2, kv._1)),
      complexValues.toDF("value", "key").as[KV])
  }

  test("SparkSession.createDataFrame - row") {
    val rows = java.util.Arrays.asList(Row("bob", 99), Row("Club", 5), Row("Bag", 5))
    val schema = new StructType().add("key", "string").add("value", "int")
    checkSameResult(rows.asScala, spark.createDataFrame(rows, schema))
  }

  test("SparkSession.createDataFrame - bean") {
    def bean(v: String): SimpleBean = {
      val bean = new SimpleBean
      bean.setValue(v)
      bean
    }
    val beans = java.util.Arrays.asList(bean("x"), bean("s"), bean("d"))
    checkSameResult(
      beans.asScala.map(b => Row(b.value)),
      spark.createDataFrame(beans, classOf[SimpleBean]))
  }

  test("SparkSession typed createDataSet/createDataframe") {
    val session = spark
    import session.implicits._
    val list = java.util.Arrays.asList(KV("bob", 99), KV("Club", 5), KV("Bag", 5))
    checkSameResult(list.asScala, session.createDataset(list))
    checkSameResult(
      list.asScala.map(kv => Row(kv.key, kv.value)),
      session.createDataFrame(list.asScala.toSeq))
  }

  test("SparkSession newSession") {
    val oldId = spark.sql("SELECT 1").analyze.getSessionId
    val newId = spark.newSession().sql("SELECT 1").analyze.getSessionId
    assert(oldId != newId)
  }

  test("createDataFrame from complex type schema") {
    val schema = new StructType()
      .add(
        "c1",
        new StructType()
          .add("c1-1", StringType)
          .add("c1-2", StringType))
    val data = Seq(Row(Row(null, "a2")), Row(Row("b1", "b2")), Row(null))
    val result = spark.createDataFrame(data.asJava, schema).collect()
    assert(result === data)
  }

  test("SameSemantics") {
    val plan = spark.sql("select 1")
    val otherPlan = spark.sql("select 1")
    assert(plan.sameSemantics(otherPlan))
  }

  test("sameSemantics and semanticHash") {
    val df1 = spark.createDataFrame(Seq((1, 2), (4, 5)))
    val df2 = spark.createDataFrame(Seq((1, 2), (4, 5)))
    val df3 = spark.createDataFrame(Seq((0, 2), (4, 5)))
    val df4 = spark.createDataFrame(Seq((0, 2), (4, 5)))

    assert(df1.sameSemantics(df2) === true)
    assert(df1.sameSemantics(df3) === false)
    assert(df3.sameSemantics(df4) === true)

    assert(df1.semanticHash === df2.semanticHash)
    assert(df1.semanticHash !== df3.semanticHash)
    assert(df3.semanticHash === df4.semanticHash)
  }

  test("toJSON") {
    val expected = Array(
      """{"b":0.0,"id":0,"d":"world","a":0}""",
      """{"b":0.1,"id":1,"d":"world","a":1}""",
      """{"b":0.2,"id":2,"d":"world","a":0}""")
    val result = spark
      .range(3)
      .select(generateMyTypeColumns: _*)
      .toJSON
      .collect()
    assert(result sameElements expected)
  }

  test("json from Dataset[String] inferSchema") {
    val session = spark
    import session.implicits._
    val expected = Seq(
      new GenericRowWithSchema(
        Array(73, "Shandong", "Kong"),
        new StructType().add("age", LongType).add("city", StringType).add("name", StringType)))
    val ds = Seq("""{"name":"Kong","age":73,"city":'Shandong'}""").toDS()
    val result = spark.read.option("allowSingleQuotes", "true").json(ds)
    checkSameResult(expected, result)
  }

  test("json from Dataset[String] with schema") {
    val session = spark
    import session.implicits._
    val schema = new StructType().add("city", StringType).add("name", StringType)
    val expected = Seq(new GenericRowWithSchema(Array("Shandong", "Kong"), schema))
    val ds = Seq("""{"name":"Kong","age":73,"city":'Shandong'}""").toDS()
    val result = spark.read.schema(schema).option("allowSingleQuotes", "true").json(ds)
    checkSameResult(expected, result)
  }

  test("json from Dataset[String] with invalid schema") {
    val message = intercept[ParseException] {
      spark.read.schema("123").json(spark.createDataset(Seq.empty[String])(StringEncoder))
    }.getMessage
    assert(message.contains("PARSE_SYNTAX_ERROR"))
  }

  test("csv from Dataset[String] inferSchema") {
    val session = spark
    import session.implicits._
    val expected = Seq(
      new GenericRowWithSchema(
        Array("Meng", 84, "Shandong"),
        new StructType().add("name", StringType).add("age", LongType).add("city", StringType)))
    val ds = Seq("name,age,city", """"Meng",84,"Shandong"""").toDS()
    val result = spark.read
      .option("header", "true")
      .option("inferSchema", "true")
      .csv(ds)
    checkSameResult(expected, result)
  }

  test("csv from Dataset[String] with schema") {
    val session = spark
    import session.implicits._
    val schema = new StructType().add("name", StringType).add("age", LongType)
    val expected = Seq(new GenericRowWithSchema(Array("Meng", 84), schema))
    val ds = Seq(""""Meng",84,"Shandong"""").toDS()
    val result = spark.read.schema(schema).csv(ds)
    checkSameResult(expected, result)
  }

  test("csv from Dataset[String] with invalid schema") {
    val message = intercept[ParseException] {
      spark.read.schema("123").csv(spark.createDataset(Seq.empty[String])(StringEncoder))
    }.getMessage
    assert(message.contains("PARSE_SYNTAX_ERROR"))
  }
}

private[sql] case class MyType(id: Long, a: Double, b: Double)
private[sql] case class KV(key: String, value: Int)
private[sql] class SimpleBean {
  @scala.beans.BeanProperty
  var value: String = _
}
