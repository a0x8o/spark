#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
from typing import Any
import unittest
import shutil
import tempfile

import grpc  # type: ignore

from pyspark.testing.sqlutils import have_pandas, SQLTestUtils

if have_pandas:
    import pandas

from pyspark.sql import SparkSession, Row
from pyspark.sql.types import StructType, StructField, LongType, StringType

if have_pandas:
    from pyspark.sql.connect.session import SparkSession as RemoteSparkSession
    from pyspark.sql.connect.client import ChannelBuilder
    from pyspark.sql.connect.dataframe import DataFrame as CDataFrame
    from pyspark.sql.connect.function_builder import udf
    from pyspark.sql.connect.functions import lit, col
    from pyspark.testing.pandasutils import PandasOnSparkTestCase
else:
    from pyspark.testing.sqlutils import ReusedSQLTestCase as PandasOnSparkTestCase  # type: ignore
from pyspark.sql.dataframe import DataFrame
import pyspark.sql.functions
from pyspark.testing.connectutils import should_test_connect, connect_requirement_message
from pyspark.testing.utils import ReusedPySparkTestCase


import tempfile


@unittest.skipIf(not should_test_connect, connect_requirement_message)
class SparkConnectSQLTestCase(PandasOnSparkTestCase, ReusedPySparkTestCase, SQLTestUtils):
    """Parent test fixture class for all Spark Connect related
    test cases."""

    if have_pandas:
        connect: RemoteSparkSession
    tbl_name: str
    tbl_name_empty: str
    df_text: "DataFrame"
    spark: SparkSession

    @classmethod
    def setUpClass(cls: Any):
        ReusedPySparkTestCase.setUpClass()
        cls.tempdir = tempfile.NamedTemporaryFile(delete=False)
        cls.hive_available = True
        # Create the new Spark Session
        cls.spark = SparkSession(cls.sc)
        cls.testData = [Row(key=i, value=str(i)) for i in range(100)]
        cls.testDataStr = [Row(key=str(i)) for i in range(100)]
        cls.df = cls.sc.parallelize(cls.testData).toDF()
        cls.df_text = cls.sc.parallelize(cls.testDataStr).toDF()

        cls.tbl_name = "test_connect_basic_table_1"
        cls.tbl_name2 = "test_connect_basic_table_2"
        cls.tbl_name_empty = "test_connect_basic_table_empty"

        # Cleanup test data
        cls.spark_connect_clean_up_test_data()
        # Load test data
        cls.spark_connect_load_test_data()

    @classmethod
    def tearDownClass(cls: Any) -> None:
        cls.spark_connect_clean_up_test_data()
        ReusedPySparkTestCase.tearDownClass()

    @classmethod
    def spark_connect_load_test_data(cls: Any):
        # Setup Remote Spark Session
        cls.connect = RemoteSparkSession.builder.remote().getOrCreate()
        df = cls.spark.createDataFrame([(x, f"{x}") for x in range(100)], ["id", "name"])
        # Since we might create multiple Spark sessions, we need to create global temporary view
        # that is specifically maintained in the "global_temp" schema.
        df.write.saveAsTable(cls.tbl_name)
        df2 = cls.spark.createDataFrame([(x, f"{x}") for x in range(100)], ["col1", "col2"])
        df2.write.saveAsTable(cls.tbl_name2)
        empty_table_schema = StructType(
            [
                StructField("firstname", StringType(), True),
                StructField("middlename", StringType(), True),
                StructField("lastname", StringType(), True),
            ]
        )
        emptyRDD = cls.spark.sparkContext.emptyRDD()
        empty_df = cls.spark.createDataFrame(emptyRDD, empty_table_schema)
        empty_df.write.saveAsTable(cls.tbl_name_empty)

    @classmethod
    def spark_connect_clean_up_test_data(cls: Any) -> None:
        cls.spark.sql("DROP TABLE IF EXISTS {}".format(cls.tbl_name))
        cls.spark.sql("DROP TABLE IF EXISTS {}".format(cls.tbl_name2))
        cls.spark.sql("DROP TABLE IF EXISTS {}".format(cls.tbl_name_empty))


