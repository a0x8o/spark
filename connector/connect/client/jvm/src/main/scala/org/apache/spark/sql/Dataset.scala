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

import java.util.{Collections, Locale}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.control.NonFatal

import org.apache.spark.connect.proto
import org.apache.spark.sql.catalyst.expressions.RowOrdering
import org.apache.spark.sql.connect.client.SparkResult
import org.apache.spark.sql.connect.common.DataTypeProtoConverter
import org.apache.spark.sql.types.{Metadata, StructType}
import org.apache.spark.util.Utils

/**
 * A Dataset is a strongly typed collection of domain-specific objects that can be transformed in
 * parallel using functional or relational operations. Each Dataset also has an untyped view
 * called a `DataFrame`, which is a Dataset of [[Row]].
 *
 * Operations available on Datasets are divided into transformations and actions. Transformations
 * are the ones that produce new Datasets, and actions are the ones that trigger computation and
 * return results. Example transformations include map, filter, select, and aggregate (`groupBy`).
 * Example actions count, show, or writing data out to file systems.
 *
 * Datasets are "lazy", i.e. computations are only triggered when an action is invoked.
 * Internally, a Dataset represents a logical plan that describes the computation required to
 * produce the data. When an action is invoked, Spark's query optimizer optimizes the logical plan
 * and generates a physical plan for efficient execution in a parallel and distributed manner. To
 * explore the logical plan as well as optimized physical plan, use the `explain` function.
 *
 * To efficiently support domain-specific objects, an [[Encoder]] is required. The encoder maps
 * the domain specific type `T` to Spark's internal type system. For example, given a class
 * `Person` with two fields, `name` (string) and `age` (int), an encoder is used to tell Spark to
 * generate code at runtime to serialize the `Person` object into a binary structure. This binary
 * structure often has much lower memory footprint as well as are optimized for efficiency in data
 * processing (e.g. in a columnar format). To understand the internal binary representation for
 * data, use the `schema` function.
 *
 * There are typically two ways to create a Dataset. The most common way is by pointing Spark to
 * some files on storage systems, using the `read` function available on a `SparkSession`.
 * {{{
 *   val people = spark.read.parquet("...").as[Person]  // Scala
 *   Dataset<Person> people = spark.read().parquet("...").as(Encoders.bean(Person.class)); // Java
 * }}}
 *
 * Datasets can also be created through transformations available on existing Datasets. For
 * example, the following creates a new Dataset by applying a filter on the existing one:
 * {{{
 *   val names = people.map(_.name)  // in Scala; names is a Dataset[String]
 *   Dataset<String> names = people.map((Person p) -> p.name, Encoders.STRING));
 * }}}
 *
 * Dataset operations can also be untyped, through various domain-specific-language (DSL)
 * functions defined in: Dataset (this class), [[Column]], and [[functions]]. These operations are
 * very similar to the operations available in the data frame abstraction in R or Python.
 *
 * To select a column from the Dataset, use `apply` method in Scala and `col` in Java.
 * {{{
 *   val ageCol = people("age")  // in Scala
 *   Column ageCol = people.col("age"); // in Java
 * }}}
 *
 * Note that the [[Column]] type can also be manipulated through its various functions.
 * {{{
 *   // The following creates a new column that increases everybody's age by 10.
 *   people("age") + 10  // in Scala
 *   people.col("age").plus(10);  // in Java
 * }}}
 *
 * A more concrete example in Scala:
 * {{{
 *   // To create Dataset[Row] using SparkSession
 *   val people = spark.read.parquet("...")
 *   val department = spark.read.parquet("...")
 *
 *   people.filter("age > 30")
 *     .join(department, people("deptId") === department("id"))
 *     .groupBy(department("name"), people("gender"))
 *     .agg(avg(people("salary")), max(people("age")))
 * }}}
 *
 * and in Java:
 * {{{
 *   // To create Dataset<Row> using SparkSession
 *   Dataset<Row> people = spark.read().parquet("...");
 *   Dataset<Row> department = spark.read().parquet("...");
 *
 *   people.filter(people.col("age").gt(30))
 *     .join(department, people.col("deptId").equalTo(department.col("id")))
 *     .groupBy(department.col("name"), people.col("gender"))
 *     .agg(avg(people.col("salary")), max(people.col("age")));
 * }}}
 *
 * @groupname basic Basic Dataset functions
 * @groupname action Actions
 * @groupname untypedrel Untyped transformations
 * @groupname typedrel Typed transformations
 *
 * @since 3.4.0
 */
