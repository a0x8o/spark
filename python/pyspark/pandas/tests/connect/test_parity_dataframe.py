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
import unittest

from pyspark import pandas as ps
from pyspark.pandas.tests.test_dataframe import DataFrameTestsMixin
from pyspark.testing.connectutils import ReusedConnectTestCase
from pyspark.testing.pandasutils import PandasOnSparkTestUtils


class DataFrameParityTests(DataFrameTestsMixin, PandasOnSparkTestUtils, ReusedConnectTestCase):
    @property
    def psdf(self):
        return ps.from_pandas(self.pdf)

    @unittest.skip(
        "TODO(SPARK-43610): Enable `InternalFrame.attach_distributed_column` in Spark Connect."
    )
    def test_aggregate(self):
        super().test_aggregate()

    @unittest.skip("TODO(SPARK-41876): Implement DataFrame `toLocalIterator`")
    def test_iterrows(self):
        super().test_iterrows()

    @unittest.skip("TODO(SPARK-41876): Implement DataFrame `toLocalIterator`")
    def test_itertuples(self):
        super().test_itertuples()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_cummax(self):
        super().test_cummax()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_cummax_multiindex_columns(self):
        super().test_cummax_multiindex_columns()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_cummin(self):
        super().test_cummin()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_cummin_multiindex_columns(self):
        super().test_cummin_multiindex_columns()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_cumprod(self):
        super().test_cumprod()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_cumprod_multiindex_columns(self):
        super().test_cumprod_multiindex_columns()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_cumsum(self):
        super().test_cumsum()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_cumsum_multiindex_columns(self):
        super().test_cumsum_multiindex_columns()

    @unittest.skip(
        "TODO(SPARK-43616): Enable pyspark.pandas.spark.functions.repeat in Spark Connect."
    )
    def test_binary_operator_multiply(self):
        super().test_binary_operator_multiply()

    @unittest.skip("TODO(SPARK-43622): Enable pyspark.pandas.spark.functions.var in Spark Connect.")
    def test_dataframe(self):
        super().test_dataframe()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_fillna(self):
        return super().test_fillna()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_pivot_table(self):
        super().test_pivot_table()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_pivot_table_dtypes(self):
        super().test_pivot_table_dtypes()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_reset_index_with_default_index_types(self):
        super().test_reset_index_with_default_index_types()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_transpose(self):
        super().test_transpose()

    @unittest.skip(
        "TODO(SPARK-43610): Enable `InternalFrame.attach_distributed_column` in Spark Connect."
    )
    def test_unstack(self):
        super().test_unstack()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_append(self):
        super().test_append()


if __name__ == "__main__":
    from pyspark.pandas.tests.connect.test_parity_dataframe import *  # noqa: F401

    try:
        import xmlrunner  # type: ignore[import]

        testRunner = xmlrunner.XMLTestRunner(output="target/test-reports", verbosity=2)
    except ImportError:
        testRunner = None
    unittest.main(testRunner=testRunner, verbosity=2)