class SparkConnectTests(SparkConnectSQLTestCase):
    def test_simple_read(self):
        df = self.connect.read.table(self.tbl_name)
        data = df.limit(10).toPandas()
        # Check that the limit is applied
        self.assertEqual(len(data.index), 10)

    def test_join_condition_column_list_columns(self):
        left_connect_df = self.connect.read.table(self.tbl_name)
        right_connect_df = self.connect.read.table(self.tbl_name2)
        left_spark_df = self.spark.read.table(self.tbl_name)
        right_spark_df = self.spark.read.table(self.tbl_name2)
        joined_plan = left_connect_df.join(
            other=right_connect_df, on=left_connect_df.id == right_connect_df.col1, how="inner"
        )
        joined_plan2 = left_spark_df.join(
            other=right_spark_df, on=left_spark_df.id == right_spark_df.col1, how="inner"
        )
        self.assert_eq(joined_plan.toPandas(), joined_plan2.toPandas())

        joined_plan3 = left_connect_df.join(
            other=right_connect_df,
            on=[
                left_connect_df.id == right_connect_df.col1,
                left_connect_df.name == right_connect_df.col2,
            ],
            how="inner",
        )
        joined_plan4 = left_spark_df.join(
            other=right_spark_df,
            on=[left_spark_df.id == right_spark_df.col1, left_spark_df.name == right_spark_df.col2],
            how="inner",
        )
        self.assert_eq(joined_plan3.toPandas(), joined_plan4.toPandas())

    def test_collect(self):
        df = self.connect.read.table(self.tbl_name)
        data = df.limit(10).collect()
        self.assertEqual(len(data), 10)
        # Check Row has schema column names.
        self.assertTrue("name" in data[0])
        self.assertTrue("id" in data[0])

    def test_with_columns_renamed(self):
        # SPARK-41312: test DataFrame.withColumnsRenamed()
        self.assertEqual(
            self.connect.read.table(self.tbl_name).withColumnRenamed("id", "id_new").schema,
            self.spark.read.table(self.tbl_name).withColumnRenamed("id", "id_new").schema,
        )
        self.assertEqual(
            self.connect.read.table(self.tbl_name)
            .withColumnsRenamed({"id": "id_new", "name": "name_new"})
            .schema,
            self.spark.read.table(self.tbl_name)
            .withColumnsRenamed({"id": "id_new", "name": "name_new"})
            .schema,
        )

    def test_simple_udf(self):
        def conv_udf(x) -> str:
            return "Martin"

        u = udf(conv_udf)
        df = self.connect.read.table(self.tbl_name)
        result = df.select(u(df.id)).toPandas()
        self.assertIsNotNone(result)

    def test_with_local_data(self):
        """SPARK-41114: Test creating a dataframe using local data"""
        pdf = pandas.DataFrame({"a": [1, 2, 3], "b": ["a", "b", "c"]})
        df = self.connect.createDataFrame(pdf)
        rows = df.filter(df.a == lit(3)).collect()
        self.assertTrue(len(rows) == 1)
        self.assertEqual(rows[0][0], 3)
        self.assertEqual(rows[0][1], "c")

        # Check correct behavior for empty DataFrame
        pdf = pandas.DataFrame({"a": []})
        with self.assertRaises(ValueError):
            self.connect.createDataFrame(pdf)

    def test_simple_explain_string(self):
        df = self.connect.read.table(self.tbl_name).limit(10)
        result = df._explain_string()
        self.assertGreater(len(result), 0)

    def test_schema(self):
        schema = self.connect.read.table(self.tbl_name).schema
        self.assertEqual(
            StructType(
                [StructField("id", LongType(), True), StructField("name", StringType(), True)]
            ),
            schema,
        )

        # test FloatType, DoubleType, DecimalType, StringType, BooleanType, NullType
        query = """
            SELECT * FROM VALUES
            (float(1.0), double(1.0), 1.0, "1", true, NULL),
            (float(2.0), double(2.0), 2.0, "2", false, NULL),
            (float(3.0), double(3.0), NULL, "3", false, NULL)
            AS tab(a, b, c, d, e, f)
            """
        self.assertEqual(
            self.spark.sql(query).schema,
            self.connect.sql(query).schema,
        )

        # test TimestampType, DateType
        query = """
            SELECT * FROM VALUES
            (TIMESTAMP('2019-04-12 15:50:00'), DATE('2022-02-22')),
            (TIMESTAMP('2019-04-12 15:50:00'), NULL),
            (NULL, DATE('2022-02-22'))
            AS tab(a, b)
            """
        self.assertEqual(
            self.spark.sql(query).schema,
            self.connect.sql(query).schema,
        )

        # test DayTimeIntervalType
        query = """ SELECT INTERVAL '100 10:30' DAY TO MINUTE AS interval """
        self.assertEqual(
            self.spark.sql(query).schema,
            self.connect.sql(query).schema,
        )

        # test MapType
        query = """
            SELECT * FROM VALUES
            (MAP('a', 'ab'), MAP('a', 'ab'), MAP(1, 2, 3, 4)),
            (MAP('x', 'yz'), MAP('x', NULL), NULL),
            (MAP('c', 'de'), NULL, MAP(-1, NULL, -3, -4))
            AS tab(a, b, c)
            """
        self.assertEqual(
            self.spark.sql(query).schema,
            self.connect.sql(query).schema,
        )

        # test ArrayType
        query = """
            SELECT * FROM VALUES
            (ARRAY('a', 'ab'), ARRAY(1, 2, 3), ARRAY(1, NULL, 3)),
            (ARRAY('x', NULL), NULL, ARRAY(1, 3)),
            (NULL, ARRAY(-1, -2, -3), Array())
            AS tab(a, b, c)
            """
        self.assertEqual(
            self.spark.sql(query).schema,
            self.connect.sql(query).schema,
        )

        # test StructType
        query = """
            SELECT STRUCT(a, b, c, d), STRUCT(e, f, g), STRUCT(STRUCT(a, b), STRUCT(h)) FROM VALUES
            (float(1.0), double(1.0), 1.0, "1", true, NULL, ARRAY(1, NULL, 3), MAP(1, 2, 3, 4)),
            (float(2.0), double(2.0), 2.0, "2", false, NULL, ARRAY(1, 3), MAP(1, NULL, 3, 4)),
            (float(3.0), double(3.0), NULL, "3", false, NULL, ARRAY(NULL), NULL)
            AS tab(a, b, c, d, e, f, g, h)
            """
        # compare the __repr__() to ignore the metadata for now
        # the metadata is not supported in Connect for now
        self.assertEqual(
            self.spark.sql(query).schema.__repr__(),
            self.connect.sql(query).schema.__repr__(),
        )

    def test_toDF(self):
        # SPARK-41310: test DataFrame.toDF()
        self.assertEqual(
            self.connect.read.table(self.tbl_name).toDF("col1", "col2").schema,
            self.spark.read.table(self.tbl_name).toDF("col1", "col2").schema,
        )

    def test_print_schema(self):
        # SPARK-41216: Test print schema
        tree_str = self.connect.sql("SELECT 1 AS X, 2 AS Y")._tree_string()
        # root
        #  |-- X: integer (nullable = false)
        #  |-- Y: integer (nullable = false)
        expected = "root\n |-- X: integer (nullable = false)\n |-- Y: integer (nullable = false)\n"
        self.assertEqual(tree_str, expected)

    def test_is_local(self):
        # SPARK-41216: Test is local
        self.assertTrue(self.connect.sql("SHOW DATABASES").isLocal)
        self.assertFalse(self.connect.read.table(self.tbl_name).isLocal)

    def test_is_streaming(self):
        # SPARK-41216: Test is streaming
        self.assertFalse(self.connect.read.table(self.tbl_name).isStreaming)
        self.assertFalse(self.connect.sql("SELECT 1 AS X LIMIT 0").isStreaming)

    def test_input_files(self):
        # SPARK-41216: Test input files
        tmpPath = tempfile.mkdtemp()
        shutil.rmtree(tmpPath)
        try:
            self.df_text.write.text(tmpPath)

            input_files_list1 = (
                self.spark.read.format("text").schema("id STRING").load(path=tmpPath).inputFiles()
            )
            input_files_list2 = (
                self.connect.read.format("text").schema("id STRING").load(path=tmpPath).inputFiles()
            )

            self.assertTrue(len(input_files_list1) > 0)
            self.assertEqual(len(input_files_list1), len(input_files_list2))
            for file_path in input_files_list2:
                self.assertTrue(file_path in input_files_list1)
        finally:
            shutil.rmtree(tmpPath)

    def test_limit_offset(self):
        df = self.connect.read.table(self.tbl_name)
        pd = df.limit(10).offset(1).toPandas()
        self.assertEqual(9, len(pd.index))
        pd2 = df.offset(98).limit(10).toPandas()
        self.assertEqual(2, len(pd2.index))

    def test_tail(self):
        df = self.connect.read.table(self.tbl_name)
        df2 = self.spark.read.table(self.tbl_name)
        self.assertEqual(df.tail(10), df2.tail(10))

    def test_sql(self):
        pdf = self.connect.sql("SELECT 1").toPandas()
        self.assertEqual(1, len(pdf.index))

    def test_head(self):
        # SPARK-41002: test `head` API in Python Client
        df = self.connect.read.table(self.tbl_name)
        self.assertIsNotNone(len(df.head()))
        self.assertIsNotNone(len(df.head(1)))
        self.assertIsNotNone(len(df.head(5)))
        df2 = self.connect.read.table(self.tbl_name_empty)
        self.assertIsNone(df2.head())

    def test_deduplicate(self):
        # SPARK-41326: test distinct and dropDuplicates.
        df = self.connect.read.table(self.tbl_name)
        df2 = self.spark.read.table(self.tbl_name)
        self.assert_eq(df.distinct().toPandas(), df2.distinct().toPandas())
        self.assert_eq(df.dropDuplicates().toPandas(), df2.dropDuplicates().toPandas())
        self.assert_eq(
            df.dropDuplicates(["name"]).toPandas(), df2.dropDuplicates(["name"]).toPandas()
        )

    def test_first(self):
        # SPARK-41002: test `first` API in Python Client
        df = self.connect.read.table(self.tbl_name)
        self.assertIsNotNone(len(df.first()))
        df2 = self.connect.read.table(self.tbl_name_empty)
        self.assertIsNone(df2.first())

    def test_take(self) -> None:
        # SPARK-41002: test `take` API in Python Client
        df = self.connect.read.table(self.tbl_name)
        self.assertEqual(5, len(df.take(5)))
        df2 = self.connect.read.table(self.tbl_name_empty)
        self.assertEqual(0, len(df2.take(5)))

    def test_drop(self):
        # SPARK-41169: test drop
        query = """
            SELECT * FROM VALUES
            (false, 1, NULL), (false, NULL, 2), (NULL, 3, 3)
            AS tab(a, b, c)
            """

        cdf = self.connect.sql(query)
        sdf = self.spark.sql(query)
        self.assert_eq(
            cdf.drop("a").toPandas(),
            sdf.drop("a").toPandas(),
        )
        self.assert_eq(
            cdf.drop("a", "b").toPandas(),
            sdf.drop("a", "b").toPandas(),
        )
        self.assert_eq(
            cdf.drop("a", "x").toPandas(),
            sdf.drop("a", "x").toPandas(),
        )
        self.assert_eq(
            cdf.drop(cdf.a, cdf.x).toPandas(),
            sdf.drop("a", "x").toPandas(),
        )

    def test_subquery_alias(self) -> None:
        # SPARK-40938: test subquery alias.
        plan_text = (
            self.connect.read.table(self.tbl_name)
            .alias("special_alias")
            ._explain_string(extended=True)
        )
        self.assertTrue("special_alias" in plan_text)

    def test_sort(self):
        # SPARK-41332: test sort
        query = """
            SELECT * FROM VALUES
            (false, 1, NULL), (false, NULL, 2.0), (NULL, 3, 3.0)
            AS tab(a, b, c)
            """
        # +-----+----+----+
        # |    a|   b|   c|
        # +-----+----+----+
        # |false|   1|null|
        # |false|null| 2.0|
        # | null|   3| 3.0|
        # +-----+----+----+

        cdf = self.connect.sql(query)
        sdf = self.spark.sql(query)
        self.assert_eq(
            cdf.sort("a").toPandas(),
            sdf.sort("a").toPandas(),
        )
        self.assert_eq(
            cdf.sort("c").toPandas(),
            sdf.sort("c").toPandas(),
        )
        self.assert_eq(
            cdf.sort("b").toPandas(),
            sdf.sort("b").toPandas(),
        )
        self.assert_eq(
            cdf.sort(cdf.c, "b").toPandas(),
            sdf.sort(sdf.c, "b").toPandas(),
        )
        self.assert_eq(
            cdf.sort(cdf.c.desc(), "b").toPandas(),
            sdf.sort(sdf.c.desc(), "b").toPandas(),
        )
        self.assert_eq(
            cdf.sort(cdf.c.desc(), cdf.a.asc()).toPandas(),
            sdf.sort(sdf.c.desc(), sdf.a.asc()).toPandas(),
        )

    def test_range(self):
        self.assert_eq(
            self.connect.range(start=0, end=10).toPandas(),
            self.spark.range(start=0, end=10).toPandas(),
        )
        self.assert_eq(
            self.connect.range(start=0, end=10, step=3).toPandas(),
            self.spark.range(start=0, end=10, step=3).toPandas(),
        )
        self.assert_eq(
            self.connect.range(start=0, end=10, step=3, numPartitions=2).toPandas(),
            self.spark.range(start=0, end=10, step=3, numPartitions=2).toPandas(),
        )
        # SPARK-41301
        self.assert_eq(
            self.connect.range(10).toPandas(), self.connect.range(start=0, end=10).toPandas()
        )

    def test_create_global_temp_view(self):
        # SPARK-41127: test global temp view creation.
        with self.tempView("view_1"):
            self.connect.sql("SELECT 1 AS X LIMIT 0").createGlobalTempView("view_1")
            self.connect.sql("SELECT 2 AS X LIMIT 1").createOrReplaceGlobalTempView("view_1")
            self.assertTrue(self.spark.catalog.tableExists("global_temp.view_1"))

            # Test when creating a view which is already exists but
            self.assertTrue(self.spark.catalog.tableExists("global_temp.view_1"))
            with self.assertRaises(grpc.RpcError):
                self.connect.sql("SELECT 1 AS X LIMIT 0").createGlobalTempView("view_1")

    def test_create_session_local_temp_view(self):
        # SPARK-41372: test session local temp view creation.
        with self.tempView("view_local_temp"):
            self.connect.sql("SELECT 1 AS X").createTempView("view_local_temp")
            self.assertEqual(self.connect.sql("SELECT * FROM view_local_temp").count(), 1)
            self.connect.sql("SELECT 1 AS X LIMIT 0").createOrReplaceTempView("view_local_temp")
            self.assertEqual(self.connect.sql("SELECT * FROM view_local_temp").count(), 0)

            # Test when creating a view which is already exists but
            with self.assertRaises(grpc.RpcError):
                self.connect.sql("SELECT 1 AS X LIMIT 0").createTempView("view_local_temp")

    def test_to_pandas(self):
        # SPARK-41005: Test to pandas
        query = """
            SELECT * FROM VALUES
            (false, 1, NULL),
            (false, NULL, float(2.0)),
            (NULL, 3, float(3.0))
            AS tab(a, b, c)
            """

        self.assert_eq(
            self.connect.sql(query).toPandas(),
            self.spark.sql(query).toPandas(),
        )

        query = """
            SELECT * FROM VALUES
            (1, 1, NULL),
            (2, NULL, float(2.0)),
            (3, 3, float(3.0))
            AS tab(a, b, c)
            """

        self.assert_eq(
            self.connect.sql(query).toPandas(),
            self.spark.sql(query).toPandas(),
        )

        query = """
            SELECT * FROM VALUES
            (double(1.0), 1, "1"),
            (NULL, NULL, NULL),
            (double(2.0), 3, "3")
            AS tab(a, b, c)
            """

        self.assert_eq(
            self.connect.sql(query).toPandas(),
            self.spark.sql(query).toPandas(),
        )

        query = """
            SELECT * FROM VALUES
            (float(1.0), double(1.0), 1, "1"),
            (float(2.0), double(2.0), 2, "2"),
            (float(3.0), double(3.0), 3, "3")
            AS tab(a, b, c, d)
            """

        self.assert_eq(
            self.connect.sql(query).toPandas(),
            self.spark.sql(query).toPandas(),
        )

    def test_select_expr(self):
        # SPARK-41201: test selectExpr API.
        self.assert_eq(
            self.connect.read.table(self.tbl_name).selectExpr("id * 2").toPandas(),
            self.spark.read.table(self.tbl_name).selectExpr("id * 2").toPandas(),
        )
        self.assert_eq(
            self.connect.read.table(self.tbl_name)
            .selectExpr(["id * 2", "cast(name as long) as name"])
            .toPandas(),
            self.spark.read.table(self.tbl_name)
            .selectExpr(["id * 2", "cast(name as long) as name"])
            .toPandas(),
        )

        self.assert_eq(
            self.connect.read.table(self.tbl_name)
            .selectExpr("id * 2", "cast(name as long) as name")
            .toPandas(),
            self.spark.read.table(self.tbl_name)
            .selectExpr("id * 2", "cast(name as long) as name")
            .toPandas(),
        )

    def test_fill_na(self):
        # SPARK-41128: Test fill na
        query = """
            SELECT * FROM VALUES
            (false, 1, NULL), (false, NULL, 2.0), (NULL, 3, 3.0)
            AS tab(a, b, c)
            """
        # +-----+----+----+
        # |    a|   b|   c|
        # +-----+----+----+
        # |false|   1|null|
        # |false|null| 2.0|
        # | null|   3| 3.0|
        # +-----+----+----+

        self.assert_eq(
            self.connect.sql(query).fillna(True).toPandas(),
            self.spark.sql(query).fillna(True).toPandas(),
        )
        self.assert_eq(
            self.connect.sql(query).fillna(2).toPandas(),
            self.spark.sql(query).fillna(2).toPandas(),
        )
        self.assert_eq(
            self.connect.sql(query).fillna(2, ["a", "b"]).toPandas(),
            self.spark.sql(query).fillna(2, ["a", "b"]).toPandas(),
        )
        self.assert_eq(
            self.connect.sql(query).na.fill({"a": True, "b": 2}).toPandas(),
            self.spark.sql(query).na.fill({"a": True, "b": 2}).toPandas(),
        )

    def test_drop_na(self):
        # SPARK-41148: Test drop na
        query = """
            SELECT * FROM VALUES
            (false, 1, NULL), (false, NULL, 2.0), (NULL, 3, 3.0)
            AS tab(a, b, c)
            """
        # +-----+----+----+
        # |    a|   b|   c|
        # +-----+----+----+
        # |false|   1|null|
        # |false|null| 2.0|
        # | null|   3| 3.0|
        # +-----+----+----+

        self.assert_eq(
            self.connect.sql(query).dropna().toPandas(),
            self.spark.sql(query).dropna().toPandas(),
        )
        self.assert_eq(
            self.connect.sql(query).na.drop(how="all", thresh=1).toPandas(),
            self.spark.sql(query).na.drop(how="all", thresh=1).toPandas(),
        )
        self.assert_eq(
            self.connect.sql(query).dropna(thresh=1, subset=("a", "b")).toPandas(),
            self.spark.sql(query).dropna(thresh=1, subset=("a", "b")).toPandas(),
        )
        self.assert_eq(
            self.connect.sql(query).na.drop(how="any", thresh=2, subset="a").toPandas(),
            self.spark.sql(query).na.drop(how="any", thresh=2, subset="a").toPandas(),
        )

    def test_replace(self):
        # SPARK-41315: Test replace
        query = """
            SELECT * FROM VALUES
            (false, 1, NULL), (false, NULL, 2.0), (NULL, 3, 3.0)
            AS tab(a, b, c)
            """
        # +-----+----+----+
        # |    a|   b|   c|
        # +-----+----+----+
        # |false|   1|null|
        # |false|null| 2.0|
        # | null|   3| 3.0|
        # +-----+----+----+

        self.assert_eq(
            self.connect.sql(query).replace(2, 3).toPandas(),
            self.spark.sql(query).replace(2, 3).toPandas(),
        )
        self.assert_eq(
            self.connect.sql(query).na.replace(False, True).toPandas(),
            self.spark.sql(query).na.replace(False, True).toPandas(),
        )
        self.assert_eq(
            self.connect.sql(query).replace({1: 2, 3: -1}, subset=("a", "b")).toPandas(),
            self.spark.sql(query).replace({1: 2, 3: -1}, subset=("a", "b")).toPandas(),
        )
        self.assert_eq(
            self.connect.sql(query).na.replace((1, 2), (3, 1)).toPandas(),
            self.spark.sql(query).na.replace((1, 2), (3, 1)).toPandas(),
        )
        self.assert_eq(
            self.connect.sql(query).na.replace((1, 2), (3, 1), subset=("c", "b")).toPandas(),
            self.spark.sql(query).na.replace((1, 2), (3, 1), subset=("c", "b")).toPandas(),
        )

        with self.assertRaises(ValueError) as context:
            self.connect.sql(query).replace({None: 1}, subset="a").toPandas()
            self.assertTrue("Mixed type replacements are not supported" in str(context.exception))

        with self.assertRaises(grpc.RpcError) as context:
            self.connect.sql(query).replace({1: 2, 3: -1}, subset=("a", "x")).toPandas()
            self.assertIn(
                """Cannot resolve column name "x" among (a, b, c)""", str(context.exception)
            )

    def test_with_columns(self):
        # SPARK-41256: test withColumn(s).
        self.assert_eq(
            self.connect.read.table(self.tbl_name).withColumn("id", lit(False)).toPandas(),
            self.spark.read.table(self.tbl_name)
            .withColumn("id", pyspark.sql.functions.lit(False))
            .toPandas(),
        )

        self.assert_eq(
            self.connect.read.table(self.tbl_name)
            .withColumns({"id": lit(False), "col_not_exist": lit(False)})
            .toPandas(),
            self.spark.read.table(self.tbl_name)
            .withColumns(
                {
                    "id": pyspark.sql.functions.lit(False),
                    "col_not_exist": pyspark.sql.functions.lit(False),
                }
            )
            .toPandas(),
        )

    def test_empty_dataset(self):
        # SPARK-41005: Test arrow based collection with empty dataset.
        self.assertTrue(
            self.connect.sql("SELECT 1 AS X LIMIT 0")
            .toPandas()
            .equals(self.spark.sql("SELECT 1 AS X LIMIT 0").toPandas())
        )
        pdf = self.connect.sql("SELECT 1 AS X LIMIT 0").toPandas()
        self.assertEqual(0, len(pdf))  # empty dataset
        self.assertEqual(1, len(pdf.columns))  # one column
        self.assertEqual("X", pdf.columns[0])

    def test_is_empty(self):
        # SPARK-41212: Test is empty
        self.assertFalse(self.connect.sql("SELECT 1 AS X").isEmpty())
        self.assertTrue(self.connect.sql("SELECT 1 AS X LIMIT 0").isEmpty())

    def test_session(self):
        self.assertEqual(self.connect, self.connect.sql("SELECT 1").sparkSession())

    def test_show(self):
        # SPARK-41111: Test the show method
        show_str = self.connect.sql("SELECT 1 AS X, 2 AS Y")._show_string()
        # +---+---+
        # |  X|  Y|
        # +---+---+
        # |  1|  2|
        # +---+---+
        expected = "+---+---+\n|  X|  Y|\n+---+---+\n|  1|  2|\n+---+---+\n"
        self.assertEqual(show_str, expected)

    def test_repr(self):
        # SPARK-41213: Test the __repr__ method
        query = """SELECT * FROM VALUES (1L, NULL), (3L, "Z") AS tab(a, b)"""
        self.assertEqual(
            self.connect.sql(query).__repr__(),
            self.spark.sql(query).__repr__(),
        )

    def test_explain_string(self):
        # SPARK-41122: test explain API.
        plan_str = self.connect.sql("SELECT 1")._explain_string(extended=True)
        self.assertTrue("Parsed Logical Plan" in plan_str)
        self.assertTrue("Analyzed Logical Plan" in plan_str)
        self.assertTrue("Optimized Logical Plan" in plan_str)
        self.assertTrue("Physical Plan" in plan_str)

        with self.assertRaises(ValueError) as context:
            self.connect.sql("SELECT 1")._explain_string(mode="unknown")
        self.assertTrue("unknown" in str(context.exception))

    def test_simple_datasource_read(self) -> None:
        writeDf = self.df_text
        tmpPath = tempfile.mkdtemp()
        shutil.rmtree(tmpPath)
        writeDf.write.text(tmpPath)

        readDf = self.connect.read.format("text").schema("id STRING").load(path=tmpPath)
        expectResult = writeDf.collect()
        pandasResult = readDf.toPandas()
        if pandasResult is None:
            self.assertTrue(False, "Empty pandas dataframe")
        else:
            actualResult = pandasResult.values.tolist()
            self.assertEqual(len(expectResult), len(actualResult))

    def test_simple_read_without_schema(self) -> None:
        """SPARK-41300: Schema not set when reading CSV."""
        writeDf = self.df_text
        tmpPath = tempfile.mkdtemp()
        shutil.rmtree(tmpPath)
        writeDf.write.csv(tmpPath, header=True)

        readDf = self.connect.read.format("csv").option("header", True).load(path=tmpPath)
        expectResult = set(writeDf.collect())
        pandasResult = set(readDf.collect())
        self.assertEqual(expectResult, pandasResult)

    def test_count(self) -> None:
        # SPARK-41308: test count() API.
        self.assertEqual(
            self.connect.read.table(self.tbl_name).count(),
            self.spark.read.table(self.tbl_name).count(),
        )

    def test_simple_transform(self) -> None:
        """SPARK-41203: Support DF.transform"""

        def transform_df(input_df: CDataFrame) -> CDataFrame:
            return input_df.select((col("id") + lit(10)).alias("id"))

        df = self.connect.range(1, 100)
        result_left = df.transform(transform_df).collect()
        result_right = self.connect.range(11, 110).collect()
        self.assertEqual(result_right, result_left)

        # Check assertion.
        with self.assertRaises(AssertionError):
            df.transform(lambda x: 2)  # type: ignore

    def test_alias(self) -> None:
        """Testing supported and unsupported alias"""
        col0 = (
            self.connect.range(1, 10)
            .select(col("id").alias("name", metadata={"max": 99}))
            .schema.names[0]
        )
        self.assertEqual("name", col0)

        with self.assertRaises(grpc.RpcError) as exc:
            self.connect.range(1, 10).select(col("id").alias("this", "is", "not")).collect()
        self.assertIn("(this, is, not)", str(exc.exception))

    def test_agg_with_two_agg_exprs(self) -> None:
        # SPARK-41230: test dataframe.agg()
        self.assert_eq(
            self.connect.read.table(self.tbl_name).agg({"name": "min", "id": "max"}).toPandas(),
            self.spark.read.table(self.tbl_name).agg({"name": "min", "id": "max"}).toPandas(),
        )

    def test_write_operations(self):
        with tempfile.TemporaryDirectory() as d:
            df = self.connect.range(50)
            df.write.mode("overwrite").format("csv").save(d)

            ndf = self.connect.read.schema("id int").load(d, format="csv")
            self.assertEqual(50, len(ndf.collect()))
            cd = ndf.collect()
            self.assertEqual(set(df.collect()), set(cd))

        with tempfile.TemporaryDirectory() as d:
            df = self.connect.range(50)
            df.write.mode("overwrite").csv(d, lineSep="|")

            ndf = self.connect.read.schema("id int").load(d, format="csv", lineSep="|")
            self.assertEqual(set(df.collect()), set(ndf.collect()))

        df = self.connect.range(50)
        df.write.format("parquet").saveAsTable("parquet_test")

        ndf = self.connect.read.table("parquet_test")
        self.assertEqual(set(df.collect()), set(ndf.collect()))

    def test_agg_with_avg(self):
        # SPARK-41325: groupby.avg()
        df = (
            self.connect.range(10)
            .groupBy((col("id") % lit(2)).alias("moded"))
            .avg("id")
            .sort("moded")
        )
        res = df.collect()
        self.assertEqual(2, len(res))
        self.assertEqual(4.0, res[0][1])
        self.assertEqual(5.0, res[1][1])

    def test_crossjoin(self):
        # SPARK-41227: Test CrossJoin
        connect_df = self.connect.read.table(self.tbl_name)
        spark_df = self.spark.read.table(self.tbl_name)
        self.assert_eq(
            set(
                connect_df.select("id")
                .join(other=connect_df.select("name"), how="cross")
                .toPandas()
            ),
            set(spark_df.select("id").join(other=spark_df.select("name"), how="cross").toPandas()),
        )
        self.assert_eq(
            set(connect_df.select("id").crossJoin(other=connect_df.select("name")).toPandas()),
            set(spark_df.select("id").crossJoin(other=spark_df.select("name")).toPandas()),
        )


