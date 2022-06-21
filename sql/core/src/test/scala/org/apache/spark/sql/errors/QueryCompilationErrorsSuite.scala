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

import org.apache.spark.sql.{AnalysisException, ClassData, IntegratedUDFTestUtils, QueryTest, Row}
import org.apache.spark.sql.api.java.{UDF1, UDF2, UDF23Test}
import org.apache.spark.sql.expressions.SparkUserDefinedFunction
import org.apache.spark.sql.functions.{grouping, grouping_id, lit, struct, sum, udf}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{IntegerType, MapType, StringType, StructField, StructType}

case class StringLongClass(a: String, b: Long)

case class StringIntClass(a: String, b: Int)

case class ComplexClass(a: Long, b: StringLongClass)

case class ArrayClass(arr: Seq[StringIntClass])

class QueryCompilationErrorsSuite
  extends QueryTest
  with QueryErrorsSuiteBase {
  import testImplicits._

  test("CANNOT_UP_CAST_DATATYPE: invalid upcast data type") {
    val e1 = intercept[AnalysisException] {
      sql("select 'value1' as a, 1L as b").as[StringIntClass]
    }
    checkError(
      exception = e1,
      errorClass = "CANNOT_UP_CAST_DATATYPE",
      parameters = Map("expression" -> "b", "sourceType" -> "\"BIGINT\"", "targetType" -> "\"INT\"",
        "details" -> (
        s"""
           |The type path of the target object is:
           |- field (class: "scala.Int", name: "b")
           |- root class: "org.apache.spark.sql.errors.StringIntClass"
           |You can either add an explicit cast to the input data or choose a higher precision type
         """.stripMargin.trim + " of the field in the target object")))

    val e2 = intercept[AnalysisException] {
      sql("select 1L as a," +
        " named_struct('a', 'value1', 'b', cast(1.0 as decimal(38,18))) as b")
        .as[ComplexClass]
    }
    checkError(
      exception = e2,
      errorClass = "CANNOT_UP_CAST_DATATYPE",
      parameters = Map("expression" -> "b.`b`", "sourceType" -> "\"DECIMAL(38,18)\"",
        "targetType" -> "\"BIGINT\"",
        "details" -> (
        s"""
           |The type path of the target object is:
           |- field (class: "scala.Long", name: "b")
           |- field (class: "org.apache.spark.sql.errors.StringLongClass", name: "b")
           |- root class: "org.apache.spark.sql.errors.ComplexClass"
           |You can either add an explicit cast to the input data or choose a higher precision type
         """.stripMargin.trim + " of the field in the target object")))
  }

  test("UNSUPPORTED_GROUPING_EXPRESSION: filter with grouping/grouping_Id expression") {
    val df = Seq(
      (536361, "85123A", 2, 17850),
      (536362, "85123B", 4, 17850),
      (536363, "86123A", 6, 17851)
    ).toDF("InvoiceNo", "StockCode", "Quantity", "CustomerID")
    Seq("grouping", "grouping_id").foreach { grouping =>
      val e = intercept[AnalysisException] {
        df.groupBy("CustomerId").agg(Map("Quantity" -> "max"))
          .filter(s"$grouping(CustomerId)=17850")
      }
      checkError(
        exception = e,
        errorClass = "UNSUPPORTED_GROUPING_EXPRESSION",
        parameters = Map[String, String]())
    }
  }

  test("UNSUPPORTED_GROUPING_EXPRESSION: Sort with grouping/grouping_Id expression") {
    val df = Seq(
      (536361, "85123A", 2, 17850),
      (536362, "85123B", 4, 17850),
      (536363, "86123A", 6, 17851)
    ).toDF("InvoiceNo", "StockCode", "Quantity", "CustomerID")
    Seq(grouping("CustomerId"), grouping_id("CustomerId")).foreach { grouping =>
      val e = intercept[AnalysisException] {
        df.groupBy("CustomerId").agg(Map("Quantity" -> "max")).
          sort(grouping)
      }
      checkError(
        exception = e,
        errorClass = "UNSUPPORTED_GROUPING_EXPRESSION",
        parameters = Map[String, String]())
    }
  }

  test("INVALID_PARAMETER_VALUE: the argument_index of string format is invalid") {
    withSQLConf(SQLConf.ALLOW_ZERO_INDEX_IN_FORMAT_STRING.key -> "false") {
      val e = intercept[AnalysisException] {
        sql("select format_string('%0$s', 'Hello')")
      }
      checkErrorClass(
        exception = e,
        errorClass = "INVALID_PARAMETER_VALUE",
        msg = "The value of parameter(s) 'strfmt' in `format_string` is invalid: " +
          "expects %1$, %2$ and so on, but got %0$.; line 1 pos 7")
    }
  }

  test("INVALID_PANDAS_UDF_PLACEMENT: Using aggregate function with grouped aggregate pandas UDF") {
    import IntegratedUDFTestUtils._
    assume(shouldTestGroupedAggPandasUDFs)

    val df = Seq(
      (536361, "85123A", 2, 17850),
      (536362, "85123B", 4, 17850),
      (536363, "86123A", 6, 17851)
    ).toDF("InvoiceNo", "StockCode", "Quantity", "CustomerID")
    val e = intercept[AnalysisException] {
      val pandasTestUDF1 = TestGroupedAggPandasUDF(name = "pandas_udf_1")
      val pandasTestUDF2 = TestGroupedAggPandasUDF(name = "pandas_udf_2")
      df.groupBy("CustomerId")
        .agg(pandasTestUDF1(df("Quantity")), pandasTestUDF2(df("Quantity")), sum(df("Quantity")))
        .collect()
    }

    checkError(
      exception = e,
      errorClass = "INVALID_PANDAS_UDF_PLACEMENT",
      parameters = Map("functionList" -> "`pandas_udf_1`, `pandas_udf_2`"))
  }

  test("UNSUPPORTED_FEATURE: Using Python UDF with unsupported join condition") {
    import IntegratedUDFTestUtils._

    val df1 = Seq(
      (536361, "85123A", 2, 17850),
      (536362, "85123B", 4, 17850),
      (536363, "86123A", 6, 17851)
    ).toDF("InvoiceNo", "StockCode", "Quantity", "CustomerID")
    val df2 = Seq(
      ("Bob", 17850),
      ("Alice", 17850),
      ("Tom", 17851)
    ).toDF("CustomerName", "CustomerID")

    val e = intercept[AnalysisException] {
      val pythonTestUDF = TestPythonUDF(name = "python_udf")
      df1.join(
        df2, pythonTestUDF(df1("CustomerID") === df2("CustomerID")), "leftouter").collect()
    }

    checkError(
      exception = e,
      errorClass = "UNSUPPORTED_FEATURE",
      errorSubClass = Some("PYTHON_UDF_IN_ON_CLAUSE"),
      parameters = Map("joinType" -> "LEFT OUTER"),
      sqlState = Some("0A000"))
  }

  test("UNSUPPORTED_FEATURE: Using pandas UDF aggregate expression with pivot") {
    import IntegratedUDFTestUtils._
    assume(shouldTestGroupedAggPandasUDFs)

    val df = Seq(
      (536361, "85123A", 2, 17850),
      (536362, "85123B", 4, 17850),
      (536363, "86123A", 6, 17851)
    ).toDF("InvoiceNo", "StockCode", "Quantity", "CustomerID")

    val e = intercept[AnalysisException] {
      val pandasTestUDF = TestGroupedAggPandasUDF(name = "pandas_udf")
      df.groupBy(df("CustomerID")).pivot(df("CustomerID")).agg(pandasTestUDF(df("Quantity")))
    }

    checkError(
      exception = e,
      errorClass = "UNSUPPORTED_FEATURE",
      errorSubClass = "PANDAS_UDAF_IN_PIVOT",
      parameters = Map[String, String](),
      sqlState = "0A000")
  }

  test("NO_HANDLER_FOR_UDAF: No handler for UDAF error") {
    val functionName = "myCast"
    withUserDefinedFunction(functionName -> true) {
      sql(
        s"""
          |CREATE TEMPORARY FUNCTION $functionName
          |AS 'org.apache.spark.sql.errors.MyCastToString'
          |""".stripMargin)

      val e = intercept[AnalysisException] (
        sql(s"SELECT $functionName(123) as value")
      )
      checkErrorClass(
        exception = e,
        errorClass = "NO_HANDLER_FOR_UDAF",
        msg = "No handler for UDAF 'org.apache.spark.sql.errors.MyCastToString'. " +
          "Use sparkSession.udf.register(...) instead.; line 1 pos 7")
    }
  }

  test("UNTYPED_SCALA_UDF: use untyped Scala UDF should fail by default") {
    checkError(
      exception = intercept[AnalysisException](udf((x: Int) => x, IntegerType)),
      errorClass = "UNTYPED_SCALA_UDF",
      parameters = Map[String, String]())
  }

  test("NO_UDF_INTERFACE_ERROR: java udf class does not implement any udf interface") {
    val className = "org.apache.spark.sql.errors.MyCastToString"
    val e = intercept[AnalysisException](
      spark.udf.registerJava(
        "myCast",
        className,
        StringType)
    )
    checkError(
      exception = e,
      errorClass = "NO_UDF_INTERFACE_ERROR",
      parameters = Map("className" -> className))
  }

  test("MULTI_UDF_INTERFACE_ERROR: java udf implement multi UDF interface") {
    val className = "org.apache.spark.sql.errors.MySum"
    val e = intercept[AnalysisException](
      spark.udf.registerJava(
        "mySum",
        className,
        StringType)
    )
    checkError(
      exception = e,
      errorClass = "MULTI_UDF_INTERFACE_ERROR",
      parameters = Map("className" -> className))
  }

  test("UNSUPPORTED_FEATURE: java udf with too many type arguments") {
    val className = "org.apache.spark.sql.errors.MultiIntSum"
    val e = intercept[AnalysisException](
      spark.udf.registerJava(
        "mySum",
        className,
        StringType)
    )
    checkError(
      exception = e,
      errorClass = "UNSUPPORTED_FEATURE",
      errorSubClass = "TOO_MANY_TYPE_ARGUMENTS_FOR_UDF_CLASS",
      parameters = Map("num" -> "24"),
      sqlState = "0A000")
  }

  test("GROUPING_COLUMN_MISMATCH: not found the grouping column") {
    val groupingColMismatchEx = intercept[AnalysisException] {
      courseSales.cube("course", "year").agg(grouping("earnings")).explain()
    }
    checkError(
      exception = groupingColMismatchEx,
      errorClass = "GROUPING_COLUMN_MISMATCH",
      errorSubClass = None,
      parameters = Map("grouping" -> "earnings.*", "groupingColumns" -> "course.*,year.*"),
      sqlState = Some("42000"),
      matchPVals = true)
  }

  test("GROUPING_ID_COLUMN_MISMATCH: columns of grouping_id does not match") {
    val groupingIdColMismatchEx = intercept[AnalysisException] {
      courseSales.cube("course", "year").agg(grouping_id("earnings")).explain()
    }
    checkError(
      exception = groupingIdColMismatchEx,
      errorClass = "GROUPING_ID_COLUMN_MISMATCH",
      errorSubClass = None,
      parameters = Map("groupingIdColumn" -> "earnings.*",
      "groupByColumns" -> "course.*,year.*"),
      sqlState = Some("42000"),
      matchPVals = true)
  }

  test("GROUPING_SIZE_LIMIT_EXCEEDED: max size of grouping set") {
    withTempView("t") {
      sql("CREATE TEMPORARY VIEW t AS SELECT * FROM " +
        s"VALUES(${(0 until 65).map { _ => 1 }.mkString(", ")}, 3) AS " +
        s"t(${(0 until 65).map { i => s"k$i" }.mkString(", ")}, v)")

      def testGroupingIDs(numGroupingSet: Int, expectedIds: Seq[Any] = Nil): Unit = {
        val groupingCols = (0 until numGroupingSet).map { i => s"k$i" }
        val df = sql("SELECT GROUPING_ID(), SUM(v) FROM t GROUP BY " +
          s"GROUPING SETS ((${groupingCols.mkString(",")}), (${groupingCols.init.mkString(",")}))")
        checkAnswer(df, expectedIds.map { id => Row(id, 3) })
      }

      withSQLConf(SQLConf.LEGACY_INTEGER_GROUPING_ID.key -> "true") {
        checkError(
          exception = intercept[AnalysisException] { testGroupingIDs(33) },
          errorClass = "GROUPING_SIZE_LIMIT_EXCEEDED",
          parameters = Map("maxSize" -> "32"))
      }

      withSQLConf(SQLConf.LEGACY_INTEGER_GROUPING_ID.key -> "false") {
        checkError(
          exception = intercept[AnalysisException] { testGroupingIDs(65) },
          errorClass = "GROUPING_SIZE_LIMIT_EXCEEDED",
          parameters = Map("maxSize" -> "64"))
      }
    }
  }

  test("FORBIDDEN_OPERATION: desc partition on a temporary view") {
    val tableName: String = "t"
    val tempViewName: String = "tempView"

    withTable(tableName) {
      sql(
        s"""
          |CREATE TABLE $tableName (a STRING, b INT, c STRING, d STRING)
          |USING parquet
          |PARTITIONED BY (c, d)
          |""".stripMargin)

      withTempView(tempViewName) {
        sql(s"CREATE TEMPORARY VIEW $tempViewName as SELECT * FROM $tableName")

        checkError(
          exception = intercept[AnalysisException] {
            sql(s"DESC TABLE $tempViewName PARTITION (c='Us', d=1)")
          },
          errorClass = "FORBIDDEN_OPERATION",
          parameters = Map("statement" -> "DESC PARTITION",
            "objectType" -> "TEMPORARY VIEW", "objectName" -> s"`$tempViewName`"))
      }
    }
  }

  test("FORBIDDEN_OPERATION: desc partition on a view") {
    val tableName: String = "t"
    val viewName: String = "view"

    withTable(tableName) {
      sql(
        s"""
           |CREATE TABLE $tableName (a STRING, b INT, c STRING, d STRING)
           |USING parquet
           |PARTITIONED BY (c, d)
           |""".stripMargin)

      withView(viewName) {
        sql(s"CREATE VIEW $viewName as SELECT * FROM $tableName")

        checkError(
          exception = intercept[AnalysisException] {
            sql(s"DESC TABLE $viewName PARTITION (c='Us', d=1)")
          },
          errorClass = "FORBIDDEN_OPERATION",
          parameters = Map("statement" -> "DESC PARTITION",
          "objectType" -> "VIEW", "objectName" -> s"`$viewName`"))
      }
    }
  }

  test("SECOND_FUNCTION_ARGUMENT_NOT_INTEGER: " +
    "the second argument of 'date_add' function needs to be an integer") {
    withSQLConf(SQLConf.ANSI_ENABLED.key -> "false") {
      checkError(
        exception = intercept[AnalysisException] {
          sql("select date_add('1982-08-15', 'x')").collect()
        },
        errorClass = "SECOND_FUNCTION_ARGUMENT_NOT_INTEGER",
        parameters = Map("functionName" -> "date_add"),
        sqlState = "22023")
    }
  }

  test("INVALID_JSON_SCHEMA_MAP_TYPE: only STRING as a key type for MAP") {
    val schema = StructType(
      StructField("map", MapType(IntegerType, IntegerType, true), false) :: Nil)

    checkError(
      exception = intercept[AnalysisException] {
        spark.read.schema(schema).json(spark.emptyDataset[String])
      },
      errorClass = "INVALID_JSON_SCHEMA_MAP_TYPE",
      parameters = Map("jsonSchema" -> "\"STRUCT<map: MAP<INT, INT>>\"")
    )
  }

  test("UNRESOLVED_MAP_KEY: string type literal should be quoted") {
    checkAnswer(sql("select m['a'] from (select map('a', 'b') as m, 'aa' as aa)"), Row("b"))
    checkError(
      exception = intercept[AnalysisException] {
        sql("select m[a] from (select map('a', 'b') as m, 'aa' as aa)")
      },
      errorClass = "UNRESOLVED_MAP_KEY",
      parameters = Map("columnName" -> "`a`",
        "proposal" ->
          "`__auto_generated_subquery_name`.`m`, `__auto_generated_subquery_name`.`aa`"))
  }

  test("UNRESOLVED_COLUMN: SELECT distinct does not work correctly " +
    "if order by missing attribute") {
    checkAnswer(
      sql(
        """select distinct struct.a, struct.b
          |from (
          |  select named_struct('a', 1, 'b', 2, 'c', 3) as struct
          |  union all
          |  select named_struct('a', 1, 'b', 2, 'c', 4) as struct) tmp
          |order by a, b
          |""".stripMargin), Row(1, 2) :: Nil)

    checkErrorClass(
      exception = intercept[AnalysisException] {
        sql(
          """select distinct struct.a, struct.b
            |from (
            |  select named_struct('a', 1, 'b', 2, 'c', 3) as struct
            |  union all
            |  select named_struct('a', 1, 'b', 2, 'c', 4) as struct) tmp
            |order by struct.a, struct.b
            |""".stripMargin)
      },
      errorClass = "UNRESOLVED_COLUMN",
      msg = """A column or function parameter with name `struct`.`a` cannot be resolved. """ +
        """Did you mean one of the following\? \[`a`, `b`\]; line 6 pos 9;
           |'Sort \['struct.a ASC NULLS FIRST, 'struct.b ASC NULLS FIRST\], true
           |\+\- Distinct
           |   \+\- Project \[struct#\w+\.a AS a#\w+, struct#\w+\.b AS b#\w+\]
           |      \+\- SubqueryAlias tmp
           |         \+\- Union false, false
           |            :\- Project \[named_struct\(a, 1, b, 2, c, 3\) AS struct#\w+\]
           |            :  \+\- OneRowRelation
           |            \+\- Project \[named_struct\(a, 1, b, 2, c, 4\) AS struct#\w+\]
           |               \+\- OneRowRelation
           |""".stripMargin,
      matchMsg = true)
  }

  test("UNRESOLVED_COLUMN - SPARK-21335: support un-aliased subquery") {
    withTempView("v") {
      Seq(1 -> "a").toDF("i", "j").createOrReplaceTempView("v")
      checkAnswer(sql("SELECT i from (SELECT i FROM v)"), Row(1))

      checkErrorClass(
        exception = intercept[AnalysisException](sql("SELECT v.i from (SELECT i FROM v)")),
        errorClass = "UNRESOLVED_COLUMN",
        msg = "A column or function parameter with name `v`.`i` cannot be resolved. " +
          """Did you mean one of the following\? """ +
          """\[`__auto_generated_subquery_name`.`i`\]; line 1 pos 7;
            |'Project \['v.i\]
            |\+\- SubqueryAlias __auto_generated_subquery_name
            |   \+\- Project \[i#\w+\]
            |      \+\- SubqueryAlias v
            |         \+\- View \(`v`, \[i#\w+,j#\w+\]\)
            |            \+\- Project \[_\w+#\w+ AS i#\w+, _\w+#\w+ AS j#\w+\]
            |               \+\- LocalRelation \[_\w+#\w+, _\w+#\w+\]
            |""".stripMargin,
        matchMsg = true)

      checkAnswer(sql("SELECT __auto_generated_subquery_name.i from (SELECT i FROM v)"), Row(1))
    }
  }

  test("AMBIGUOUS_FIELD_NAME: alter column matching multi fields in the struct") {
    withTable("t") {
      withSQLConf(SQLConf.CASE_SENSITIVE.key -> "true") {
        sql("CREATE TABLE t(c struct<X:String, x:String>) USING parquet")
      }

      checkErrorClass(
        exception = intercept[AnalysisException] {
          sql("ALTER TABLE t CHANGE COLUMN c.X COMMENT 'new comment'")
        },
        errorClass = "AMBIGUOUS_FIELD_NAME",
        msg = "Field name c.X is ambiguous and has 2 matching fields in the struct.; line 1 pos 0")
    }
  }

  test("PIVOT_VALUE_DATA_TYPE_MISMATCH: can't cast pivot value data type (struct) " +
    "to pivot column data type (int)") {
    val df = Seq(
      ("dotNET", 2012, 10000),
      ("Java", 2012, 20000),
      ("dotNET", 2012, 5000),
      ("dotNET", 2013, 48000),
      ("Java", 2013, 30000)
    ).toDF("course", "year", "earnings")

    checkError(
      exception = intercept[AnalysisException] {
        df.groupBy(df("course")).pivot(df("year"), Seq(
          struct(lit("dotnet"), lit("Experts")),
          struct(lit("java"), lit("Dummies")))).
          agg(sum($"earnings")).collect()
      },
      errorClass = "PIVOT_VALUE_DATA_TYPE_MISMATCH",
      parameters = Map("value" -> "struct(col1, dotnet, col2, Experts)",
        "valueType" -> "struct<col1:string,col2:string>",
        "pivotType" -> "int"))
  }

  test("INVALID_FIELD_NAME: add a nested field for not struct parent") {
    withTable("t") {
      sql("CREATE TABLE t(c struct<x:string>, m string) USING parquet")

      val e = intercept[AnalysisException] {
        sql("ALTER TABLE t ADD COLUMNS (m.n int)")
      }
      checkErrorClass(
        exception = e,
        errorClass = "INVALID_FIELD_NAME",
        msg = "Field name `m`.`n` is invalid: `m` is not a struct.; line 1 pos 27")
    }
  }

  test("NON_LITERAL_PIVOT_VALUES: literal expressions required for pivot values") {
    val df = Seq(
      ("dotNET", 2012, 10000),
      ("Java", 2012, 20000),
      ("dotNET", 2012, 5000),
      ("dotNET", 2013, 48000),
      ("Java", 2013, 30000)
    ).toDF("course", "year", "earnings")

    checkError(
      exception = intercept[AnalysisException] {
        df.groupBy(df("course")).
          pivot(df("year"), Seq($"earnings")).
          agg(sum($"earnings")).collect()
      },
      errorClass = "NON_LITERAL_PIVOT_VALUES",
      parameters = Map("expression" -> "\"earnings\""))
  }

  test("UNSUPPORTED_DESERIALIZER: data type mismatch") {
    val e = intercept[AnalysisException] {
      sql("select 1 as arr").as[ArrayClass]
    }
    checkError(
      exception = e,
      errorClass = "UNSUPPORTED_DESERIALIZER",
      errorSubClass = Some("DATA_TYPE_MISMATCH"),
      parameters = Map("desiredType" -> "\"ARRAY\"", "dataType" -> "\"INT\""),
      sqlState = None)
  }

  test("UNSUPPORTED_DESERIALIZER: " +
    "the real number of fields doesn't match encoder schema") {
    val ds = Seq(ClassData("a", 1), ClassData("b", 2)).toDS()

    val e1 = intercept[AnalysisException] {
      ds.as[(String, Int, Long)]
    }
    checkError(
      exception = e1,
      errorClass = "UNSUPPORTED_DESERIALIZER",
      errorSubClass = Some("FIELD_NUMBER_MISMATCH"),
      parameters = Map("schema" -> "\"STRUCT<a: STRING, b: INT>\"",
        "ordinal" -> "3"),
      sqlState = None)

    val e2 = intercept[AnalysisException] {
      ds.as[Tuple1[String]]
    }
    checkError(
      exception = e2,
      errorClass = "UNSUPPORTED_DESERIALIZER",
      errorSubClass = Some("FIELD_NUMBER_MISMATCH"),
      parameters = Map("schema" -> "\"STRUCT<a: STRING, b: INT>\"",
        "ordinal" -> "1"),
      sqlState = None)
  }

  test("UNSUPPORTED_GENERATOR: " +
    "generators are not supported when it's nested in expressions") {
    val e = intercept[AnalysisException](
      sql("""select explode(Array(1, 2, 3)) + 1""").collect()
    )

    checkError(
      exception = e,
      errorClass = "UNSUPPORTED_GENERATOR",
      errorSubClass = Some("NESTED_IN_EXPRESSIONS"),
      parameters = Map("expression" -> "\"(explode(array(1, 2, 3)) + 1)\""),
      sqlState = None)
  }

  test("UNSUPPORTED_GENERATOR: only one generator allowed") {
    val e = intercept[AnalysisException](
      sql("""select explode(Array(1, 2, 3)), explode(Array(1, 2, 3))""").collect()
    )

    checkError(
      exception = e,
      errorClass = "UNSUPPORTED_GENERATOR",
      errorSubClass = Some("MULTI_GENERATOR"),
      parameters = Map("clause" -> "SELECT", "num" -> "2",
        "generators" -> "\"explode(array(1, 2, 3))\", \"explode(array(1, 2, 3))\""),
      sqlState = None)
  }

  test("UNSUPPORTED_GENERATOR: generators are not supported outside the SELECT clause") {
    val e = intercept[AnalysisException](
      sql("""select 1 from t order by explode(Array(1, 2, 3))""").collect()
    )

    checkError(
      exception = e,
      errorClass = "UNSUPPORTED_GENERATOR",
      errorSubClass = Some("OUTSIDE_SELECT"),
      parameters = Map("plan" -> "'Sort [explode(array(1, 2, 3)) ASC NULLS FIRST], true"),
      sqlState = None)
  }

  test("UNSUPPORTED_GENERATOR: not a generator") {
    val e = intercept[AnalysisException](
      sql(
        """
          |SELECT explodedvalue.*
          |FROM VALUES array(1, 2, 3) AS (value)
          |LATERAL VIEW array_contains(value, 1) AS explodedvalue""".stripMargin).collect()
    )

    checkErrorClass(
      exception = e,
      errorClass = "UNSUPPORTED_GENERATOR",
      errorSubClass = Some("NOT_GENERATOR"),
      msg = """The generator is not supported: `array_contains` is expected to be a generator. """ +
        "However, its class is org.apache.spark.sql.catalyst.expressions.ArrayContains, " +
        "which is not a generator.; line 4 pos 0"
    )
  }
}

class MyCastToString extends SparkUserDefinedFunction(
  (input: Any) => if (input == null) {
    null
  } else {
    input.toString
  },
  StringType,
  inputEncoders = Seq.fill(1)(None))

class MySum extends UDF1[Int, Int] with UDF2[Int, Int, Int] {
  override def call(t1: Int): Int = t1

  override def call(t1: Int, t2: Int): Int = t1 + t2
}

class MultiIntSum extends
  UDF23Test[Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int,
    Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int] {
  // scalastyle:off argcount
  override def call(
      t1: Int, t2: Int, t3: Int, t4: Int, t5: Int, t6: Int, t7: Int, t8: Int,
      t9: Int, t10: Int, t11: Int, t12: Int, t13: Int, t14: Int, t15: Int, t16: Int,
      t17: Int, t18: Int, t19: Int, t20: Int, t21: Int, t22: Int, t23: Int): Int = {
    t1 + t2 + t3 + t4 + t5 + t6 + t7 + t8 + t9 + t10 +
      t11 + t12 + t13 + t14 + t15 + t16 + t17 + t18 + t19 + t20 + t21 + t22 + t23
  }
  // scalastyle:on argcount
}