class Dataset[T] private[sql] (val session: SparkSession, private[sql] val plan: proto.Plan)
    extends Serializable {

  override def toString: String = {
    try {
      val builder = new mutable.StringBuilder
      val fields = schema.take(2).map { f =>
        s"${f.name}: ${f.dataType.simpleString(2)}"
      }
      builder.append("[")
      builder.append(fields.mkString(", "))
      if (schema.length > 2) {
        if (schema.length - fields.size == 1) {
          builder.append(" ... 1 more field")
        } else {
          builder.append(" ... " + (schema.length - 2) + " more fields")
        }
      }
      builder.append("]").toString()
    } catch {
      case NonFatal(e) =>
        s"Invalid Dataframe; ${e.getMessage}"
    }
  }

  /**
   * Converts this strongly typed collection of data to generic Dataframe. In contrast to the
   * strongly typed objects that Dataset operations work on, a Dataframe returns generic [[Row]]
   * objects that allow fields to be accessed by ordinal or name.
   *
   * @group basic
   * @since 3.4.0
   */
  def toDF(): DataFrame = {
    // Note this will change as soon as we add the typed APIs.
    this.asInstanceOf[Dataset[Row]]
  }

  /**
   * Converts this strongly typed collection of data to generic `DataFrame` with columns renamed.
   * This can be quite convenient in conversion from an RDD of tuples into a `DataFrame` with
   * meaningful names. For example:
   * {{{
   *   val rdd: RDD[(Int, String)] = ...
   *   rdd.toDF()  // this implicit conversion creates a DataFrame with column name `_1` and `_2`
   *   rdd.toDF("id", "name")  // this creates a DataFrame with column name "id" and "name"
   * }}}
   *
   * @group basic
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def toDF(colNames: String*): DataFrame = session.newDataset { builder =>
    builder.getToDfBuilder
      .setInput(plan.getRoot)
      .addAllColumnNames(colNames.asJava)
  }

  /**
   * Returns a new DataFrame where each row is reconciled to match the specified schema. Spark
   * will: <ul> <li>Reorder columns and/or inner fields by name to match the specified
   * schema.</li> <li>Project away columns and/or inner fields that are not needed by the
   * specified schema. Missing columns and/or inner fields (present in the specified schema but
   * not input DataFrame) lead to failures.</li> <li>Cast the columns and/or inner fields to match
   * the data types in the specified schema, if the types are compatible, e.g., numeric to numeric
   * (error if overflows), but not string to int.</li> <li>Carry over the metadata from the
   * specified schema, while the columns and/or inner fields still keep their own metadata if not
   * overwritten by the specified schema.</li> <li>Fail if the nullability is not compatible. For
   * example, the column and/or inner field is nullable but the specified schema requires them to
   * be not nullable.</li> </ul>
   *
   * @group basic
   * @since 3.4.0
   */
  def to(schema: StructType): DataFrame = session.newDataset { builder =>
    builder.getToSchemaBuilder
      .setInput(plan.getRoot)
      .setSchema(DataTypeProtoConverter.toConnectProtoType(schema))
  }

  /**
   * Returns the schema of this Dataset.
   *
   * @group basic
   * @since 3.4.0
   */
  def schema: StructType = {
    DataTypeProtoConverter.toCatalystType(analyze.getSchema).asInstanceOf[StructType]
  }

  /**
   * Prints the schema to the console in a nice tree format.
   *
   * @group basic
   * @since 3.4.0
   */
  def printSchema(): Unit = printSchema(Int.MaxValue)

  // scalastyle:off println
  /**
   * Prints the schema up to the given level to the console in a nice tree format.
   *
   * @group basic
   * @since 3.4.0
   */
  def printSchema(level: Int): Unit = println(schema.treeString(level))
  // scalastyle:on println

  /**
   * Prints the plans (logical and physical) with a format specified by a given explain mode.
   *
   * @param mode
   *   specifies the expected output format of plans. <ul> <li>`simple` Print only a physical
   *   plan.</li> <li>`extended`: Print both logical and physical plans.</li> <li>`codegen`: Print
   *   a physical plan and generated codes if they are available.</li> <li>`cost`: Print a logical
   *   plan and statistics if they are available.</li> <li>`formatted`: Split explain output into
   *   two sections: a physical plan outline and node details.</li> </ul>
   * @group basic
   * @since 3.4.0
   */
  def explain(mode: String): Unit = {
    val protoMode = mode.trim.toLowerCase(Locale.ROOT) match {
      case "simple" => proto.Explain.ExplainMode.SIMPLE
      case "extended" => proto.Explain.ExplainMode.EXTENDED
      case "codegen" => proto.Explain.ExplainMode.CODEGEN
      case "cost" => proto.Explain.ExplainMode.COST
      case "formatted" => proto.Explain.ExplainMode.FORMATTED
      case _ => throw new IllegalArgumentException("Unsupported explain mode: " + mode)
    }
    explain(protoMode)
  }

  /**
   * Prints the plans (logical and physical) to the console for debugging purposes.
   *
   * @param extended
   *   default `false`. If `false`, prints only the physical plan.
   *
   * @group basic
   * @since 3.4.0
   */
  def explain(extended: Boolean): Unit = {
    val mode = if (extended) {
      proto.Explain.ExplainMode.EXTENDED
    } else {
      proto.Explain.ExplainMode.SIMPLE
    }
    explain(mode)
  }

  /**
   * Prints the physical plan to the console for debugging purposes.
   *
   * @group basic
   * @since 3.4.0
   */
  def explain(): Unit = explain(proto.Explain.ExplainMode.SIMPLE)

  private def explain(mode: proto.Explain.ExplainMode): Unit = {
    // scalastyle:off println
    println(session.analyze(plan, mode).getExplainString)
    // scalastyle:on println
  }

  /**
   * Returns all column names and their data types as an array.
   *
   * @group basic
   * @since 3.4.0
   */
  def dtypes: Array[(String, String)] = schema.fields.map { field =>
    (field.name, field.dataType.toString)
  }

  /**
   * Returns all column names as an array.
   *
   * @group basic
   * @since 3.4.0
   */
  def columns: Array[String] = schema.fields.map(_.name)

  /**
   * Returns true if the `collect` and `take` methods can be run locally (without any Spark
   * executors).
   *
   * @group basic
   * @since 3.4.0
   */
  def isLocal: Boolean = analyze.getIsLocal

  /**
   * Returns true if the `Dataset` is empty.
   *
   * @group basic
   * @since 3.4.0
   */
  def isEmpty: Boolean = select().limit(1).withResult { result =>
    result.length == 0
  }

  /**
   * Returns true if this Dataset contains one or more sources that continuously return data as it
   * arrives. A Dataset that reads data from a streaming source must be executed as a
   * `StreamingQuery` using the `start()` method in `DataStreamWriter`.
   *
   * @group streaming
   * @since 3.4.0
   */
  def isStreaming: Boolean = analyze.getIsStreaming

  /**
   * Displays the Dataset in a tabular form. Strings more than 20 characters will be truncated,
   * and all cells will be aligned right. For example:
   * {{{
   *   year  month AVG('Adj Close) MAX('Adj Close)
   *   1980  12    0.503218        0.595103
   *   1981  01    0.523289        0.570307
   *   1982  02    0.436504        0.475256
   *   1983  03    0.410516        0.442194
   *   1984  04    0.450090        0.483521
   * }}}
   *
   * @param numRows
   *   Number of rows to show
   *
   * @group action
   * @since 3.4.0
   */
  def show(numRows: Int): Unit = show(numRows, truncate = true)

  /**
   * Displays the top 20 rows of Dataset in a tabular form. Strings more than 20 characters will
   * be truncated, and all cells will be aligned right.
   *
   * @group action
   * @since 3.4.0
   */
  def show(): Unit = show(20)

  /**
   * Displays the top 20 rows of Dataset in a tabular form.
   *
   * @param truncate
   *   Whether truncate long strings. If true, strings more than 20 characters will be truncated
   *   and all cells will be aligned right
   *
   * @group action
   * @since 3.4.0
   */
  def show(truncate: Boolean): Unit = show(20, truncate)

  /**
   * Displays the Dataset in a tabular form. For example:
   * {{{
   *   year  month AVG('Adj Close) MAX('Adj Close)
   *   1980  12    0.503218        0.595103
   *   1981  01    0.523289        0.570307
   *   1982  02    0.436504        0.475256
   *   1983  03    0.410516        0.442194
   *   1984  04    0.450090        0.483521
   * }}}
   * @param numRows
   *   Number of rows to show
   * @param truncate
   *   Whether truncate long strings. If true, strings more than 20 characters will be truncated
   *   and all cells will be aligned right
   *
   * @group action
   * @since 3.4.0
   */
  // scalastyle:off println
  def show(numRows: Int, truncate: Boolean): Unit = {
    val truncateValue = if (truncate) 20 else 0
    show(numRows, truncateValue, vertical = false)
  }

  /**
   * Displays the Dataset in a tabular form. For example:
   * {{{
   *   year  month AVG('Adj Close) MAX('Adj Close)
   *   1980  12    0.503218        0.595103
   *   1981  01    0.523289        0.570307
   *   1982  02    0.436504        0.475256
   *   1983  03    0.410516        0.442194
   *   1984  04    0.450090        0.483521
   * }}}
   *
   * @param numRows
   *   Number of rows to show
   * @param truncate
   *   If set to more than 0, truncates strings to `truncate` characters and all cells will be
   *   aligned right.
   * @group action
   * @since 3.4.0
   */
  def show(numRows: Int, truncate: Int): Unit = show(numRows, truncate, vertical = false)

  /**
   * Displays the Dataset in a tabular form. For example:
   * {{{
   *   year  month AVG('Adj Close) MAX('Adj Close)
   *   1980  12    0.503218        0.595103
   *   1981  01    0.523289        0.570307
   *   1982  02    0.436504        0.475256
   *   1983  03    0.410516        0.442194
   *   1984  04    0.450090        0.483521
   * }}}
   *
   * If `vertical` enabled, this command prints output rows vertically (one line per column
   * value)?
   *
   * {{{
   * -RECORD 0-------------------
   *  year            | 1980
   *  month           | 12
   *  AVG('Adj Close) | 0.503218
   *  AVG('Adj Close) | 0.595103
   * -RECORD 1-------------------
   *  year            | 1981
   *  month           | 01
   *  AVG('Adj Close) | 0.523289
   *  AVG('Adj Close) | 0.570307
   * -RECORD 2-------------------
   *  year            | 1982
   *  month           | 02
   *  AVG('Adj Close) | 0.436504
   *  AVG('Adj Close) | 0.475256
   * -RECORD 3-------------------
   *  year            | 1983
   *  month           | 03
   *  AVG('Adj Close) | 0.410516
   *  AVG('Adj Close) | 0.442194
   * -RECORD 4-------------------
   *  year            | 1984
   *  month           | 04
   *  AVG('Adj Close) | 0.450090
   *  AVG('Adj Close) | 0.483521
   * }}}
   *
   * @param numRows
   *   Number of rows to show
   * @param truncate
   *   If set to more than 0, truncates strings to `truncate` characters and all cells will be
   *   aligned right.
   * @param vertical
   *   If set to true, prints output rows vertically (one line per column value).
   * @group action
   * @since 3.4.0
   */
  def show(numRows: Int, truncate: Int, vertical: Boolean): Unit = {
    val df = session.newDataset { builder =>
      builder.getShowStringBuilder
        .setInput(plan.getRoot)
        .setNumRows(numRows)
        .setTruncate(truncate)
        .setVertical(vertical)
    }
    df.withResult { result =>
      assert(result.length == 1)
      assert(result.schema.size == 1)
      // scalastyle:off println
      println(result.toArray.head.getString(0))
    // scalastyle:on println
    }
  }

  private def buildJoin(right: Dataset[_])(f: proto.Join.Builder => Unit): DataFrame = {
    session.newDataset { builder =>
      val joinBuilder = builder.getJoinBuilder
      joinBuilder.setLeft(plan.getRoot).setRight(right.plan.getRoot)
      f(joinBuilder)
    }
  }

  private def toJoinType(name: String): proto.Join.JoinType = {
    name.trim.toLowerCase(Locale.ROOT) match {
      case "inner" =>
        proto.Join.JoinType.JOIN_TYPE_INNER
      case "cross" =>
        proto.Join.JoinType.JOIN_TYPE_CROSS
      case "outer" | "full" | "fullouter" | "full_outer" =>
        proto.Join.JoinType.JOIN_TYPE_FULL_OUTER
      case "left" | "leftouter" | "left_outer" =>
        proto.Join.JoinType.JOIN_TYPE_LEFT_OUTER
      case "right" | "rightouter" | "right_outer" =>
        proto.Join.JoinType.JOIN_TYPE_RIGHT_OUTER
      case "semi" | "leftsemi" | "left_semi" =>
        proto.Join.JoinType.JOIN_TYPE_LEFT_SEMI
      case "anti" | "leftanti" | "left_anti" =>
        proto.Join.JoinType.JOIN_TYPE_LEFT_ANTI
      case _ =>
        throw new IllegalArgumentException(s"Unsupported join type `joinType`.")
    }
  }

  /**
   * Join with another `DataFrame`.
   *
   * Behaves as an INNER JOIN and requires a subsequent join predicate.
   *
   * @param right
   *   Right side of the join operation.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def join(right: Dataset[_]): DataFrame = buildJoin(right) { builder =>
    builder.setJoinType(proto.Join.JoinType.JOIN_TYPE_INNER)
  }

  /**
   * Inner equi-join with another `DataFrame` using the given column.
   *
   * Different from other join functions, the join column will only appear once in the output,
   * i.e. similar to SQL's `JOIN USING` syntax.
   *
   * {{{
   *   // Joining df1 and df2 using the column "user_id"
   *   df1.join(df2, "user_id")
   * }}}
   *
   * @param right
   *   Right side of the join operation.
   * @param usingColumn
   *   Name of the column to join on. This column must exist on both sides.
   *
   * @note
   *   If you perform a self-join using this function without aliasing the input `DataFrame`s, you
   *   will NOT be able to reference any columns after the join, since there is no way to
   *   disambiguate which side of the join you would like to reference.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def join(right: Dataset[_], usingColumn: String): DataFrame = {
    join(right, Seq(usingColumn))
  }

  /**
   * (Java-specific) Inner equi-join with another `DataFrame` using the given columns. See the
   * Scala-specific overload for more details.
   *
   * @param right
   *   Right side of the join operation.
   * @param usingColumns
   *   Names of the columns to join on. This columns must exist on both sides.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def join(right: Dataset[_], usingColumns: Array[String]): DataFrame = {
    join(right, usingColumns.toSeq)
  }

  /**
   * (Scala-specific) Inner equi-join with another `DataFrame` using the given columns.
   *
   * Different from other join functions, the join columns will only appear once in the output,
   * i.e. similar to SQL's `JOIN USING` syntax.
   *
   * {{{
   *   // Joining df1 and df2 using the columns "user_id" and "user_name"
   *   df1.join(df2, Seq("user_id", "user_name"))
   * }}}
   *
   * @param right
   *   Right side of the join operation.
   * @param usingColumns
   *   Names of the columns to join on. This columns must exist on both sides.
   *
   * @note
   *   If you perform a self-join using this function without aliasing the input `DataFrame`s, you
   *   will NOT be able to reference any columns after the join, since there is no way to
   *   disambiguate which side of the join you would like to reference.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def join(right: Dataset[_], usingColumns: Seq[String]): DataFrame = {
    join(right, usingColumns, "inner")
  }

  /**
   * Equi-join with another `DataFrame` using the given column. A cross join with a predicate is
   * specified as an inner join. If you would explicitly like to perform a cross join use the
   * `crossJoin` method.
   *
   * Different from other join functions, the join column will only appear once in the output,
   * i.e. similar to SQL's `JOIN USING` syntax.
   *
   * @param right
   *   Right side of the join operation.
   * @param usingColumn
   *   Name of the column to join on. This column must exist on both sides.
   * @param joinType
   *   Type of join to perform. Default `inner`. Must be one of: `inner`, `cross`, `outer`,
   *   `full`, `fullouter`, `full_outer`, `left`, `leftouter`, `left_outer`, `right`,
   *   `rightouter`, `right_outer`, `semi`, `leftsemi`, `left_semi`, `anti`, `leftanti`,
   *   `left_anti`.
   *
   * @note
   *   If you perform a self-join using this function without aliasing the input `DataFrame`s, you
   *   will NOT be able to reference any columns after the join, since there is no way to
   *   disambiguate which side of the join you would like to reference.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def join(right: Dataset[_], usingColumn: String, joinType: String): DataFrame = {
    join(right, Seq(usingColumn), joinType)
  }

  /**
   * (Java-specific) Equi-join with another `DataFrame` using the given columns. See the
   * Scala-specific overload for more details.
   *
   * @param right
   *   Right side of the join operation.
   * @param usingColumns
   *   Names of the columns to join on. This columns must exist on both sides.
   * @param joinType
   *   Type of join to perform. Default `inner`. Must be one of: `inner`, `cross`, `outer`,
   *   `full`, `fullouter`, `full_outer`, `left`, `leftouter`, `left_outer`, `right`,
   *   `rightouter`, `right_outer`, `semi`, `leftsemi`, `left_semi`, `anti`, `leftanti`,
   *   `left_anti`.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def join(right: Dataset[_], usingColumns: Array[String], joinType: String): DataFrame = {
    join(right, usingColumns.toSeq, joinType)
  }

  /**
   * (Scala-specific) Equi-join with another `DataFrame` using the given columns. A cross join
   * with a predicate is specified as an inner join. If you would explicitly like to perform a
   * cross join use the `crossJoin` method.
   *
   * Different from other join functions, the join columns will only appear once in the output,
   * i.e. similar to SQL's `JOIN USING` syntax.
   *
   * @param right
   *   Right side of the join operation.
   * @param usingColumns
   *   Names of the columns to join on. This columns must exist on both sides.
   * @param joinType
   *   Type of join to perform. Default `inner`. Must be one of: `inner`, `cross`, `outer`,
   *   `full`, `fullouter`, `full_outer`, `left`, `leftouter`, `left_outer`, `right`,
   *   `rightouter`, `right_outer`, `semi`, `leftsemi`, `left_semi`, `anti`, `leftanti`,
   *   `left_anti`.
   *
   * @note
   *   If you perform a self-join using this function without aliasing the input `DataFrame`s, you
   *   will NOT be able to reference any columns after the join, since there is no way to
   *   disambiguate which side of the join you would like to reference.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def join(right: Dataset[_], usingColumns: Seq[String], joinType: String): DataFrame = {
    buildJoin(right) { builder =>
      builder
        .setJoinType(toJoinType(joinType))
        .addAllUsingColumns(usingColumns.asJava)
    }
  }

  /**
   * Inner join with another `DataFrame`, using the given join expression.
   *
   * {{{
   *   // The following two are equivalent:
   *   df1.join(df2, $"df1Key" === $"df2Key")
   *   df1.join(df2).where($"df1Key" === $"df2Key")
   * }}}
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def join(right: Dataset[_], joinExprs: Column): DataFrame = join(right, joinExprs, "inner")

  /**
   * Join with another `DataFrame`, using the given join expression. The following performs a full
   * outer join between `df1` and `df2`.
   *
   * {{{
   *   // Scala:
   *   import org.apache.spark.sql.functions._
   *   df1.join(df2, $"df1Key" === $"df2Key", "outer")
   *
   *   // Java:
   *   import static org.apache.spark.sql.functions.*;
   *   df1.join(df2, col("df1Key").equalTo(col("df2Key")), "outer");
   * }}}
   *
   * @param right
   *   Right side of the join.
   * @param joinExprs
   *   Join expression.
   * @param joinType
   *   Type of join to perform. Default `inner`. Must be one of: `inner`, `cross`, `outer`,
   *   `full`, `fullouter`, `full_outer`, `left`, `leftouter`, `left_outer`, `right`,
   *   `rightouter`, `right_outer`, `semi`, `leftsemi`, `left_semi`, `anti`, `leftanti`,
   *   `left_anti`.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def join(right: Dataset[_], joinExprs: Column, joinType: String): DataFrame = {
    buildJoin(right) { builder =>
      builder
        .setJoinType(toJoinType(joinType))
        .setJoinCondition(joinExprs.expr)
    }
  }

  /**
   * Explicit cartesian join with another `DataFrame`.
   *
   * @param right
   *   Right side of the join operation.
   *
   * @note
   *   Cartesian joins are very expensive without an extra filter that can be pushed down.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def crossJoin(right: Dataset[_]): DataFrame = buildJoin(right) { builder =>
    builder.setJoinType(proto.Join.JoinType.JOIN_TYPE_CROSS)
  }

  private def buildSort(global: Boolean, sortExprs: Seq[Column]): Dataset[T] = {
    session.newDataset { builder =>
      builder.getSortBuilder
        .setInput(plan.getRoot)
        .setIsGlobal(global)
        .addAllOrder(sortExprs.map(_.sortOrder).asJava)
    }
  }

  /**
   * Returns a new Dataset with each partition sorted by the given expressions.
   *
   * This is the same operation as "SORT BY" in SQL (Hive QL).
   *
   * @group typedrel
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def sortWithinPartitions(sortCol: String, sortCols: String*): Dataset[T] = {
    sortWithinPartitions((sortCol +: sortCols).map(Column(_)): _*)
  }

  /**
   * Returns a new Dataset with each partition sorted by the given expressions.
   *
   * This is the same operation as "SORT BY" in SQL (Hive QL).
   *
   * @group typedrel
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def sortWithinPartitions(sortExprs: Column*): Dataset[T] = {
    buildSort(global = false, sortExprs)
  }

  /**
   * Returns a new Dataset sorted by the specified column, all in ascending order.
   * {{{
   *   // The following 3 are equivalent
   *   ds.sort("sortcol")
   *   ds.sort($"sortcol")
   *   ds.sort($"sortcol".asc)
   * }}}
   *
   * @group typedrel
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def sort(sortCol: String, sortCols: String*): Dataset[T] = {
    sort((sortCol +: sortCols).map(Column(_)): _*)
  }

  /**
   * Returns a new Dataset sorted by the given expressions. For example:
   * {{{
   *   ds.sort($"col1", $"col2".desc)
   * }}}
   *
   * @group typedrel
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def sort(sortExprs: Column*): Dataset[T] = {
    buildSort(global = true, sortExprs)
  }

  /**
   * Returns a new Dataset sorted by the given expressions. This is an alias of the `sort`
   * function.
   *
   * @group typedrel
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def orderBy(sortCol: String, sortCols: String*): Dataset[T] = sort(sortCol, sortCols: _*)

  /**
   * Returns a new Dataset sorted by the given expressions. This is an alias of the `sort`
   * function.
   *
   * @group typedrel
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def orderBy(sortExprs: Column*): Dataset[T] = sort(sortExprs: _*)

  /**
   * Selects column based on the column name and returns it as a [[Column]].
   *
   * @note
   *   The column name can also reference to a nested column like `a.b`.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def apply(colName: String): Column = col(colName)

  /**
   * Specifies some hint on the current Dataset. As an example, the following code specifies that
   * one of the plan can be broadcasted:
   *
   * {{{
   *   df1.join(df2.hint("broadcast"))
   * }}}
   *
   * @group basic
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def hint(name: String, parameters: Any*): Dataset[T] = session.newDataset { builder =>
    builder.getHintBuilder
      .setInput(plan.getRoot)
      .setName(name)
      .addAllParameters(parameters.map(p => functions.lit(p).expr).asJava)
  }

  /**
   * Selects column based on the column name and returns it as a [[Column]].
   *
   * @note
   *   The column name can also reference to a nested column like `a.b`.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def col(colName: String): Column = functions.col(colName)

  /**
   * Selects column based on the column name specified as a regex and returns it as [[Column]].
   * @group untypedrel
   * @since 3.4.0
   */
  def colRegex(colName: String): Column = Column { builder =>
    builder.getUnresolvedRegexBuilder.setColName(colName)
  }

  /**
   * Returns a new Dataset with an alias set.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def as(alias: String): Dataset[T] = session.newDataset { builder =>
    builder.getSubqueryAliasBuilder
      .setInput(plan.getRoot)
      .setAlias(alias)
  }

  /**
   * (Scala-specific) Returns a new Dataset with an alias set.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def as(alias: Symbol): Dataset[T] = as(alias.name)

  /**
   * Returns a new Dataset with an alias set. Same as `as`.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def alias(alias: String): Dataset[T] = as(alias)

  /**
   * (Scala-specific) Returns a new Dataset with an alias set. Same as `as`.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def alias(alias: Symbol): Dataset[T] = as(alias)

  /**
   * Selects a set of column based expressions.
   * {{{
   *   ds.select($"colA", $"colB" + 1)
   * }}}
   *
   * @group untypedrel
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def select(cols: Column*): DataFrame = session.newDataset { builder =>
    builder.getProjectBuilder
      .setInput(plan.getRoot)
      .addAllExpressions(cols.map(_.expr).asJava)
  }

  /**
   * Selects a set of columns. This is a variant of `select` that can only select existing columns
   * using column names (i.e. cannot construct expressions).
   *
   * {{{
   *   // The following two are equivalent:
   *   ds.select("colA", "colB")
   *   ds.select($"colA", $"colB")
   * }}}
   *
   * @group untypedrel
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def select(col: String, cols: String*): DataFrame = select((col +: cols).map(Column(_)): _*)

  /**
   * Selects a set of SQL expressions. This is a variant of `select` that accepts SQL expressions.
   *
   * {{{
   *   // The following are equivalent:
   *   ds.selectExpr("colA", "colB as newName", "abs(colC)")
   *   ds.select(expr("colA"), expr("colB as newName"), expr("abs(colC)"))
   * }}}
   *
   * @group untypedrel
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def selectExpr(exprs: String*): DataFrame = {
    select(exprs.map(functions.expr): _*)
  }

  /**
   * Filters rows using the given condition.
   * {{{
   *   // The following are equivalent:
   *   peopleDs.filter($"age" > 15)
   *   peopleDs.where($"age" > 15)
   * }}}
   *
   * @group typedrel
   * @since 3.4.0
   */
  def filter(condition: Column): Dataset[T] = session.newDataset { builder =>
    builder.getFilterBuilder.setInput(plan.getRoot).setCondition(condition.expr)
  }

  /**
   * Filters rows using the given SQL expression.
   * {{{
   *   peopleDs.filter("age > 15")
   * }}}
   *
   * @group typedrel
   * @since 3.4.0
   */
  def filter(conditionExpr: String): Dataset[T] = filter(functions.expr(conditionExpr))

  /**
   * Filters rows using the given condition. This is an alias for `filter`.
   * {{{
   *   // The following are equivalent:
   *   peopleDs.filter($"age" > 15)
   *   peopleDs.where($"age" > 15)
   * }}}
   *
   * @group typedrel
   * @since 3.4.0
   */
  def where(condition: Column): Dataset[T] = filter(condition)

  /**
   * Filters rows using the given SQL expression.
   * {{{
   *   peopleDs.where("age > 15")
   * }}}
   *
   * @group typedrel
   * @since 3.4.0
   */
  def where(conditionExpr: String): Dataset[T] = filter(conditionExpr)

  private def buildUnpivot(
      ids: Array[Column],
      valuesOption: Option[Array[Column]],
      variableColumnName: String,
      valueColumnName: String): DataFrame = session.newDataset { builder =>
    val unpivot = builder.getUnpivotBuilder
      .setInput(plan.getRoot)
      .addAllIds(ids.toSeq.map(_.expr).asJava)
      .setValueColumnName(variableColumnName)
      .setValueColumnName(valueColumnName)
    valuesOption.foreach { values =>
      unpivot.getValuesBuilder
        .addAllValues(values.toSeq.map(_.expr).asJava)
    }
  }

  /**
   * Unpivot a DataFrame from wide format to long format, optionally leaving identifier columns
   * set. This is the reverse to `groupBy(...).pivot(...).agg(...)`, except for the aggregation,
   * which cannot be reversed.
   *
   * This function is useful to massage a DataFrame into a format where some columns are
   * identifier columns ("ids"), while all other columns ("values") are "unpivoted" to the rows,
   * leaving just two non-id columns, named as given by `variableColumnName` and
   * `valueColumnName`.
   *
   * {{{
   *   val df = Seq((1, 11, 12L), (2, 21, 22L)).toDF("id", "int", "long")
   *   df.show()
   *   // output:
   *   // +---+---+----+
   *   // | id|int|long|
   *   // +---+---+----+
   *   // |  1| 11|  12|
   *   // |  2| 21|  22|
   *   // +---+---+----+
   *
   *   df.unpivot(Array($"id"), Array($"int", $"long"), "variable", "value").show()
   *   // output:
   *   // +---+--------+-----+
   *   // | id|variable|value|
   *   // +---+--------+-----+
   *   // |  1|     int|   11|
   *   // |  1|    long|   12|
   *   // |  2|     int|   21|
   *   // |  2|    long|   22|
   *   // +---+--------+-----+
   *   // schema:
   *   //root
   *   // |-- id: integer (nullable = false)
   *   // |-- variable: string (nullable = false)
   *   // |-- value: long (nullable = true)
   * }}}
   *
   * When no "id" columns are given, the unpivoted DataFrame consists of only the "variable" and
   * "value" columns.
   *
   * All "value" columns must share a least common data type. Unless they are the same data type,
   * all "value" columns are cast to the nearest common data type. For instance, types
   * `IntegerType` and `LongType` are cast to `LongType`, while `IntegerType` and `StringType` do
   * not have a common data type and `unpivot` fails with an `AnalysisException`.
   *
   * @param ids
   *   Id columns
   * @param values
   *   Value columns to unpivot
   * @param variableColumnName
   *   Name of the variable column
   * @param valueColumnName
   *   Name of the value column
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def unpivot(
      ids: Array[Column],
      values: Array[Column],
      variableColumnName: String,
      valueColumnName: String): DataFrame = {
    buildUnpivot(ids, Option(values), variableColumnName, valueColumnName)
  }

  /**
   * Unpivot a DataFrame from wide format to long format, optionally leaving identifier columns
   * set. This is the reverse to `groupBy(...).pivot(...).agg(...)`, except for the aggregation,
   * which cannot be reversed.
   *
   * @see
   *   `org.apache.spark.sql.Dataset.unpivot(Array, Array, String, String)`
   *
   * This is equivalent to calling `Dataset#unpivot(Array, Array, String, String)` where `values`
   * is set to all non-id columns that exist in the DataFrame.
   *
   * @param ids
   *   Id columns
   * @param variableColumnName
   *   Name of the variable column
   * @param valueColumnName
   *   Name of the value column
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def unpivot(
      ids: Array[Column],
      variableColumnName: String,
      valueColumnName: String): DataFrame = {
    buildUnpivot(ids, None, variableColumnName, valueColumnName)
  }

  /**
   * Unpivot a DataFrame from wide format to long format, optionally leaving identifier columns
   * set. This is the reverse to `groupBy(...).pivot(...).agg(...)`, except for the aggregation,
   * which cannot be reversed. This is an alias for `unpivot`.
   *
   * @see
   *   `org.apache.spark.sql.Dataset.unpivot(Array, Array, String, String)`
   *
   * @param ids
   *   Id columns
   * @param values
   *   Value columns to unpivot
   * @param variableColumnName
   *   Name of the variable column
   * @param valueColumnName
   *   Name of the value column
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def melt(
      ids: Array[Column],
      values: Array[Column],
      variableColumnName: String,
      valueColumnName: String): DataFrame =
    unpivot(ids, values, variableColumnName, valueColumnName)

  /**
   * Unpivot a DataFrame from wide format to long format, optionally leaving identifier columns
   * set. This is the reverse to `groupBy(...).pivot(...).agg(...)`, except for the aggregation,
   * which cannot be reversed. This is an alias for `unpivot`.
   *
   * @see
   *   `org.apache.spark.sql.Dataset.unpivot(Array, Array, String, String)`
   *
   * This is equivalent to calling `Dataset#unpivot(Array, Array, String, String)` where `values`
   * is set to all non-id columns that exist in the DataFrame.
   *
   * @param ids
   *   Id columns
   * @param variableColumnName
   *   Name of the variable column
   * @param valueColumnName
   *   Name of the value column
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def melt(ids: Array[Column], variableColumnName: String, valueColumnName: String): DataFrame =
    unpivot(ids, variableColumnName, valueColumnName)

  /**
   * Returns a new Dataset by taking the first `n` rows. The difference between this function and
   * `head` is that `head` is an action and returns an array (by triggering query execution) while
   * `limit` returns a new Dataset.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def limit(n: Int): Dataset[T] = session.newDataset { builder =>
    builder.getLimitBuilder
      .setInput(plan.getRoot)
      .setLimit(n)
  }

  /**
   * Returns a new Dataset by skipping the first `n` rows.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def offset(n: Int): Dataset[T] = session.newDataset { builder =>
    builder.getOffsetBuilder
      .setInput(plan.getRoot)
      .setOffset(n)
  }

  private def buildSetOp(right: Dataset[T], setOpType: proto.SetOperation.SetOpType)(
      f: proto.SetOperation.Builder => Unit): Dataset[T] = {
    session.newDataset { builder =>
      f(
        builder.getSetOpBuilder
          .setSetOpType(setOpType)
          .setLeftInput(plan.getRoot)
          .setRightInput(right.plan.getRoot))
    }
  }

  /**
   * Returns a new Dataset containing union of rows in this Dataset and another Dataset.
   *
   * This is equivalent to `UNION ALL` in SQL. To do a SQL-style set union (that does
   * deduplication of elements), use this function followed by a [[distinct]].
   *
   * Also as standard in SQL, this function resolves columns by position (not by name):
   *
   * {{{
   *   val df1 = Seq((1, 2, 3)).toDF("col0", "col1", "col2")
   *   val df2 = Seq((4, 5, 6)).toDF("col1", "col2", "col0")
   *   df1.union(df2).show
   *
   *   // output:
   *   // +----+----+----+
   *   // |col0|col1|col2|
   *   // +----+----+----+
   *   // |   1|   2|   3|
   *   // |   4|   5|   6|
   *   // +----+----+----+
   * }}}
   *
   * Notice that the column positions in the schema aren't necessarily matched with the fields in
   * the strongly typed objects in a Dataset. This function resolves columns by their positions in
   * the schema, not the fields in the strongly typed objects. Use [[unionByName]] to resolve
   * columns by field name in the typed objects.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def union(other: Dataset[T]): Dataset[T] = {
    buildSetOp(other, proto.SetOperation.SetOpType.SET_OP_TYPE_UNION) { builder =>
      builder.setIsAll(true)
    }
  }

  /**
   * Returns a new Dataset containing union of rows in this Dataset and another Dataset. This is
   * an alias for `union`.
   *
   * This is equivalent to `UNION ALL` in SQL. To do a SQL-style set union (that does
   * deduplication of elements), use this function followed by a [[distinct]].
   *
   * Also as standard in SQL, this function resolves columns by position (not by name).
   *
   * @group typedrel
   * @since 3.4.0
   */
  def unionAll(other: Dataset[T]): Dataset[T] = union(other)

  /**
   * Returns a new Dataset containing union of rows in this Dataset and another Dataset.
   *
   * This is different from both `UNION ALL` and `UNION DISTINCT` in SQL. To do a SQL-style set
   * union (that does deduplication of elements), use this function followed by a [[distinct]].
   *
   * The difference between this function and [[union]] is that this function resolves columns by
   * name (not by position):
   *
   * {{{
   *   val df1 = Seq((1, 2, 3)).toDF("col0", "col1", "col2")
   *   val df2 = Seq((4, 5, 6)).toDF("col1", "col2", "col0")
   *   df1.unionByName(df2).show
   *
   *   // output:
   *   // +----+----+----+
   *   // |col0|col1|col2|
   *   // +----+----+----+
   *   // |   1|   2|   3|
   *   // |   6|   4|   5|
   *   // +----+----+----+
   * }}}
   *
   * Note that this supports nested columns in struct and array types. Nested columns in map types
   * are not currently supported.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def unionByName(other: Dataset[T]): Dataset[T] = unionByName(other, allowMissingColumns = false)

  /**
   * Returns a new Dataset containing union of rows in this Dataset and another Dataset.
   *
   * The difference between this function and [[union]] is that this function resolves columns by
   * name (not by position).
   *
   * When the parameter `allowMissingColumns` is `true`, the set of column names in this and other
   * `Dataset` can differ; missing columns will be filled with null. Further, the missing columns
   * of this `Dataset` will be added at the end in the schema of the union result:
   *
   * {{{
   *   val df1 = Seq((1, 2, 3)).toDF("col0", "col1", "col2")
   *   val df2 = Seq((4, 5, 6)).toDF("col1", "col0", "col3")
   *   df1.unionByName(df2, true).show
   *
   *   // output: "col3" is missing at left df1 and added at the end of schema.
   *   // +----+----+----+----+
   *   // |col0|col1|col2|col3|
   *   // +----+----+----+----+
   *   // |   1|   2|   3|null|
   *   // |   5|   4|null|   6|
   *   // +----+----+----+----+
   *
   *   df2.unionByName(df1, true).show
   *
   *   // output: "col2" is missing at left df2 and added at the end of schema.
   *   // +----+----+----+----+
   *   // |col1|col0|col3|col2|
   *   // +----+----+----+----+
   *   // |   4|   5|   6|null|
   *   // |   2|   1|null|   3|
   *   // +----+----+----+----+
   * }}}
   *
   * Note that this supports nested columns in struct and array types. With `allowMissingColumns`,
   * missing nested columns of struct columns with the same name will also be filled with null
   * values and added to the end of struct. Nested columns in map types are not currently
   * supported.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def unionByName(other: Dataset[T], allowMissingColumns: Boolean): Dataset[T] = {
    buildSetOp(other, proto.SetOperation.SetOpType.SET_OP_TYPE_UNION) { builder =>
      builder.setByName(true).setIsAll(true).setAllowMissingColumns(allowMissingColumns)
    }
  }

  /**
   * Returns a new Dataset containing rows only in both this Dataset and another Dataset. This is
   * equivalent to `INTERSECT` in SQL.
   *
   * @note
   *   Equality checking is performed directly on the encoded representation of the data and thus
   *   is not affected by a custom `equals` function defined on `T`.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def intersect(other: Dataset[T]): Dataset[T] = {
    buildSetOp(other, proto.SetOperation.SetOpType.SET_OP_TYPE_INTERSECT) { builder =>
      builder.setIsAll(false)
    }
  }

  /**
   * Returns a new Dataset containing rows only in both this Dataset and another Dataset while
   * preserving the duplicates. This is equivalent to `INTERSECT ALL` in SQL.
   *
   * @note
   *   Equality checking is performed directly on the encoded representation of the data and thus
   *   is not affected by a custom `equals` function defined on `T`. Also as standard in SQL, this
   *   function resolves columns by position (not by name).
   *
   * @group typedrel
   * @since 3.4.0
   */
  def intersectAll(other: Dataset[T]): Dataset[T] = {
    buildSetOp(other, proto.SetOperation.SetOpType.SET_OP_TYPE_INTERSECT) { builder =>
      builder.setIsAll(true)
    }
  }

  /**
   * Returns a new Dataset containing rows in this Dataset but not in another Dataset. This is
   * equivalent to `EXCEPT DISTINCT` in SQL.
   *
   * @note
   *   Equality checking is performed directly on the encoded representation of the data and thus
   *   is not affected by a custom `equals` function defined on `T`.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def except(other: Dataset[T]): Dataset[T] = {
    buildSetOp(other, proto.SetOperation.SetOpType.SET_OP_TYPE_EXCEPT) { builder =>
      builder.setIsAll(false)
    }
  }

  /**
   * Returns a new Dataset containing rows in this Dataset but not in another Dataset while
   * preserving the duplicates. This is equivalent to `EXCEPT ALL` in SQL.
   *
   * @note
   *   Equality checking is performed directly on the encoded representation of the data and thus
   *   is not affected by a custom `equals` function defined on `T`. Also as standard in SQL, this
   *   function resolves columns by position (not by name).
   *
   * @group typedrel
   * @since 3.4.0
   */
  def exceptAll(other: Dataset[T]): Dataset[T] = {
    buildSetOp(other, proto.SetOperation.SetOpType.SET_OP_TYPE_EXCEPT) { builder =>
      builder.setIsAll(true)
    }
  }

  /**
   * Returns a new [[Dataset]] by sampling a fraction of rows (without replacement), using a
   * user-supplied seed.
   *
   * @param fraction
   *   Fraction of rows to generate, range [0.0, 1.0].
   * @param seed
   *   Seed for sampling.
   *
   * @note
   *   This is NOT guaranteed to provide exactly the fraction of the count of the given
   *   [[Dataset]].
   *
   * @group typedrel
   * @since 3.4.0
   */
  def sample(fraction: Double, seed: Long): Dataset[T] = {
    sample(withReplacement = false, fraction = fraction, seed = seed)
  }

  /**
   * Returns a new [[Dataset]] by sampling a fraction of rows (without replacement), using a
   * random seed.
   *
   * @param fraction
   *   Fraction of rows to generate, range [0.0, 1.0].
   *
   * @note
   *   This is NOT guaranteed to provide exactly the fraction of the count of the given
   *   [[Dataset]].
   *
   * @group typedrel
   * @since 3.4.0
   */
  def sample(fraction: Double): Dataset[T] = {
    sample(withReplacement = false, fraction = fraction)
  }

  /**
   * Returns a new [[Dataset]] by sampling a fraction of rows, using a user-supplied seed.
   *
   * @param withReplacement
   *   Sample with replacement or not.
   * @param fraction
   *   Fraction of rows to generate, range [0.0, 1.0].
   * @param seed
   *   Seed for sampling.
   *
   * @note
   *   This is NOT guaranteed to provide exactly the fraction of the count of the given
   *   [[Dataset]].
   *
   * @group typedrel
   * @since 3.4.0
   */
  def sample(withReplacement: Boolean, fraction: Double, seed: Long): Dataset[T] = {
    session.newDataset { builder =>
      builder.getSampleBuilder
        .setInput(plan.getRoot)
        .setWithReplacement(withReplacement)
        .setLowerBound(0.0d)
        .setUpperBound(fraction)
        .setSeed(seed)
    }
  }

  /**
   * Returns a new [[Dataset]] by sampling a fraction of rows, using a random seed.
   *
   * @param withReplacement
   *   Sample with replacement or not.
   * @param fraction
   *   Fraction of rows to generate, range [0.0, 1.0].
   *
   * @note
   *   This is NOT guaranteed to provide exactly the fraction of the total count of the given
   *   [[Dataset]].
   *
   * @group typedrel
   * @since 3.4.0
   */
  def sample(withReplacement: Boolean, fraction: Double): Dataset[T] = {
    sample(withReplacement, fraction, Utils.random.nextLong)
  }

  /**
   * Randomly splits this Dataset with the provided weights.
   *
   * @param weights
   *   weights for splits, will be normalized if they don't sum to 1.
   * @param seed
   *   Seed for sampling.
   *
   * For Java API, use [[randomSplitAsList]].
   *
   * @group typedrel
   * @since 3.4.0
   */
  def randomSplit(weights: Array[Double], seed: Long): Array[Dataset[T]] = {
    require(
      weights.forall(_ >= 0),
      s"Weights must be nonnegative, but got ${weights.mkString("[", ",", "]")}")
    require(
      weights.sum > 0,
      s"Sum of weights must be positive, but got ${weights.mkString("[", ",", "]")}")

    // It is possible that the underlying dataframe doesn't guarantee the ordering of rows in its
    // constituent partitions each time a split is materialized which could result in
    // overlapping splits. To prevent this, we explicitly sort each input partition to make the
    // ordering deterministic. Note that MapTypes cannot be sorted and are explicitly pruned out
    // from the sort order.
    // TODO we need to have a proper way of stabilizing the input data. The current approach does
    //  not work well with spark connects' extremely lazy nature. When the schema is modified
    //  between construction and execution the query might fail or produce wrong results. Another
    //  problem can come from data that arrives between the execution of the returned datasets.
    val sortOrder = schema.collect {
      case f if RowOrdering.isOrderable(f.dataType) => col(f.name).asc
    }
    val sortedInput = sortWithinPartitions(sortOrder: _*).plan.getRoot
    val sum = weights.sum
    val normalizedCumWeights = weights.map(_ / sum).scanLeft(0.0d)(_ + _)
    normalizedCumWeights
      .sliding(2)
      .map { case Array(low, high) =>
        session.newDataset[T] { builder =>
          builder.getSampleBuilder
            .setInput(sortedInput)
            .setWithReplacement(false)
            .setLowerBound(low)
            .setUpperBound(high)
            .setSeed(seed)
        }
      }
      .toArray
  }

  /**
   * Returns a Java list that contains randomly split Dataset with the provided weights.
   *
   * @param weights
   *   weights for splits, will be normalized if they don't sum to 1.
   * @param seed
   *   Seed for sampling.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def randomSplitAsList(weights: Array[Double], seed: Long): java.util.List[Dataset[T]] = {
    val values = randomSplit(weights, seed)
    java.util.Arrays.asList(values: _*)
  }

  /**
   * Randomly splits this Dataset with the provided weights.
   *
   * @param weights
   *   weights for splits, will be normalized if they don't sum to 1.
   * @group typedrel
   * @since 3.4.0
   */
  def randomSplit(weights: Array[Double]): Array[Dataset[T]] = {
    randomSplit(weights, Utils.random.nextLong)
  }

  private def withColumns(names: Seq[String], values: Seq[Column]): DataFrame = {
    val aliases = values.zip(names).map { case (value, name) =>
      value.name(name).expr.getAlias
    }
    session.newDataset { builder =>
      builder.getWithColumnsBuilder
        .setInput(plan.getRoot)
        .addAllAliases(aliases.asJava)
    }
  }

  /**
   * Returns a new Dataset by adding a column or replacing the existing column that has the same
   * name.
   *
   * `column`'s expression must only refer to attributes supplied by this Dataset. It is an error
   * to add a column that refers to some other Dataset.
   *
   * @note
   *   this method introduces a projection internally. Therefore, calling it multiple times, for
   *   instance, via loops in order to add multiple columns can generate big plans which can cause
   *   performance issues and even `StackOverflowException`. To avoid this, use `select` with the
   *   multiple columns at once.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def withColumn(colName: String, col: Column): DataFrame = withColumns(Seq(colName), Seq(col))

  /**
   * (Scala-specific) Returns a new Dataset by adding columns or replacing the existing columns
   * that has the same names.
   *
   * `colsMap` is a map of column name and column, the column must only refer to attributes
   * supplied by this Dataset. It is an error to add columns that refers to some other Dataset.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def withColumns(colsMap: Map[String, Column]): DataFrame = {
    val (colNames, newCols) = colsMap.toSeq.unzip
    withColumns(colNames, newCols)
  }

  /**
   * (Java-specific) Returns a new Dataset by adding columns or replacing the existing columns
   * that has the same names.
   *
   * `colsMap` is a map of column name and column, the column must only refer to attribute
   * supplied by this Dataset. It is an error to add columns that refers to some other Dataset.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def withColumns(colsMap: java.util.Map[String, Column]): DataFrame = withColumns(
    colsMap.asScala.toMap)

  /**
   * Returns a new Dataset with a column renamed. This is a no-op if schema doesn't contain
   * existingName.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def withColumnRenamed(existingName: String, newName: String): DataFrame = {
    withColumnsRenamed(Collections.singletonMap(existingName, newName))
  }

  /**
   * (Scala-specific) Returns a new Dataset with a columns renamed. This is a no-op if schema
   * doesn't contain existingName.
   *
   * `colsMap` is a map of existing column name and new column name.
   *
   * @throws AnalysisException
   *   if there are duplicate names in resulting projection
   *
   * @group untypedrel
   * @since 3.4.0
   */
  @throws[AnalysisException]
  def withColumnsRenamed(colsMap: Map[String, String]): DataFrame = {
    withColumnsRenamed(colsMap.asJava)
  }

  /**
   * (Java-specific) Returns a new Dataset with a columns renamed. This is a no-op if schema
   * doesn't contain existingName.
   *
   * `colsMap` is a map of existing column name and new column name.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def withColumnsRenamed(colsMap: java.util.Map[String, String]): DataFrame = {
    session.newDataset { builder =>
      builder.getWithColumnsRenamedBuilder
        .setInput(plan.getRoot)
        .putAllRenameColumnsMap(colsMap)
    }
  }

  /**
   * Returns a new Dataset by updating an existing column with metadata.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def withMetadata(columnName: String, metadata: Metadata): DataFrame = {
    val newAlias = proto.Expression.Alias
      .newBuilder()
      .setExpr(col(columnName).expr)
      .addName(columnName)
      .setMetadata(metadata.json)
    session.newDataset { builder =>
      builder.getWithColumnsBuilder
        .setInput(plan.getRoot)
        .addAliases(newAlias)
    }
  }

  /**
   * Returns a new Dataset with a column dropped. This is a no-op if schema doesn't contain column
   * name.
   *
   * This method can only be used to drop top level columns. the colName string is treated
   * literally without further interpretation.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def drop(colName: String): DataFrame = {
    drop(functions.col(colName))
  }

  /**
   * Returns a new Dataset with columns dropped. This is a no-op if schema doesn't contain column
   * name(s).
   *
   * This method can only be used to drop top level columns. the colName string is treated
   * literally without further interpretation.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def drop(colNames: String*): DataFrame = buildDrop(colNames.map(functions.col))

  /**
   * Returns a new Dataset with column dropped.
   *
   * This method can only be used to drop top level column. This version of drop accepts a
   * [[Column]] rather than a name. This is a no-op if the Dataset doesn't have a column with an
   * equivalent expression.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  def drop(col: Column): DataFrame = {
    buildDrop(col :: Nil)
  }

  /**
   * Returns a new Dataset with columns dropped.
   *
   * This method can only be used to drop top level columns. This is a no-op if the Dataset
   * doesn't have a columns with an equivalent expression.
   *
   * @group untypedrel
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def drop(col: Column, cols: Column*): DataFrame = buildDrop(col +: cols)

  private def buildDrop(cols: Seq[Column]): DataFrame = session.newDataset { builder =>
    builder.getDropBuilder
      .setInput(plan.getRoot)
      .addAllCols(cols.map(_.expr).asJava)
  }

  /**
   * Returns a new Dataset that contains only the unique rows from this Dataset. This is an alias
   * for `distinct`.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def dropDuplicates(): Dataset[T] = session.newDataset { builder =>
    builder.getDeduplicateBuilder
      .setInput(plan.getRoot)
      .setAllColumnsAsKeys(true)
  }

  /**
   * (Scala-specific) Returns a new Dataset with duplicate rows removed, considering only the
   * subset of columns.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def dropDuplicates(colNames: Seq[String]): Dataset[T] = session.newDataset { builder =>
    builder.getDeduplicateBuilder
      .setInput(plan.getRoot)
      .addAllColumnNames(colNames.asJava)
  }

  /**
   * Returns a new Dataset with duplicate rows removed, considering only the subset of columns.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def dropDuplicates(colNames: Array[String]): Dataset[T] = dropDuplicates(colNames.toSeq)

  /**
   * Returns a new [[Dataset]] with duplicate rows removed, considering only the subset of
   * columns.
   *
   * @group typedrel
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def dropDuplicates(col1: String, cols: String*): Dataset[T] = {
    val colNames: Seq[String] = col1 +: cols
    dropDuplicates(colNames)
  }

  /**
   * Computes basic statistics for numeric and string columns, including count, mean, stddev, min,
   * and max. If no columns are given, this function computes statistics for all numerical or
   * string columns.
   *
   * This function is meant for exploratory data analysis, as we make no guarantee about the
   * backward compatibility of the schema of the resulting Dataset. If you want to
   * programmatically compute summary statistics, use the `agg` function instead.
   *
   * {{{
   *   ds.describe("age", "height").show()
   *
   *   // output:
   *   // summary age   height
   *   // count   10.0  10.0
   *   // mean    53.3  178.05
   *   // stddev  11.6  15.7
   *   // min     18.0  163.0
   *   // max     92.0  192.0
   * }}}
   *
   * Use [[summary]] for expanded statistics and control over which statistics to compute.
   *
   * @param cols
   *   Columns to compute statistics on.
   *
   * @group action
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def describe(cols: String*): DataFrame = session.newDataset { builder =>
    builder.getDescribeBuilder
      .setInput(plan.getRoot)
      .addAllCols(cols.asJava)
  }

  /**
   * Computes specified statistics for numeric and string columns. Available statistics are: <ul>
   * <li>count</li> <li>mean</li> <li>stddev</li> <li>min</li> <li>max</li> <li>arbitrary
   * approximate percentiles specified as a percentage (e.g. 75%)</li> <li>count_distinct</li>
   * <li>approx_count_distinct</li> </ul>
   *
   * If no statistics are given, this function computes count, mean, stddev, min, approximate
   * quartiles (percentiles at 25%, 50%, and 75%), and max.
   *
   * This function is meant for exploratory data analysis, as we make no guarantee about the
   * backward compatibility of the schema of the resulting Dataset. If you want to
   * programmatically compute summary statistics, use the `agg` function instead.
   *
   * {{{
   *   ds.summary().show()
   *
   *   // output:
   *   // summary age   height
   *   // count   10.0  10.0
   *   // mean    53.3  178.05
   *   // stddev  11.6  15.7
   *   // min     18.0  163.0
   *   // 25%     24.0  176.0
   *   // 50%     24.0  176.0
   *   // 75%     32.0  180.0
   *   // max     92.0  192.0
   * }}}
   *
   * {{{
   *   ds.summary("count", "min", "25%", "75%", "max").show()
   *
   *   // output:
   *   // summary age   height
   *   // count   10.0  10.0
   *   // min     18.0  163.0
   *   // 25%     24.0  176.0
   *   // 75%     32.0  180.0
   *   // max     92.0  192.0
   * }}}
   *
   * To do a summary for specific columns first select them:
   *
   * {{{
   *   ds.select("age", "height").summary().show()
   * }}}
   *
   * Specify statistics to output custom summaries:
   *
   * {{{
   *   ds.summary("count", "count_distinct").show()
   * }}}
   *
   * The distinct count isn't included by default.
   *
   * You can also run approximate distinct counts which are faster:
   *
   * {{{
   *   ds.summary("count", "approx_count_distinct").show()
   * }}}
   *
   * See also [[describe]] for basic statistics.
   *
   * @param statistics
   *   Statistics from above list to be computed.
   *
   * @group action
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def summary(statistics: String*): DataFrame = session.newDataset { builder =>
    builder.getSummaryBuilder
      .setInput(plan.getRoot)
      .addAllStatistics(statistics.asJava)
  }

  /**
   * Returns the first `n` rows.
   *
   * @note
   *   this method should only be used if the resulting array is expected to be small, as all the
   *   data is loaded into the driver's memory.
   *
   * @group action
   * @since 3.4.0
   */
  def head(n: Int): Array[T] = limit(n).collect()

  /**
   * Returns the first row.
   * @group action
   * @since 3.4.0
   */
  def head(): T = head(1).head

  /**
   * Returns the first row. Alias for head().
   * @group action
   * @since 3.4.0
   */
  def first(): T = head()

  /**
   * Concise syntax for chaining custom transformations.
   * {{{
   *   def featurize(ds: Dataset[T]): Dataset[U] = ...
   *
   *   ds
   *     .transform(featurize)
   *     .transform(...)
   * }}}
   *
   * @group typedrel
   * @since 3.4.0
   */
  def transform[U](t: Dataset[T] => Dataset[U]): Dataset[U] = t(this)

  /**
   * Returns the first `n` rows in the Dataset.
   *
   * Running take requires moving data into the application's driver process, and doing so with a
   * very large `n` can crash the driver process with OutOfMemoryError.
   *
   * @group action
   * @since 3.4.0
   */
  def take(n: Int): Array[T] = head(n)

  /**
   * Returns the last `n` rows in the Dataset.
   *
   * Running tail requires moving data into the application's driver process, and doing so with a
   * very large `n` can crash the driver process with OutOfMemoryError.
   *
   * @group action
   * @since 3.4.0
   */
  def tail(n: Int): Array[T] = {
    val lastN = session.newDataset[T] { builder =>
      builder.getTailBuilder
        .setInput(plan.getRoot)
        .setLimit(n)
    }
    lastN.collect()
  }

  /**
   * Returns the first `n` rows in the Dataset as a list.
   *
   * Running take requires moving data into the application's driver process, and doing so with a
   * very large `n` can crash the driver process with OutOfMemoryError.
   *
   * @group action
   * @since 3.4.0
   */
  def takeAsList(n: Int): java.util.List[T] = java.util.Arrays.asList(take(n): _*)

  /**
   * Returns an array that contains all rows in this Dataset.
   *
   * Running collect requires moving all the data into the application's driver process, and doing
   * so on a very large dataset can crash the driver process with OutOfMemoryError.
   *
   * For Java API, use [[collectAsList]].
   *
   * @group action
   * @since 3.4.0
   */
  def collect(): Array[T] = withResult { result =>
    result.toArray.asInstanceOf[Array[T]]
  }

  /**
   * Returns a Java list that contains all rows in this Dataset.
   *
   * Running collect requires moving all the data into the application's driver process, and doing
   * so on a very large dataset can crash the driver process with OutOfMemoryError.
   *
   * @group action
   * @since 3.4.0
   */
  def collectAsList(): java.util.List[T] = {
    java.util.Arrays.asList(collect(): _*)
  }

  /**
   * Returns an iterator that contains all rows in this Dataset.
   *
   * The returned iterator implements [[AutoCloseable]]. For memory management it is better to
   * close it once you are done. If you don't close it, it and the underlying data will be cleaned
   * up once the iterator is garbage collected.
   *
   * @group action
   * @since 3.4.0
   */
  def toLocalIterator(): java.util.Iterator[T] = {
    // TODO make this a destructive iterator.
    collectResult().iterator.asInstanceOf[java.util.Iterator[T]]
  }

  private def buildRepartition(numPartitions: Int, shuffle: Boolean): Dataset[T] = {
    session.newDataset { builder =>
      builder.getRepartitionBuilder
        .setInput(plan.getRoot)
        .setNumPartitions(numPartitions)
        .setShuffle(shuffle)
    }
  }

  private def buildRepartitionByExpression(
      numPartitions: Option[Int],
      partitionExprs: Seq[Column]): Dataset[T] = session.newDataset { builder =>
    val repartitionBuilder = builder.getRepartitionByExpressionBuilder
      .setInput(plan.getRoot)
      .addAllPartitionExprs(partitionExprs.map(_.expr).asJava)
    numPartitions.foreach(repartitionBuilder.setNumPartitions)
  }

  /**
   * Returns a new Dataset that has exactly `numPartitions` partitions.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def repartition(numPartitions: Int): Dataset[T] = {
    buildRepartition(numPartitions, shuffle = true)
  }

  private def repartitionByExpression(
      numPartitions: Option[Int],
      partitionExprs: Seq[Column]): Dataset[T] = {
    // The underlying `LogicalPlan` operator special-cases all-`SortOrder` arguments.
    // However, we don't want to complicate the semantics of this API method.
    // Instead, let's give users a friendly error message, pointing them to the new method.
    val sortOrders = partitionExprs.filter(_.expr.hasSortOrder)
    if (sortOrders.nonEmpty) {
      throw new IllegalArgumentException(
        s"Invalid partitionExprs specified: $sortOrders\n" +
          s"For range partitioning use repartitionByRange(...) instead.")
    }
    buildRepartitionByExpression(numPartitions, partitionExprs)
  }

  /**
   * Returns a new Dataset partitioned by the given partitioning expressions into `numPartitions`.
   * The resulting Dataset is hash partitioned.
   *
   * This is the same operation as "DISTRIBUTE BY" in SQL (Hive QL).
   *
   * @group typedrel
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def repartition(numPartitions: Int, partitionExprs: Column*): Dataset[T] = {
    repartitionByExpression(Some(numPartitions), partitionExprs)
  }

  /**
   * Returns a new Dataset partitioned by the given partitioning expressions, using
   * `spark.sql.shuffle.partitions` as number of partitions. The resulting Dataset is hash
   * partitioned.
   *
   * This is the same operation as "DISTRIBUTE BY" in SQL (Hive QL).
   *
   * @group typedrel
   * @since 2.0.0
   */
  @scala.annotation.varargs
  def repartition(partitionExprs: Column*): Dataset[T] = {
    repartitionByExpression(None, partitionExprs)
  }

  private def repartitionByRange(
      numPartitions: Option[Int],
      partitionExprs: Seq[Column]): Dataset[T] = {
    require(partitionExprs.nonEmpty, "At least one partition-by expression must be specified.")
    val sortExprs = partitionExprs.map {
      case e if e.expr.hasSortOrder => e
      case e => e.asc
    }
    buildRepartitionByExpression(numPartitions, sortExprs)
  }

  /**
   * Returns a new Dataset partitioned by the given partitioning expressions into `numPartitions`.
   * The resulting Dataset is range partitioned.
   *
   * At least one partition-by expression must be specified. When no explicit sort order is
   * specified, "ascending nulls first" is assumed. Note, the rows are not sorted in each
   * partition of the resulting Dataset.
   *
   * Note that due to performance reasons this method uses sampling to estimate the ranges. Hence,
   * the output may not be consistent, since sampling can return different values. The sample size
   * can be controlled by the config `spark.sql.execution.rangeExchange.sampleSizePerPartition`.
   *
   * @group typedrel
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def repartitionByRange(numPartitions: Int, partitionExprs: Column*): Dataset[T] = {
    repartitionByRange(Some(numPartitions), partitionExprs)
  }

  /**
   * Returns a new Dataset partitioned by the given partitioning expressions, using
   * `spark.sql.shuffle.partitions` as number of partitions. The resulting Dataset is range
   * partitioned.
   *
   * At least one partition-by expression must be specified. When no explicit sort order is
   * specified, "ascending nulls first" is assumed. Note, the rows are not sorted in each
   * partition of the resulting Dataset.
   *
   * Note that due to performance reasons this method uses sampling to estimate the ranges. Hence,
   * the output may not be consistent, since sampling can return different values. The sample size
   * can be controlled by the config `spark.sql.execution.rangeExchange.sampleSizePerPartition`.
   *
   * @group typedrel
   * @since 3.4.0
   */
  @scala.annotation.varargs
  def repartitionByRange(partitionExprs: Column*): Dataset[T] = {
    repartitionByRange(None, partitionExprs)
  }

  /**
   * Returns a new Dataset that has exactly `numPartitions` partitions, when the fewer partitions
   * are requested. If a larger number of partitions is requested, it will stay at the current
   * number of partitions. Similar to coalesce defined on an `RDD`, this operation results in a
   * narrow dependency, e.g. if you go from 1000 partitions to 100 partitions, there will not be a
   * shuffle, instead each of the 100 new partitions will claim 10 of the current partitions.
   *
   * However, if you're doing a drastic coalesce, e.g. to numPartitions = 1, this may result in
   * your computation taking place on fewer nodes than you like (e.g. one node in the case of
   * numPartitions = 1). To avoid this, you can call repartition. This will add a shuffle step,
   * but means the current upstream partitions will be executed in parallel (per whatever the
   * current partitioning is).
   *
   * @group typedrel
   * @since 3.4.0
   */
  def coalesce(numPartitions: Int): Dataset[T] = {
    buildRepartition(numPartitions, shuffle = false)
  }

  /**
   * Returns a new Dataset that contains only the unique rows from this Dataset. This is an alias
   * for `dropDuplicates`.
   *
   * Note that for a streaming [[Dataset]], this method returns distinct rows only once regardless
   * of the output mode, which the behavior may not be same with `DISTINCT` in SQL against
   * streaming [[Dataset]].
   *
   * @note
   *   Equality checking is performed directly on the encoded representation of the data and thus
   *   is not affected by a custom `equals` function defined on `T`.
   *
   * @group typedrel
   * @since 3.4.0
   */
  def distinct(): Dataset[T] = dropDuplicates()

  /**
   * Returns a best-effort snapshot of the files that compose this Dataset. This method simply
   * asks each constituent BaseRelation for its respective files and takes the union of all
   * results. Depending on the source relations, this may not find all input files. Duplicates are
   * removed.
   *
   * @group basic
   * @since 3.4.0
   */
  def inputFiles: Array[String] = analyze.getInputFilesList.asScala.toArray

  private[sql] def analyze: proto.AnalyzePlanResponse = {
    session.analyze(plan, proto.Explain.ExplainMode.SIMPLE)
  }

  def collectResult(): SparkResult = session.execute(plan)

  private[sql] def withResult[E](f: SparkResult => E): E = {
    val result = collectResult()
    try f(result)
    finally {
      result.close()
    }
  }
}