@unittest.skipIf(not should_test_connect, connect_requirement_message)
class ChannelBuilderTests(ReusedPySparkTestCase):
    def test_invalid_connection_strings(self):
        invalid = [
            "scc://host:12",
            "http://host",
            "sc:/host:1234/path",
            "sc://host/path",
            "sc://host/;parm1;param2",
        ]
        for i in invalid:
            self.assertRaises(AttributeError, ChannelBuilder, i)

    def test_sensible_defaults(self):
        chan = ChannelBuilder("sc://host")
        self.assertFalse(chan.secure, "Default URL is not secure")

        chan = ChannelBuilder("sc://host/;token=abcs")
        self.assertTrue(chan.secure, "specifying a token must set the channel to secure")

        chan = ChannelBuilder("sc://host/;use_ssl=abcs")
        self.assertFalse(chan.secure, "Garbage in, false out")

    def test_valid_channel_creation(self):
        chan = ChannelBuilder("sc://host").toChannel()
        self.assertIsInstance(chan, grpc.Channel)

        # Sets up a channel without tokens because ssl is not used.
        chan = ChannelBuilder("sc://host/;use_ssl=true;token=abc").toChannel()
        self.assertIsInstance(chan, grpc.Channel)

        chan = ChannelBuilder("sc://host/;use_ssl=true").toChannel()
        self.assertIsInstance(chan, grpc.Channel)

    def test_channel_properties(self):
        chan = ChannelBuilder("sc://host/;use_ssl=true;token=abc;param1=120%2021")
        self.assertEqual("host:15002", chan.endpoint)
        self.assertEqual(True, chan.secure)
        self.assertEqual("120 21", chan.get("param1"))

    def test_metadata(self):
        chan = ChannelBuilder("sc://host/;use_ssl=true;token=abc;param1=120%2021;x-my-header=abcd")
        md = chan.metadata()
        self.assertEqual([("param1", "120 21"), ("x-my-header", "abcd")], md)


if __name__ == "__main__":
    from pyspark.sql.tests.connect.test_connect_basic import *  # noqa: F401

    try:
        import xmlrunner  # type: ignore

        testRunner = xmlrunner.XMLTestRunner(output="target/test-reports", verbosity=2)
    except ImportError:
        testRunner = None

    unittest.main(testRunner=testRunner, verbosity=2)
