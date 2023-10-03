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
package org.apache.spark.sql.execution.datasources.xml

import java.nio.charset.{StandardCharsets, UnsupportedCharsetException}
import java.nio.file.{Files, Path, Paths}
import java.sql.{Date, Timestamp}
import java.util.TimeZone

import scala.collection.mutable
import scala.io.Source
import scala.jdk.CollectionConverters._

import org.apache.commons.lang3.exception.ExceptionUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.{LongWritable, Text}
import org.apache.hadoop.io.compress.GzipCodec

import org.apache.spark.SparkException
import org.apache.spark.sql.{AnalysisException, Encoders, QueryTest, Row, SaveMode}
import org.apache.spark.sql.catalyst.util._
import org.apache.spark.sql.catalyst.xml.XmlOptions
import org.apache.spark.sql.catalyst.xml.XmlOptions._
import org.apache.spark.sql.execution.datasources.xml.TestUtils._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._

class XmlSuite extends QueryTest with SharedSparkSession {
  import testImplicits._

  private val resDir = "test-data/xml-resources/"

  private var tempDir: Path = _

  protected override def sparkConf = super.sparkConf
    .set(SQLConf.SESSION_LOCAL_TIMEZONE.key, "UTC")

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    tempDir = Files.createTempDirectory("XmlSuite")
    tempDir.toFile.deleteOnExit()
  }

  private def getEmptyTempDir(): Path = {
    Files.createTempDirectory(tempDir, "test")
  }

  // Tests

  test("DSL test") {
    val results = spark.read.format("xml")
      .option("multiLine", "true")
      .load(getTestResourcePath(resDir + "cars.xml"))
      .select("year")
      .collect()

    assert(results.length === 3)
  }

  test("DSL test with xml having unbalanced datatypes") {
    val results = spark.read
      .option("treatEmptyValuesAsNulls", "true")
      .option("multiLine", "true")
      .xml(getTestResourcePath(resDir + "gps-empty-field.xml"))

    assert(results.collect().length === 2)
  }

  test("DSL test with mixed elements (attributes, no child)") {
    val results = spark.read
      .xml(getTestResourcePath(resDir + "cars-mixed-attr-no-child.xml"))
      .select("date")
      .collect()

    val attrValOne = results(0).getStruct(0).getString(1)
    val attrValTwo = results(1).getStruct(0).getString(1)
    assert(attrValOne == "string")
    assert(attrValTwo == "struct")
    assert(results.length === 3)
  }

  test("DSL test for inconsistent element attributes as fields") {
    val results = spark.read
      .option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "books-attributes-in-no-child.xml"))
      .select("price")

    // This should not throw an exception `java.lang.ArrayIndexOutOfBoundsException`
    // as non-existing values are represented as `null`s.
    val res = results.collect();
    assert(res(0).getStruct(0).get(1) === null)
  }

  test("DSL test with mixed elements (struct, string)") {
    val results = spark.read
      .option("rowTag", "person")
      .xml(getTestResourcePath(resDir + "ages-mixed-types.xml"))
      .collect()
    assert(results.length === 3)
  }

  test("DSL test with elements in array having attributes") {
    val results = spark.read
      .option("rowTag", "person")
      .xml(getTestResourcePath(resDir + "ages.xml"))
      .collect()
    val attrValOne = results(0).getStruct(0).getAs[Date](1)
    val attrValTwo = results(1).getStruct(0).getAs[Date](1)
    assert(attrValOne.toString === "1990-02-24")
    assert(attrValTwo.toString === "1985-01-01")
    assert(results.length === 3)
  }

  test("DSL test for iso-8859-1 encoded file") {
    val dataFrame = spark.read
      .option("charset", StandardCharsets.ISO_8859_1.name)
      .xml(getTestResourcePath(resDir + "cars-iso-8859-1.xml"))
    assert(dataFrame.select("year").collect().length === 3)

    val results = dataFrame
      .select("comment", "year")
      .where(dataFrame("year") === 2012)

    assert(results.head() === Row("No comment", 2012))
  }

  test("DSL test compressed file") {
    val results = spark.read
      .xml(getTestResourcePath(resDir + "cars.xml.gz"))
      .select("year")
      .collect()

    assert(results.length === 3)
  }

  test("DSL test splittable compressed file") {
    val results = spark.read
      .xml(getTestResourcePath(resDir + "cars.xml.bz2"))
      .select("year")
      .collect()

    assert(results.length === 3)
  }

  test("DSL test bad charset name") {
    // val exception = intercept[UnsupportedCharsetException] {
    val exception = intercept[SparkException] {
      spark.read
        .option("charset", "1-9588-osi")
        .xml(getTestResourcePath(resDir + "cars.xml"))
        .select("year")
        .collect()
    }
    ExceptionUtils.getRootCause(exception).isInstanceOf[UnsupportedCharsetException]
    assert(exception.getMessage.contains("1-9588-osi"))
  }

  test("DDL test") {
    spark.sql(s"""
         |CREATE TEMPORARY VIEW carsTable1
         |USING org.apache.spark.sql.execution.datasources.xml
         |OPTIONS (path "${getTestResourcePath(resDir + "cars.xml")}")
      """.stripMargin.replaceAll("\n", " "))

    assert(spark.sql("SELECT year FROM carsTable1").collect().length === 3)
  }

  test("DDL test with alias name") {
    spark.sql(s"""
         |CREATE TEMPORARY VIEW carsTable2
         |USING xml
         |OPTIONS (path "${getTestResourcePath(resDir + "cars.xml")}")
      """.stripMargin.replaceAll("\n", " "))

    assert(spark.sql("SELECT year FROM carsTable2").collect().length === 3)
  }

  test("DSL test for parsing a malformed XML file") {
    val results = spark.read
      .option("mode", DropMalformedMode.name)
      .xml(getTestResourcePath(resDir + "cars-malformed.xml"))

    assert(results.count() === 1)
  }

  test("DSL test for dropping malformed rows") {
    val cars = spark.read
      .option("mode", DropMalformedMode.name)
      .xml(getTestResourcePath(resDir + "cars-malformed.xml"))

    assert(cars.count() == 1)
    assert(cars.head() === Row("Chevy", "Volt", 2015))
  }

  test("DSL test for failing fast") {
    val exceptionInParse = intercept[SparkException] {
      spark.read
        .option("mode", FailFastMode.name)
        .xml(getTestResourcePath(resDir + "cars-malformed.xml"))
        .collect()
    }
    checkError(
      // TODO: Exception was nested two level deep as opposed to just one like json/csv
      exception = exceptionInParse.getCause.getCause.asInstanceOf[SparkException],
      errorClass = "MALFORMED_RECORD_IN_PARSING.WITHOUT_SUGGESTION",
      parameters = Map(
        "badRecord" -> "[null,null,null]",
        "failFastMode" -> FailFastMode.name)
    )
  }

  test("test FAILFAST with unclosed tag") {
    val exceptionInParse = intercept[SparkException] {
      spark.read
        .option("rowTag", "book")
        .option("mode", FailFastMode.name)
        .xml(getTestResourcePath(resDir + "unclosed_tag.xml"))
        .show()
    }
    checkError(
      // TODO: Exception was nested two level deep as opposed to just one like json/csv
      exception = exceptionInParse.getCause.getCause.asInstanceOf[SparkException],
      errorClass = "MALFORMED_RECORD_IN_PARSING.WITHOUT_SUGGESTION",
      parameters = Map(
        "badRecord" -> "[empty row]",
        "failFastMode" -> FailFastMode.name)
    )
  }

  test("DSL test for permissive mode for corrupt records") {
    val carsDf = spark.read
      .option("mode", PermissiveMode.name)
      .option("columnNameOfCorruptRecord", "_malformed_records")
      .xml(getTestResourcePath(resDir + "cars-malformed.xml"))
    val cars = carsDf.collect()
    assert(cars.length === 3)

    val malformedRowOne = carsDf.cache().select("_malformed_records").first().get(0).toString
    val malformedRowTwo = carsDf.cache().select("_malformed_records").take(2).last.get(0).toString
    val expectedMalformedRowOne = "<ROW><year>2012</year><make>Tesla</make><model>>S" +
      "<comment>No comment</comment></ROW>"
    val expectedMalformedRowTwo = "<ROW></year><make>Ford</make><model>E350</model>model></model>" +
      "<comment>Go get one now they are going fast</comment></ROW>"

    assert(malformedRowOne.replaceAll("\\s", "") === expectedMalformedRowOne.replaceAll("\\s", ""))
    assert(malformedRowTwo.replaceAll("\\s", "") === expectedMalformedRowTwo.replaceAll("\\s", ""))
    assert(cars(2)(0) === null)
    assert(cars(0).toSeq.takeRight(3) === Seq(null, null, null))
    assert(cars(1).toSeq.takeRight(3) === Seq(null, null, null))
    assert(cars(2).toSeq.takeRight(3) === Seq("Chevy", "Volt", 2015))
  }

  test("DSL test with empty file and known schema") {
    val results = spark.read
      .schema(buildSchema(field("column", StringType, false)))
      .xml(getTestResourcePath(resDir + "empty.xml"))
      .count()

    assert(results === 0)
  }

  test("DSL test with poorly formatted file and string schema") {
    val schema = buildSchema(
      field("color"),
      field("year"),
      field("make"),
      field("model"),
      field("comment"))
    val results = spark.read.schema(schema)
      .xml(getTestResourcePath(resDir + "cars-unbalanced-elements.xml"))
      .count()

    assert(results === 3)
  }

  test("DDL test with empty file") {
    spark.sql(s"""
           |CREATE TEMPORARY VIEW carsTable3
           |(year double, make string, model string, comments string, grp string)
           |USING org.apache.spark.sql.execution.datasources.xml
           |OPTIONS (path "${getTestResourcePath(resDir + "empty.xml")}")
      """.stripMargin.replaceAll("\n", " "))

    assert(spark.sql("SELECT count(*) FROM carsTable3").collect().head(0) === 0)
  }

  test("SQL test insert overwrite") {
    val tempPath = getEmptyTempDir()
    spark.sql(s"""
         |CREATE TEMPORARY VIEW booksTableIO
         |USING org.apache.spark.sql.execution.datasources.xml
         |OPTIONS (path "${getTestResourcePath(resDir + "books.xml")}", rowTag "book")
      """.stripMargin.replaceAll("\n", " "))
    spark.sql(s"""
         |CREATE TEMPORARY VIEW booksTableEmpty
         |(author string, description string, genre string,
         |id string, price double, publish_date string, title string)
         |USING org.apache.spark.sql.execution.datasources.xml
         |OPTIONS (path "$tempPath")
      """.stripMargin.replaceAll("\n", " "))

    assert(spark.sql("SELECT * FROM booksTableIO").collect().length === 12)
    assert(spark.sql("SELECT * FROM booksTableEmpty").collect().isEmpty)

    spark.sql(
      s"""
         |INSERT OVERWRITE TABLE booksTableEmpty
         |SELECT * FROM booksTableIO
      """.stripMargin.replaceAll("\n", " "))
    assert(spark.sql("SELECT * FROM booksTableEmpty").collect().length == 12)
  }

  test("DSL save with gzip compression codec") {
    val copyFilePath = getEmptyTempDir().resolve("cars-copy.xml")

    val cars = spark.read.xml(getTestResourcePath(resDir + "cars.xml"))
    cars.write
      .mode(SaveMode.Overwrite)
      .options(Map("codec" -> classOf[GzipCodec].getName))
      .xml(copyFilePath.toString)
    // Check that the part file has a .gz extension
    assert(Files.list(copyFilePath).iterator().asScala
      .count(_.getFileName.toString().endsWith(".xml.gz")) === 1)

    val carsCopy = spark.read.xml(copyFilePath.toString)

    assert(carsCopy.count() === cars.count())
    assert(carsCopy.collect().map(_.toString).toSet === cars.collect().map(_.toString).toSet)
  }

  test("DSL save with gzip compression codec by shorten name") {
    val copyFilePath = getEmptyTempDir().resolve("cars-copy.xml")

    val cars = spark.read.xml(getTestResourcePath(resDir + "cars.xml"))
    cars.write
      .mode(SaveMode.Overwrite)
      .options(Map("compression" -> "gZiP"))
      .xml(copyFilePath.toString)

    // Check that the part file has a .gz extension
    assert(Files.list(copyFilePath).iterator().asScala
      .count(_.getFileName.toString().endsWith(".xml.gz")) === 1)

    val carsCopy = spark.read.xml(copyFilePath.toString)

    assert(carsCopy.count() === cars.count())
    assert(carsCopy.collect().map(_.toString).toSet === cars.collect().map(_.toString).toSet)
  }

  test("DSL save") {
    val copyFilePath = getEmptyTempDir().resolve("books-copy.xml")

    val books = spark.read
      .option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "books-complicated.xml"))
    books.write
      .options(Map("rootTag" -> "books", "rowTag" -> "book"))
      .xml(copyFilePath.toString)

    val booksCopy = spark.read
      .option("rowTag", "book")
      .xml(copyFilePath.toString)
    assert(booksCopy.count() === books.count())
    assert(booksCopy.collect().map(_.toString).toSet === books.collect().map(_.toString).toSet)
  }

  test("DSL save with declaration") {
    val copyFilePath1 = getEmptyTempDir().resolve("books-copy.xml")

    val books = spark.read
      .option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "books-complicated.xml"))

    books.write
      .options(Map("rootTag" -> "books", "rowTag" -> "book", "declaration" -> ""))
      .xml(copyFilePath1.toString)

    val xmlFile1 =
      Files.list(copyFilePath1).iterator.asScala
        .filter(_.getFileName.toString.startsWith("part-")).next()
    val firstLine = getLines(xmlFile1).head
    assert(firstLine === "<books>")

    val copyFilePath2 = getEmptyTempDir().resolve("books-copy.xml")

    books.write
      .options(Map("rootTag" -> "books", "rowTag" -> "book"))
      .xml(copyFilePath2.toString)

    val xmlFile2 =
      Files.list(copyFilePath2).iterator.asScala
        .filter(_.getFileName.toString.startsWith("part-")).next()
    assert(getLines(xmlFile2).head ===
      "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
  }

  test("DSL save with item") {
    val tempPath = getEmptyTempDir().resolve("items-temp.xml")
    val items = spark.createDataFrame(Seq(Tuple1(Array(Array(3, 4))))).toDF("thing").repartition(1)
    items.write.option("arrayElementName", "foo").xml(tempPath.toString)

    val xmlFile =
      Files.list(tempPath).iterator.asScala
        .filter(_.getFileName.toString.startsWith("part-")).next()
    assert(getLines(xmlFile).count(_.contains("<foo>")) === 2)
  }

  test("DSL save with nullValue and treatEmptyValuesAsNulls") {
    val copyFilePath = getEmptyTempDir().resolve("books-copy.xml")

    val books = spark.read
      .option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "books-complicated.xml"))
    books.write
      .options(Map("rootTag" -> "books", "rowTag" -> "book", "nullValue" -> ""))
      .xml(copyFilePath.toString)

    val booksCopy = spark.read
      .option("rowTag", "book")
      .option("treatEmptyValuesAsNulls", "true")
      .xml(copyFilePath.toString)

    assert(booksCopy.count() === books.count())
    assert(booksCopy.collect().map(_.toString).toSet === books.collect().map(_.toString).toSet)
  }

  test("Write values properly as given to valueTag even if it starts with attributePrefix") {
    val copyFilePath = getEmptyTempDir().resolve("books-copy.xml")

    val rootTag = "catalog"
    val books = spark.read
      .option("valueTag", "#VALUE")
      .option("attributePrefix", "#")
      .option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "books-attributes-in-no-child.xml"))

    books.write
      .option("valueTag", "#VALUE")
      .option("attributePrefix", "#")
      .option("rootTag", rootTag)
      .option("rowTag", "book")
      .xml(copyFilePath.toString)

    val booksCopy = spark.read
      .option("valueTag", "#VALUE")
      .option("attributePrefix", "_")
      .option("rowTag", "book")
      .xml(copyFilePath.toString)

    assert(booksCopy.count() === books.count())
    assert(booksCopy.collect().map(_.toString).toSet === books.collect().map(_.toString).toSet)
  }

  test("DSL save dataframe not read from a XML file") {
    val copyFilePath = getEmptyTempDir().resolve("data-copy.xml")

    val schema = buildSchema(arrayField("a", ArrayType(StringType)))
    val data = spark.sparkContext.parallelize(
      List(List(List("aa", "bb"), List("aa", "bb"))).map(Row(_)))
    val df = spark.createDataFrame(data, schema)
    df.write.xml(copyFilePath.toString)

    // When [[ArrayType]] has [[ArrayType]] as elements, it is confusing what is the element
    // name for XML file. Now, it is "item" by default. So, "item" field is additionally added
    // to wrap the element.
    val schemaCopy = buildSchema(
      structArray("a",
        field(XmlOptions.DEFAULT_ARRAY_ELEMENT_NAME, ArrayType(StringType))))
    val dfCopy = spark.read.xml(copyFilePath.toString)

    assert(dfCopy.count() === df.count())
    assert(dfCopy.schema === schemaCopy)
  }

  test("DSL save dataframe with data types correctly") {
    val copyFilePath = getEmptyTempDir().resolve("data-copy.xml")

    // Create the schema.
    val dataTypes = Array(
        StringType, NullType, BooleanType,
        ByteType, ShortType, IntegerType, LongType,
        FloatType, DoubleType, DecimalType(25, 3), DecimalType(6, 5),
        DateType, TimestampType, MapType(StringType, StringType))
    val fields = dataTypes.zipWithIndex.map { case (dataType, index) =>
      field(s"col$index", dataType)
    }
    val schema = StructType(fields)

    val currentTZ = TimeZone.getDefault
    try {
      // Tests will depend on default timezone, so set it to UTC temporarily
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
      // Create the data
      val timestamp = "2015-01-01 00:00:00"
      val date = "2015-01-01"
      val row =
        Row(
          "aa", null, true,
          1.toByte, 1.toShort, 1, 1.toLong,
          1.toFloat, 1.toDouble, Decimal(1, 25, 3), Decimal(1, 6, 5),
          Date.valueOf(date), Timestamp.valueOf(timestamp), Map("a" -> "b"))
      val data = spark.sparkContext.parallelize(Seq(row))

      val df = spark.createDataFrame(data, schema)
      df.write.xml(copyFilePath.toString)

      val dfCopy = spark.read.schema(schema)
        .xml(copyFilePath.toString)

      assert(dfCopy.collect() === df.collect())
      assert(dfCopy.schema === df.schema)
    } finally {
      TimeZone.setDefault(currentTZ)
    }
  }

  test("DSL test schema inferred correctly") {
    val results = spark.read.option("rowTag", "book").xml(getTestResourcePath(resDir + "books.xml"))

    assert(results.schema === buildSchema(
      field(s"${DEFAULT_ATTRIBUTE_PREFIX}id"),
      field("author"),
      field("description"),
      field("genre"),
      field("price", DoubleType),
      field("publish_date", DateType),
      field("title")))

    assert(results.collect().length === 12)
  }

  test("DSL test schema inferred correctly with sampling ratio") {
    val results = spark.read
      .option("rowTag", "book")
      .option("samplingRatio", 0.5)
      .xml(getTestResourcePath(resDir + "books.xml"))

    assert(results.schema === buildSchema(
      field(s"${DEFAULT_ATTRIBUTE_PREFIX}id"),
      field("author"),
      field("description"),
      field("genre"),
      field("price", DoubleType),
      field("publish_date", DateType),
      field("title")))

    assert(results.collect().length === 12)
  }

  test("DSL test schema (object) inferred correctly") {
    val results = spark.read
      .option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "books-nested-object.xml"))

    assert(results.schema === buildSchema(
      field(s"${DEFAULT_ATTRIBUTE_PREFIX}id"),
      field("author"),
      field("description"),
      field("genre"),
      field("price", DoubleType),
      structField("publish_dates",
        field("publish_date", DateType)),
      field("title")))

    assert(results.collect().length === 12)
  }

  test("DSL test schema (array) inferred correctly") {
    val results = spark.read
      .option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "books-nested-array.xml"))

    assert(results.schema === buildSchema(
      field(s"${DEFAULT_ATTRIBUTE_PREFIX}id"),
      field("author"),
      field("description"),
      field("genre"),
      field("price", DoubleType),
      arrayField("publish_date", DateType),
      field("title")))

    assert(results.collect().length === 12)
  }

  test("DSL test schema (complicated) inferred correctly") {
    val results = spark.read
      .option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "books-complicated.xml"))

    assert(results.schema == buildSchema(
      field(s"${DEFAULT_ATTRIBUTE_PREFIX}id"),
      field("author"),
      structField("genre",
        field("genreid", LongType),
        field("name")),
      field("price", DoubleType),
      structField("publish_dates",
        arrayField("publish_date",
          structField(
            field(s"${DEFAULT_ATTRIBUTE_PREFIX}tag"),
            field("day", LongType),
            field("month", LongType),
            field("year", LongType)))),
      field("title")))

    assert(results.collect().length === 3)
  }

  test("DSL test parsing and inferring attribute in elements having no child element") {
    // Default value.
    val resultsOne = spark.read.option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "books-attributes-in-no-child.xml"))

    val schemaOne = buildSchema(
      field("_id"),
      field("author"),
      structField("price",
        field("_VALUE"),
        field(s"_unit")),
      field("publish_date", DateType),
      field("title"))

    assert(resultsOne.schema === schemaOne)
    assert(resultsOne.count() === 12)

    // Explicitly set
    val attributePrefix = "@#"
    val valueTag = "#@@value"
    val resultsTwo = spark.read
      .option("rowTag", "book")
      .option("attributePrefix", attributePrefix)
      .option("valueTag", valueTag)
      .xml(getTestResourcePath(resDir + "books-attributes-in-no-child.xml"))

    val schemaTwo = buildSchema(
      field(s"${attributePrefix}id"),
      field("author"),
      structField("price",
        field(valueTag),
        field(s"${attributePrefix}unit")),
      field("publish_date", DateType),
      field("title"))

    assert(resultsTwo.schema === schemaTwo)
    assert(resultsTwo.count() === 12)
  }

  test("DSL test schema (excluding tags) inferred correctly") {
    val results = spark.read
      .option("excludeAttribute", true)
      .option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "books.xml"))

    val schema = buildSchema(
      field("author"),
      field("description"),
      field("genre"),
      field("price", DoubleType),
      field("publish_date", DateType),
      field("title"))

    assert(results.schema === schema)
  }

  test("DSL test with custom schema") {
    val schema = buildSchema(
      field("make"),
      field("model"),
      field("comment"),
      field("color"),
      field("year", IntegerType))
    val results = spark.read.schema(schema)
      .xml(getTestResourcePath(resDir + "cars-unbalanced-elements.xml"))
      .count()

    assert(results === 3)
  }

  test("DSL test inferred schema passed through") {
    val dataFrame = spark.read.xml(getTestResourcePath(resDir + "cars.xml"))

    val results = dataFrame
      .select("comment", "year")
      .where(dataFrame("year") === 2012)

    assert(results.head() === Row("No comment", 2012))
  }

  test("DSL test nullable fields") {
    val schema = buildSchema(
      field("name", StringType, false),
      field("age"))
    val results = spark.read.schema(schema)
      .xml(getTestResourcePath(resDir + "null-numbers.xml"))
      .select("name", "age")
      .collect()

    assert(results(0) === Row("alice", "35"))
    assert(results(1) === Row("bob", "    "))
    assert(results(2) === Row("coc", "24"))
  }

  test("DSL test for treating empty string as null value") {
    val schema = buildSchema(
      field("name", StringType, false),
      field("age", IntegerType))
    val results = spark.read.schema(schema)
      .option("treatEmptyValuesAsNulls", true)
      .xml(getTestResourcePath(resDir + "null-numbers.xml"))
      .select("name", "age")
      .collect()

    assert(results(1) === Row("bob", null))
  }

  test("DSL test with namespaces ignored") {
    val results = spark.read
      .option("rowTag", "Topic")
      .xml(getTestResourcePath(resDir + "topics-namespaces.xml"))
      .collect()

    assert(results.length === 1)
  }

  test("xs_any array matches single element") {
    val schema = buildSchema(
      field(s"${DEFAULT_ATTRIBUTE_PREFIX}id"),
      field("author"),
      field("description"),
      field("genre"),
      field("price", DoubleType),
      field("publish_date"),
      field("xs_any"))
    val results = spark.read.schema(schema).option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "books.xml"))
      // .select("xs_any")
      .collect()
    results.foreach { r =>
      assert(r.getString(0) != null)
    }
  }

  test("xs_any array matches multiple elements") {
    val schema = buildSchema(
      field(s"${DEFAULT_ATTRIBUTE_PREFIX}id"),
      field("author"),
      field("description"),
      field("genre"),
      arrayField("xs_any", StringType))
    val results = spark.read.schema(schema).option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "books.xml"))
      .collect()
    results.foreach { r =>
      assert(r.getAs[Seq[String]]("xs_any").size === 3)
    }
  }

  test("Missing nested struct represented as Row of nulls instead of null") {
    val result = spark.read
      .option("rowTag", "item")
      .xml(getTestResourcePath(resDir + "null-nested-struct.xml"))
      .select("b.es")
      .collect()

    assert(result(1).getStruct(0) !== null)
    assert(result(1).getStruct(0)(0) === null)
  }

  test("Produces correct result for empty vs non-existent rows") {
    val schema = buildSchema(
      structField("b",
        structField("es",
          field("e"),
          field("f"))))
    val result = spark.read
      .option("rowTag", "item")
      .schema(schema)
      .xml(getTestResourcePath(resDir + "null-nested-struct-2.xml"))
      .collect()

    assert(result(0) === Row(Row(null)))
    assert(result(1) === Row(Row(Row(null, null))))
    assert(result(2) === Row(Row(Row("E", null))))
    assert(result(3) === Row(Row(Row("E", " "))))
    assert(result(4) === Row(Row(Row("E", ""))))
  }

  test("Produces correct order of columns for nested rows when user specifies a schema") {
    val schema = buildSchema(
      structField("c",
        field("b", IntegerType),
        field("a", IntegerType)))

    val result = spark.read.schema(schema)
      .xml(getTestResourcePath(resDir + "simple-nested-objects.xml"))
      .select("c.a", "c.b")
      .collect()

    assert(result(0) === Row(111, 222))
  }

  private[this] def testNextedElementFromFile(xmlFile: String): Unit = {
    val lines = getLines(Paths.get(xmlFile.replace("file:/", "/"))).toList
    val firstExpected = lines(2).trim
    val lastExpected = lines(3).trim
    val config = new Configuration(spark.sessionState.newHadoopConf())
    config.set(XmlInputFormat.START_TAG_KEY, "<parent>")
    config.set(XmlInputFormat.END_TAG_KEY, "</parent>")
    val records = spark.sparkContext.newAPIHadoopFile(
      xmlFile,
      classOf[XmlInputFormat],
      classOf[LongWritable],
      classOf[Text],
      config)
    val list = records.values.map(_.toString).collect().toList
    assert(list.length === 2)
    val firstActual = list.head
    val lastActual = list.last
    assert(firstActual === firstExpected)
    assert(lastActual === lastExpected)
  }

  test("Nested element with same name as parent delineation") {
    testNextedElementFromFile(getTestResourcePath(resDir +
      "nested-element-with-name-of-parent.xml"))
  }

  test("Nested element including attribute with same name as parent delineation") {
    testNextedElementFromFile(getTestResourcePath(resDir +
      "nested-element-with-attributes-and-name-of-parent.xml"))
  }

  test("Nested element with same name as parent schema inference") {
    val df = spark.read.option("rowTag", "parent")
      .xml(getTestResourcePath(resDir + "nested-element-with-name-of-parent.xml"))

    val schema = buildSchema(
      field("child"),
      structField("parent",
        field("child")))
    assert(df.schema === schema)
  }

  test("Skip and project currently XML files without indentation") {
    val df = spark.read.xml(getTestResourcePath(resDir + "cars-no-indentation.xml"))
    val results = df.select("model").collect()
    val years = results.map(_(0)).toSet
    assert(years === Set("S", "E350", "Volt"))
  }

  test("Select correctly all child fields regardless of pushed down projection") {
    val results = spark.read
      .option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "books-complicated.xml"))
      .selectExpr("publish_dates")
      .collect()
    results.foreach { row =>
      // All nested fields should not have nulls but arrays.
      assert(!row.anyNull)
    }
  }

  test("Empty string not allowed for rowTag, attributePrefix and valueTag.") {
    val messageOne = intercept[IllegalArgumentException] {
      spark.read.option("rowTag", "").xml(getTestResourcePath(resDir + "cars.xml"))
    }.getMessage
    assert(messageOne === "requirement failed: 'rowTag' option should not be empty string.")

    val messageThree = intercept[IllegalArgumentException] {
      spark.read.option("valueTag", "").xml(getTestResourcePath(resDir + "cars.xml"))
    }.getMessage
    assert(messageThree === "requirement failed: 'valueTag' option should not be empty string.")
  }

  test("'rowTag' and 'rootTag' should not include angle brackets") {
    val messageOne = intercept[IllegalArgumentException] {
      spark.read.option("rowTag", "ROW>").xml(getTestResourcePath(resDir + "cars.xml"))
    }.getMessage
    assert(messageOne === "requirement failed: 'rowTag' should not include angle brackets")

    val messageTwo = intercept[IllegalArgumentException] {
            spark.read.option("rowTag", "<ROW").xml(getTestResourcePath(resDir + "cars.xml"))
    }.getMessage
    assert(
      messageTwo === "requirement failed: 'rowTag' should not include angle brackets")

    val messageThree = intercept[IllegalArgumentException] {
      spark.read.option("rootTag", "ROWSET>").xml(getTestResourcePath(resDir + "cars.xml"))
    }.getMessage
    assert(messageThree === "requirement failed: 'rootTag' should not include angle brackets")

    val messageFour = intercept[IllegalArgumentException] {
      spark.read.option("rootTag", "<ROWSET").xml(getTestResourcePath(resDir + "cars.xml"))
    }.getMessage
    assert(messageFour === "requirement failed: 'rootTag' should not include angle brackets")
  }

  test("valueTag and attributePrefix should not be the same.") {
    val messageOne = intercept[IllegalArgumentException] {
      spark.read
        .option("valueTag", "#abc")
        .option("attributePrefix", "#abc")
        .xml(getTestResourcePath(resDir + "cars.xml"))
    }.getMessage
    assert(messageOne ===
      "requirement failed: 'valueTag' and 'attributePrefix' options should not be the same.")
  }

  test("nullValue and treatEmptyValuesAsNulls test") {
    val resultsOne = spark.read
      .option("treatEmptyValuesAsNulls", "true")
      .xml(getTestResourcePath(resDir + "gps-empty-field.xml"))
    assert(resultsOne.selectExpr("extensions.TrackPointExtension").head().getStruct(0) !== null)
    assert(resultsOne.selectExpr("extensions.TrackPointExtension")
      .head().getStruct(0)(0) === null)
    // Is the behavior below consistent? see line above.
    assert(resultsOne.selectExpr("extensions.TrackPointExtension.hr").head().getStruct(0) === null)
    assert(resultsOne.collect().length === 2)

    val resultsTwo = spark.read
      .option("nullValue", "2013-01-24T06:18:43Z")
      .xml(getTestResourcePath(resDir + "gps-empty-field.xml"))
    assert(resultsTwo.selectExpr("time").head().getStruct(0) === null)
    assert(resultsTwo.collect().length === 2)
  }

  test("ignoreSurroundingSpace with string types") {
    val df = spark.read
      .option("inferSchema", true)
      .option("rowTag", "entry")
      .option("ignoreSurroundingSpaces", true)
      .xml(getTestResourcePath(resDir + "feed-with-spaces.xml"))
    val results = df.collect().map(_.getString(0))
    assert(results === Array("A", "B", "C", "D"))
  }

  test("ignoreSurroundingSpaces with non-string types") {
    val results = spark.read
      .option("ignoreSurroundingSpaces", true)
      .option("rowTag", "person")
      .xml(getTestResourcePath(resDir + "ages-with-spaces.xml"))
      .collect()
    val attrValOne = results(0).getStruct(0)(1)
    val attrValTwo = results(1).getStruct(0)(0)
    assert(attrValOne.toString === "1990-02-24")
    assert(attrValTwo === 30)
    assert(results.length === 3)
  }

  test("DSL test with malformed attributes") {
    val results = spark.read
      .option("mode", DropMalformedMode.name)
      .option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "books-malformed-attributes.xml"))
      .collect()

    assert(results.length === 2)
    assert(results(0)(0) === "bk111")
    assert(results(1)(0) === "bk112")
  }

  test("read utf-8 encoded file with empty tag") {
    val df = spark.read
      .option("excludeAttribute", "false")
      .option("rowTag", "House")
      .xml(getTestResourcePath(resDir + "fias_house.xml"))

    assert(df.collect().length === 37)
    assert(df.select().where("_HOUSEID is null").count() == 0)
  }

  test("attributes start with new line") {
    val schema = buildSchema(
      field("_schemaLocation"),
      field("_xmlns"),
      field("_xsi"),
      field("body"),
      field("from"),
      field("heading"),
      field("to"))

    val rowsCount = 1

    Seq("attributesStartWithNewLine.xml",
        "attributesStartWithNewLineCR.xml",
        "attributesStartWithNewLineLF.xml").foreach { file =>
      val df = spark.read
        .option("ignoreNamespace", "true")
        .option("excludeAttribute", "false")
        .option("rowTag", "note")
        .xml(getTestResourcePath(resDir + file))
      assert(df.schema === schema)
      assert(df.count() === rowsCount)
    }
  }

  test("Produces correct result for a row with a self closing tag inside") {
    val schema = buildSchema(
      field("non-empty-tag", IntegerType),
      field("self-closing-tag", IntegerType))

    val result = spark.read.schema(schema)
      .xml(getTestResourcePath(resDir + "self-closing-tag.xml"))
      .collect()

    assert(result(0) === Row(1, null))
  }

  test("DSL save with null attributes") {
    val copyFilePath = getEmptyTempDir().resolve("books-copy.xml")

    val books = spark.read
      .option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "books-complicated-null-attribute.xml"))
    books.write
      .options(Map("rootTag" -> "books", "rowTag" -> "book"))
      .xml(copyFilePath.toString)

    val booksCopy = spark.read
      .option("rowTag", "book")
      .xml(copyFilePath.toString)
    assert(booksCopy.count() === books.count())
    assert(booksCopy.collect().map(_.toString).toSet === books.collect().map(_.toString).toSet)
  }

  test("DSL test nulls out invalid values when set to permissive and given explicit schema") {
    val schema = buildSchema(
      structField("integer_value",
        field("_VALUE", IntegerType),
        field("_int", IntegerType)),
      structField("long_value",
        field("_VALUE", LongType),
        field("_int", StringType)),
      field("float_value", FloatType),
      field("double_value", DoubleType),
      field("boolean_value", BooleanType),
      field("string_value"), arrayField("integer_array", IntegerType),
      field("integer_map", MapType(StringType, IntegerType)),
      field("_malformed_records", StringType))
    val results = spark.read
      .option("mode", "PERMISSIVE")
      .option("columnNameOfCorruptRecord", "_malformed_records")
      .schema(schema)
      .xml(getTestResourcePath(resDir + "datatypes-valid-and-invalid.xml"))

    assert(results.schema === schema)

    val Array(valid, invalid) = results.take(2)

    assert(valid.toSeq.toArray.take(schema.length - 1) ===
      Array(Row(10, 10), Row(10, "Ten"), 10.0, 10.0, true,
        "Ten", Array(1, 2), Map("a" -> 123, "b" -> 345)))
    assert(invalid.toSeq.toArray.take(schema.length - 1) ===
      Array(null, null, null, null, null,
        "Ten", Array(2), null))

    assert(valid.toSeq.toArray.last === null)
    assert(invalid.toSeq.toArray.last.toString.contains(
      <integer_value int="Ten">Ten</integer_value>.toString))
  }

  test("empty string to null and back") {
    val fruit = spark.read
      .option("rowTag", "row")
      .option("nullValue", "")
      .xml(getTestResourcePath(resDir + "null-empty-string.xml"))
    assert(fruit.head().getAs[String]("color") === null)
  }

  test("test all string data type infer strategy") {
    val text = spark.read
      .option("rowTag", "ROW")
      .option("inferSchema", "false")
      .xml(getTestResourcePath(resDir + "textColumn.xml"))
    assert(text.head().getAs[String]("col1") === "00010")

  }

  test("test default data type infer strategy") {
    val default = spark.read
      .option("rowTag", "ROW")
      .option("inferSchema", "true")
      .xml(getTestResourcePath(resDir + "textColumn.xml"))
    assert(default.head().getAs[Int]("col1") === 10)
  }

  test("test XML with processing instruction") {
    val processingDF = spark.read
      .option("rowTag", "foo")
      .option("inferSchema", "true")
      .xml(getTestResourcePath(resDir + "processing.xml"))
    assert(processingDF.count() === 1)
  }

  test("test mixed text and element children") {
    val mixedDF = spark.read
      .option("rowTag", "root")
      .option("inferSchema", true)
      .xml(getTestResourcePath(resDir + "mixed_children.xml"))
    val mixedRow = mixedDF.head()
    assert(mixedRow.getAs[Row](0).toSeq === Seq(" lorem "))
    assert(mixedRow.getString(1) === " ipsum ")
  }

  test("test mixed text and complex element children") {
    val mixedDF = spark.read
      .option("rowTag", "root")
      .option("inferSchema", true)
      .xml(getTestResourcePath(resDir + "mixed_children_2.xml"))
    assert(mixedDF.select("foo.bar").head().getString(0) === " lorem ")
    assert(mixedDF.select("foo.baz.bing").head().getLong(0) === 2)
    assert(mixedDF.select("missing").head().getString(0) === " ipsum ")
  }

  test("test XSD validation") {
    val basketDF = spark.read
      .option("rowTag", "basket")
      .option("inferSchema", true)
      .option("rowValidationXSDPath", getTestResourcePath(resDir + "basket.xsd")
        .replace("file:/", "/"))
      .xml(getTestResourcePath(resDir + "basket.xml"))
    // Mostly checking it doesn't fail
    assert(basketDF.selectExpr("entry[0].key").head().getLong(0) === 9027)
  }

  test("test XSD validation with validation error") {
    val basketDF = spark.read
      .option("rowTag", "basket")
      .option("inferSchema", true)
      .option("rowValidationXSDPath", getTestResourcePath(resDir + "basket.xsd")
        .replace("file:/", "/"))
      .option("mode", "PERMISSIVE")
      .option("columnNameOfCorruptRecord", "_malformed_records")
      .xml(getTestResourcePath(resDir + "basket_invalid.xml")).cache()
    assert(basketDF.filter($"_malformed_records".isNotNull).count() == 1)
    assert(basketDF.filter($"_malformed_records".isNull).count() == 1)
    val rec = basketDF.select("_malformed_records").collect()(1).getString(0)
    assert(rec.startsWith("<basket>") && rec.indexOf("<extra>123</extra>") != -1 &&
      rec.endsWith("</basket>"))
  }

  test("test XSD validation with addFile() with validation error") {
    spark.sparkContext.addFile(getTestResourcePath(resDir + "basket.xsd"))
    val basketDF = spark.read
      .option("rowTag", "basket")
      .option("inferSchema", true)
      .option("rowValidationXSDPath", "basket.xsd")
      .option("mode", "PERMISSIVE")
      .option("columnNameOfCorruptRecord", "_malformed_records")
      .xml(getTestResourcePath(resDir + "basket_invalid.xml")).cache()
    assert(basketDF.filter($"_malformed_records".isNotNull).count() == 1)
    assert(basketDF.filter($"_malformed_records".isNull).count() == 1)
    val rec = basketDF.select("_malformed_records").collect()(1).getString(0)
    assert(rec.startsWith("<basket>") && rec.indexOf("<extra>123</extra>") != -1 &&
      rec.endsWith("</basket>"))
  }

  test("test xmlDataset") {
    val data = Seq(
      "<ROW><year>2012</year><make>Tesla</make><model>S</model><comment>No comment</comment></ROW>",
      "<ROW><year>1997</year><make>Ford</make><model>E350</model><comment>Get one</comment></ROW>",
      "<ROW><year>2015</year><make>Chevy</make><model>Volt</model><comment>No</comment></ROW>")
    val xmlRDD = spark.sparkContext.parallelize(data)
    val ds = spark.createDataset(xmlRDD)(Encoders.STRING)
    assert(spark.read.xml(ds).collect().length === 3)
  }

  import testImplicits._
  test("from_xml basic test") {
    val xmlData =
      """<parent foo="bar"><pid>14ft3</pid>
        |  <name>dave guy</name>
        |</parent>
       """.stripMargin
    val df = Seq((8, xmlData)).toDF("number", "payload")
    val xmlSchema = schema_of_xml(xmlData)
    val schema = buildSchema(
      field("_foo", StringType),
      field("name", StringType),
      field("pid", StringType))
    val expectedSchema = df.schema.add("decoded", schema)
    val result = df.withColumn("decoded",
      from_xml(df.col("payload"), xmlSchema, Map[String, String]().asJava))

    assert(expectedSchema === result.schema)
    assert(result.select("decoded.pid").head().getString(0) === "14ft3")
    assert(result.select("decoded._foo").head().getString(0) === "bar")
  }

  /*
  test("from_xml array basic test") {
    val xmlData =
      """<parent><pid>12345</pid><name>dave guy</name></parent>
        |<parent><pid>67890</pid><name>other guy</name></parent>""".stripMargin
    val df = Seq((8, xmlData)).toDF("number", "payload")
    val xmlSchema = ArrayType(
      StructType(
        StructField("pid", IntegerType) ::
          StructField("name", StringType) :: Nil))
    val expectedSchema = df.schema.add("decoded", xmlSchema)
    val result = df.withColumn("decoded",
      from_xml(df.col("payload"), xmlSchema))
    assert(expectedSchema === result.schema)
    // TODO: ArrayType and MapType support in from_xml
    // assert(result.selectExpr("decoded[0].pid").head().getInt(0) === 12345)
    // assert(result.selectExpr("decoded[1].pid").head().getInt(1) === 67890)
  }
  */

  test("from_xml error test") {
    // XML contains error
    val xmlData =
      """<parent foo="bar"><pid>14ft3
        |  <name>dave guy</name>
        |</parent>
       """.stripMargin
    val df = spark.createDataFrame(Seq((8, xmlData))).toDF("number", "payload")
    val xmlSchema = schema_of_xml(xmlData)
    val result = df.withColumn("decoded",
      from_xml(df.col("payload"), xmlSchema, Map[String, String]().asJava))
    assert(result.select("decoded._corrupt_record").head().getString(0).nonEmpty)
  }

  test("from_xml with PERMISSIVE parse mode with no corrupt col schema") {
    // XML contains error
    val xmlData =
      """<parent foo="bar"><pid>14ft3
        |  <name>dave guy</name>
        |</parent>
       """.stripMargin
    val xmlDataNoError =
      """<parent foo="bar">
        |  <name>dave guy</name>
        |</parent>
       """.stripMargin
    val dfNoError = spark.createDataFrame(Seq((8, xmlDataNoError))).toDF("number", "payload")
    val xmlSchema = schema_of_xml(xmlDataNoError)
    val df = spark.createDataFrame(Seq((8, xmlData))).toDF("number", "payload")
    val result = df.withColumn("decoded",
      from_xml(df.col("payload"), xmlSchema, Map[String, String]().asJava))
    assert(result.select("decoded").head().get(0) === Row(null, null))
  }

  test("decimals with scale greater than precision") {
    val spark = this.spark;
    import spark.implicits._
    val schema = buildSchema(field("Number", DecimalType(7, 4)))
    val outputDF = Seq("0.0000", "0.01")
      .map { n => s"<Row> <Number>$n</Number> </Row>" }
      .toDF("xml")
      .withColumn("parsed", from_xml($"xml", schema, Map("rowTag" -> "Row").asJava))
      .select("parsed.Number")

    val results = outputDF.collect()
    assert(results(0).getAs[java.math.BigDecimal](0).toString === "0.0000")
    assert(results(1).getAs[java.math.BigDecimal](0).toString === "0.0100")
  }

  test("double field encounters whitespace-only value") {
    val schema = buildSchema(structField("Book", field("Price", DoubleType)),
      field("_corrupt_record"))
    val whitespaceDF = spark.read
      .option("rowTag", "Books")
      .schema(schema)
      .xml(getTestResourcePath(resDir + "whitespace_error.xml"))

    assert(whitespaceDF.count() === 1)
    assert(whitespaceDF.take(1).head.getAs[String]("_corrupt_record") !== null)
  }

  test("struct with only attributes and no value tag does not crash") {
    val schema = buildSchema(structField("book", field("_id", StringType)),
      field("_corrupt_record"))
    val booksDF = spark.read
      .option("rowTag", "book")
      .schema(schema)
      .xml(getTestResourcePath(resDir + "books.xml"))

    assert(booksDF.count() === 12)
  }

  test("XML in String field preserves attributes") {
    val schema = buildSchema(field("ROW"))
    val result = spark.read
      .option("rowTag", "ROWSET")
      .schema(schema)
      .xml(getTestResourcePath(resDir + "cars-attribute.xml"))
      .collect()
    assert(result.head.getString(0).contains("<comment foo=\"bar\">No</comment>"))
  }

  test("rootTag with simple attributes") {
    val xmlPath = getEmptyTempDir().resolve("simple_attributes")
    val df = spark.createDataFrame(Seq((42, "foo"))).toDF("number", "value").repartition(1)
    df.write.
      option("rootTag", "root foo='bar' bing=\"baz\"").
      option("declaration", "").
      xml(xmlPath.toString)

    val xmlFile =
      Files.list(xmlPath).iterator.asScala.filter(_.getFileName.toString.startsWith("part-")).next()
    val firstLine = getLines(xmlFile).head
    assert(firstLine === "<root foo=\"bar\" bing=\"baz\">")
  }

  test("test ignoreNamespace") {
    val results = spark.read
      .option("rowTag", "book")
      .option("ignoreNamespace", true)
      .xml(getTestResourcePath(resDir + "books-namespaces.xml"))
    assert(results.filter("author IS NOT NULL").count() === 3)
    assert(results.filter("_id IS NOT NULL").count() === 3)
  }

  test("MapType field with attributes") {
    val schema = buildSchema(
      field("_startTime"),
      field("_interval"),
      field("PMTarget", MapType(StringType, StringType)))
    val df = spark.read.option("rowTag", "PMSetup").
      schema(schema).
      xml(getTestResourcePath(resDir + "map-attribute.xml")).
      select("PMTarget")
    val map = df.collect().head.getAs[Map[String, String]](0)
    assert(map.contains("_measurementType"))
    assert(map.contains("M1"))
    assert(map.contains("M2"))
  }

  test("StructType with missing optional StructType child") {
    val df = spark.read.option("rowTag", "Foo")
      .xml(getTestResourcePath(resDir + "struct_with_optional_child.xml"))
    val res = df.collect()
    assert(res.length == 1)
    assert(df.selectExpr("SIZE(Bar)").collect().head.getInt(0) === 2)
  }

  test("Manual schema with corrupt record field works on permissive mode failure") {
    // See issue #517
    val schema = StructType(List(
      StructField("_id", StringType),
      StructField("_space", StringType),
      StructField("c2", DoubleType),
      StructField("c3", StringType),
      StructField("c4", StringType),
      StructField("c5", StringType),
      StructField("c6", StringType),
      StructField("c7", StringType),
      StructField("c8", StringType),
      StructField("c9", DoubleType),
      StructField("c11", DoubleType),
      StructField("c20", ArrayType(StructType(List(
        StructField("_VALUE", StringType),
        StructField("_m", IntegerType)))
      )),
      StructField("c46", StringType),
      StructField("c76", StringType),
      StructField("c78", StringType),
      StructField("c85", DoubleType),
      StructField("c93", StringType),
      StructField("c95", StringType),
      StructField("c99", ArrayType(StructType(List(
        StructField("_VALUE", StringType),
        StructField("_m", IntegerType)))
      )),
      StructField("c100", ArrayType(StructType(List(
        StructField("_VALUE", StringType),
        StructField("_m", IntegerType)))
      )),
      StructField("c108", StringType),
      StructField("c192", DoubleType),
      StructField("c193", StringType),
      StructField("c194", StringType),
      StructField("c195", StringType),
      StructField("c196", StringType),
      StructField("c197", DoubleType),
      StructField("_corrupt_record", StringType)))

    val df = spark.read
      .option("inferSchema", false)
      .option("rowTag", "row")
      .schema(schema)
      .xml(getTestResourcePath(resDir + "manual_schema_corrupt_record.xml"))

    // Assert it works at all
    assert(df.collect().head.getAs[String]("_corrupt_record") !== null)
  }

  test("Test date parsing") {
    val schema = buildSchema(field("author"), field("date", DateType), field("date2", StringType))
    val df = spark.read
      .option("rowTag", "book")
      .schema(schema)
      .xml(getTestResourcePath(resDir + "date.xml"))
    assert(df.collect().head.getAs[Date](1).toString === "2021-02-01")
  }

  test("Test date type inference") {
    val df = spark.read
      .option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "date.xml"))
    val expectedSchema =
      buildSchema(field("author"), field("date", DateType), field("date2", StringType))
    assert(df.schema === expectedSchema)
    assert(df.collect().head.getAs[Date](1).toString === "2021-02-01")
  }

  test("Test timestamp parsing") {
    val schema =
      buildSchema(field("author"), field("time", TimestampType), field("time2", StringType))
    val df = spark.read
      .option("rowTag", "book")
      .schema(schema)
      .xml(getTestResourcePath(resDir + "time.xml"))
    assert(df.collect().head.getAs[Timestamp](1).getTime === 1322907330000L)
  }

  test("Test timestamp type inference") {
    val df = spark.read
      .option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "time.xml"))
    val expectedSchema =
      buildSchema(
        field("author"),
        field("time", TimestampType),
        field("time2", StringType),
        field("time3", StringType),
        field("time4", StringType)
      )
    assert(df.schema === expectedSchema)
    assert(df.collect().head.getAs[Timestamp](1).getTime === 1322907330000L)
  }

  test("Test dateFormat") {
    val df = spark.read
      .option("rowTag", "book")
      .option("dateFormat", "MM-dd-yyyy")
      .xml(getTestResourcePath(resDir + "date.xml"))
    val expectedSchema =
      buildSchema(field("author"), field("date", TimestampType), field("date2", DateType))
    assert(df.schema === expectedSchema)
    assert(df.collect().head.getAs[Timestamp](1).toString === "2021-01-31 16:00:00.0")
    assert(df.collect().head.getAs[Date](2).toString === "2021-02-01")
  }

  test("Test timestampFormat") {
    val df = spark.read
      .option("rowTag", "book")
      .option("timestampFormat", "MM-dd-yyyy HH:mm:ss z")
      .xml(getTestResourcePath(resDir + "time.xml"))
    val expectedSchema =
      buildSchema(
        field("author"),
        field("time", StringType),
        field("time2", TimestampType),
        field("time3", StringType),
        field("time4", StringType)
      )
    assert(df.schema === expectedSchema)
    assert(df.collect().head.get(1) === "2011-12-03T10:15:30Z")
    assert(df.collect().head.getAs[Timestamp](2).getTime === 1322936130000L)
  }

  test("Test custom timestampFormat without timezone") {
    val xml = s"""<book>
                 |    <author>John Smith</author>
                 |    <time>2011-12-03T10:15:30Z</time>
                 |    <time2>12-03-2011 10:15:30 PST</time2>
                 |    <time3>2011/12/03 06:15:30</time3>
                 |</book>""".stripMargin
    val input = spark.createDataset(Seq(xml))
    val df = spark.read
      .option("rowTag", "book")
      .option("timestampFormat", "yyyy/MM/dd HH:mm:ss")
      .xml(input)
    val expectedSchema =
      buildSchema(
        field("author"),
        field("time", StringType),
        field("time2", StringType),
        field("time3", TimestampType)
      )
    assert(df.schema === expectedSchema)
    val res = df.collect()
    assert(res.head.get(1) === "2011-12-03T10:15:30Z")
    assert(res.head.get(2) === "12-03-2011 10:15:30 PST")
    assert(res.head.getAs[Timestamp](3).getTime === 1322892930000L)
  }

  test("Test custom timestampFormat with offset") {
    val df = spark.read
      .option("rowTag", "book")
      .option("timestampFormat", "yyyy/MM/dd HH:mm:ss xx")
      .xml(getTestResourcePath(resDir + "time.xml"))
    val expectedSchema =
      buildSchema(
        field("author"),
        field("time", StringType),
        field("time2", StringType),
        field("time3", StringType),
        field("time4", TimestampType)
      )
    assert(df.schema === expectedSchema)
    assert(df.collect().head.get(1) === "2011-12-03T10:15:30Z")
    assert(df.collect().head.getAs[Timestamp](4).getTime === 1322892930000L)
  }

  test("Test null number type is null not 0.0") {
    val schema = buildSchema(
      structField("Header",
        field("_Name"), field("_SequenceNumber", LongType)),
      structArray("T",
        field("_Number", LongType), field("_VALUE", DoubleType), field("_Volume", DoubleType)))

    val df = spark.read.option("rowTag", "TEST")
      .option("nullValue", "")
      .schema(schema)
      .xml(getTestResourcePath(resDir + "null-numbers-2.xml"))
      .select(explode(column("T")))

    assert(df.collect()(1).getStruct(0).get(2) === null)
  }

  test("read multiple xml files in parallel") {
    val failedAgesSet = mutable.Set[Long]()
    val threads_ages = (1 to 10).map { i =>
      new Thread {
        override def run(): Unit = {
          val df = spark.read.option("rowTag", "person").format("xml")
            .load(getTestResourcePath(resDir + "ages.xml"))
          if (df.schema.fields.isEmpty) {
            failedAgesSet.add(i)
          }
        }
      }
    }

    val failedBooksSet = mutable.Set[Long]()
    val threads_books = (11 to 20).map { i =>
      new Thread {
        override def run(): Unit = {
          val df = spark.read.option("rowTag", "book").format("xml")
            .load(getTestResourcePath(resDir + "books.xml"))
          if (df.schema.fields.isEmpty) {
            failedBooksSet.add(i)
          }
        }
      }
    }

    threads_ages.foreach(_.start())
    threads_books.foreach(_.start())
    threads_ages.foreach(_.join())
    threads_books.foreach(_.join())
    assert(failedBooksSet.isEmpty)
    assert(failedAgesSet.isEmpty)
  }

  test("Issue 588: Ensure fails when data is not present, with or without schema") {
    val exception1 = intercept[AnalysisException] {
      spark.read.xml("/this/file/does/not/exist")
    }
    checkError(
      exception = exception1,
      errorClass = "PATH_NOT_FOUND",
      parameters = Map("path" -> "file:/this/file/does/not/exist")
    )
    val exception2 = intercept[AnalysisException] {
      spark.read.schema(buildSchema(field("dummy"))).xml("/this/file/does/not/exist")
    }
    checkError(
      exception = exception2,
      errorClass = "PATH_NOT_FOUND",
      parameters = Map("path" -> "file:/this/file/does/not/exist")
    )
  }

  test("Issue 614: mixed content element parsed as string in schema") {
    val textResults = spark.read
      .schema(buildSchema(field("text")))
      .option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "mixed_children_as_string.xml"))
    val textHead = textResults.select("text").head().getString(0)
    assert(textHead.contains(
      "Lorem ipsum dolor sit amet. Ut <i>voluptas</i> distinctio et impedit deserunt"))
    assert(textHead.contains(
      "<i>numquam</i> incidunt cum autem temporibus."))

    val bookResults = spark.read
      .schema(buildSchema(field("book")))
      .option("rowTag", "books")
      .xml(getTestResourcePath(resDir + "mixed_children_as_string.xml"))
    val bookHead = bookResults.select("book").head().getString(0)
    assert(bookHead.contains(
      "Lorem ipsum dolor sit amet. Ut <i>voluptas</i> distinctio et impedit deserunt"))
    assert(bookHead.contains(
      "<i>numquam</i> incidunt cum autem temporibus."))
  }

  private def getLines(path: Path): Seq[String] = {
    val source = Source.fromFile(path.toFile)
    try {
      source.getLines().toList
    } finally {
      source.close()
    }
  }

  test("read utf-8 encoded file") {
    val df = spark.read
      .option("charset", StandardCharsets.UTF_8.name)
      .option("rowTag", "book")
      .xml(getTestResourcePath(resDir + "books.xml"))
    assert(df.collect().length === 12)
  }

  test("read file with unicode chars in row tag name") {
    val df = spark.read
      .option("charset", StandardCharsets.UTF_8.name)
      .option("rowTag", "\u66F8") // scalastyle:ignore
      .xml(getTestResourcePath(resDir + "books-unicode-in-tag-name.xml"))
    assert(df.collect().length === 3)
  }

  test("read utf-8 encoded file with empty tag 2") {
    val df = spark.read
      .option("charset", StandardCharsets.UTF_8.name)
      .option("rowTag", "House")
      .xml(getTestResourcePath(resDir + "fias_house.xml"))
    assert(df.collect().length === 37)
  }
}
