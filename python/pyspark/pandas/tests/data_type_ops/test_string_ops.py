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

import numpy as np
import pandas as pd

from pyspark import pandas as ps
from pyspark.pandas.config import option_context
from pyspark.pandas.tests.data_type_ops.testing_utils import TestCasesUtils
from pyspark.testing.pandasutils import PandasOnSparkTestCase


class StringOpsTest(PandasOnSparkTestCase, TestCasesUtils):
    @property
    def pser(self):
        return pd.Series(["x", "y", "z"])

    @property
    def psser(self):
        return ps.from_pandas(self.pser)

    def test_add(self):
        self.assert_eq(self.pser + "x", self.psser + "x")
        self.assertRaises(TypeError, lambda: self.psser + 1)

        with option_context("compute.ops_on_diff_frames", True):
            self.assert_eq(
                self.pser + self.non_numeric_psers["string"],
                (self.psser + self.non_numeric_pssers["string"]).sort_index(),
            )
            self.assertRaises(TypeError, lambda: self.psser + self.non_numeric_pssers["datetime"])
            self.assertRaises(TypeError, lambda: self.psser + self.non_numeric_pssers["date"])
            self.assertRaises(
                TypeError, lambda: self.psser + self.non_numeric_pssers["categorical"])
            self.assertRaises(TypeError, lambda: self.psser + self.non_numeric_pssers["bool"])
            for psser in self.numeric_pssers:
                self.assertRaises(TypeError, lambda: self.psser + psser)

    def test_sub(self):
        self.assertRaises(TypeError, lambda: self.psser - "x")
        self.assertRaises(TypeError, lambda: self.psser - 1)

        with option_context("compute.ops_on_diff_frames", True):
            for psser in self.pssers:
                self.assertRaises(TypeError, lambda: self.psser - psser)

    def test_mul(self):
        self.assertRaises(TypeError, lambda: self.psser * "x")
        self.assert_eq(self.pser * 1, self.psser * 1)

        with option_context("compute.ops_on_diff_frames", True):
            for pser, psser in self.pser_psser_pairs:
                if psser.dtype in [np.int64, np.int32]:
                    self.assert_eq(self.pser * pser, (self.psser * psser).sort_index())
                else:
                    self.assertRaises(TypeError, lambda: self.psser * psser)

    def test_truediv(self):
        self.assertRaises(TypeError, lambda: self.psser / "x")
        self.assertRaises(TypeError, lambda: self.psser / 1)

        with option_context("compute.ops_on_diff_frames", True):
            for psser in self.pssers:
                self.assertRaises(TypeError, lambda: self.psser / psser)

    def test_floordiv(self):
        self.assertRaises(TypeError, lambda: self.psser // "x")
        self.assertRaises(TypeError, lambda: self.psser // 1)

        with option_context("compute.ops_on_diff_frames", True):
            for psser in self.pssers:
                self.assertRaises(TypeError, lambda: self.psser // psser)

    def test_mod(self):
        self.assertRaises(TypeError, lambda: self.psser % "x")
        self.assertRaises(TypeError, lambda: self.psser % 1)

        with option_context("compute.ops_on_diff_frames", True):
            for psser in self.pssers:
                self.assertRaises(TypeError, lambda: self.psser % psser)

    def test_pow(self):
        self.assertRaises(TypeError, lambda: self.psser ** "x")
        self.assertRaises(TypeError, lambda: self.psser ** 1)

        with option_context("compute.ops_on_diff_frames", True):
            for psser in self.pssers:
                self.assertRaises(TypeError, lambda: self.psser ** psser)

    def test_radd(self):
        self.assert_eq("x" + self.pser, "x" + self.psser)
        self.assertRaises(TypeError, lambda: 1 + self.psser)

    def test_rsub(self):
        self.assertRaises(TypeError, lambda: "x" - self.psser)
        self.assertRaises(TypeError, lambda: 1 - self.psser)

    def test_rmul(self):
        self.assertRaises(TypeError, lambda: "x" * self.psser)
        self.assert_eq(1 * self.pser, 1 * self.psser)

    def test_rtruediv(self):
        self.assertRaises(TypeError, lambda: "x" / self.psser)
        self.assertRaises(TypeError, lambda: 1 / self.psser)

    def test_rfloordiv(self):
        self.assertRaises(TypeError, lambda: "x" // self.psser)
        self.assertRaises(TypeError, lambda: 1 // self.psser)

    def test_rmod(self):
        self.assertRaises(TypeError, lambda: 1 % self.psser)

    def test_rpow(self):
        self.assertRaises(TypeError, lambda: "x" ** self.psser)
        self.assertRaises(TypeError, lambda: 1 ** self.psser)


if __name__ == "__main__":
    import unittest
    from pyspark.pandas.tests.data_type_ops.test_num_ops import *  # noqa: F401

    try:
        import xmlrunner  # type: ignore[import]
        testRunner = xmlrunner.XMLTestRunner(output='target/test-reports', verbosity=2)
    except ImportError:
        testRunner = None
    unittest.main(testRunner=testRunner, verbosity=2)
