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

from pyspark.pandas.tests.test_series import SeriesTestsMixin
from pyspark.testing.connectutils import ReusedConnectTestCase
from pyspark.testing.pandasutils import PandasOnSparkTestUtils


class SeriesParityTests(SeriesTestsMixin, PandasOnSparkTestUtils, ReusedConnectTestCase):
    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_argsort(self):
        super().test_argsort()

    @unittest.skip(
        "TODO(SPARK-43610): Enable `InternalFrame.attach_distributed_column` in Spark Connect."
    )
    def test_asof(self):
        super().test_asof()

    @unittest.skip(
        "TODO(SPARK-43610): Enable `InternalFrame.attach_distributed_column` in Spark Connect."
    )
    def test_at_time(self):
        super().test_at_time()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_backfill(self):
        super().test_backfill()

    @unittest.skip(
        "TODO(SPARK-43610): Enable `InternalFrame.attach_distributed_column` in Spark Connect."
    )
    def test_between_time(self):
        super().test_between_time()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_bfill(self):
        super().test_bfill()

    @unittest.skip("TODO(SPARK-43663): Enable SeriesParityTests.test_compare.")
    def test_compare(self):
        super().test_compare()

    @unittest.skip(
        "TODO(SPARK-43613): Enable pyspark.pandas.spark.functions.covar in Spark Connect."
    )
    def test_cov(self):
        super().test_cov()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_cummax(self):
        super().test_cummax()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_cummin(self):
        super().test_cummin()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_cumprod(self):
        super().test_cumprod()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_cumsum(self):
        super().test_cumsum()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_diff(self):
        super().test_diff()

    @unittest.skip("TODO(SPARK-43620): Support `Column` for SparkConnectColumn.__getitem__.")
    def test_factorize(self):
        super().test_factorize()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_ffill(self):
        super().test_ffill()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_fillna(self):
        super().test_fillna()

    @unittest.skip(
        "TODO(SPARK-43616): Enable pyspark.pandas.spark.functions.mode in Spark Connect."
    )
    def test_mode(self):
        super().test_mode()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_pad(self):
        super().test_pad()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_pct_change(self):
        super().test_pct_change()

    @unittest.skip(
        "TODO(SPARK-43617): Enable pyspark.pandas.spark.functions.product in Spark Connect."
    )
    def test_product(self):
        super().test_product()

    @unittest.skip("TODO(SPARK-43618): Fix pyspark.sq.column._unary_op to work with Spark Connect.")
    def test_rank(self):
        super().test_rank()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_replace(self):
        super().test_replace()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_reset_index_with_default_index_types(self):
        super().test_reset_index_with_default_index_types()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_shift(self):
        super().test_shift()

    @unittest.skip("TODO(SPARK-43619): Enable DataFrameSlowParityTests.test_udt.")
    def test_udt(self):
        super().test_udt()

    @unittest.skip(
        "TODO(SPARK-43611): Fix unexpected `AnalysisException` from Spark Connect client."
    )
    def test_unstack(self):
        super().test_unstack()


if __name__ == "__main__":
    from pyspark.pandas.tests.connect.test_parity_series import *  # noqa: F401

    try:
        import xmlrunner  # type: ignore[import]

        testRunner = xmlrunner.XMLTestRunner(output="target/test-reports", verbosity=2)
    except ImportError:
        testRunner = None
    unittest.main(testRunner=testRunner, verbosity=2)
