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

import datetime
import unittest
from distutils.version import LooseVersion

import pandas as pd
import numpy as np
from pandas.api.types import CategoricalDtype

from pyspark import pandas as ps
from pyspark.pandas.config import option_context
from pyspark.pandas.tests.data_type_ops.testing_utils import TestCasesUtils
from pyspark.pandas.typedef.typehints import (
    extension_dtypes_available,
    extension_float_dtypes_available,
    extension_object_dtypes_available,
)
from pyspark.sql.types import DecimalType, IntegralType
from pyspark.testing.pandasutils import PandasOnSparkTestCase


class NumOpsTest(PandasOnSparkTestCase, TestCasesUtils):
    """Unit tests for arithmetic operations of numeric data types.

    A few test cases are disabled because pandas-on-Spark returns float64 whereas pandas
    returns float32.
    The underlying reason is the respective Spark operations return DoubleType always.
    """

    @property
    def float_pser(self):
        return pd.Series([1, 2, 3], dtype=float)

    @property
    def float_psser(self):
        return ps.from_pandas(self.float_pser)

    def test_add(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            self.assert_eq(pser + pser, psser + psser)
            self.assert_eq(pser + 1, psser + 1)
            # self.assert_eq(pser + 0.1, psser + 0.1)
            self.assert_eq(pser + pser.astype(bool), psser + psser.astype(bool))
            self.assert_eq(pser + True, psser + True)
            self.assert_eq(pser + False, psser + False)

            for n_col in self.non_numeric_df_cols:
                if n_col == "bool":
                    self.assert_eq(pser + pdf[n_col], psser + psdf[n_col])
                else:
                    self.assertRaises(TypeError, lambda: psser + psdf[n_col])

    def test_sub(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            self.assert_eq(pser - pser, psser - psser)
            self.assert_eq(pser - 1, psser - 1)
            # self.assert_eq(pser - 0.1, psser - 0.1)
            self.assert_eq(pser - pser.astype(bool), psser - psser.astype(bool))
            self.assert_eq(pser - True, psser - True)
            self.assert_eq(pser - False, psser - False)

            for n_col in self.non_numeric_df_cols:
                if n_col == "bool":
                    self.assert_eq(pser - pdf[n_col], psser - psdf[n_col])
                else:
                    self.assertRaises(TypeError, lambda: psser - psdf[n_col])

    def test_mul(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            self.assert_eq(pser * pser, psser * psser)
            self.assert_eq(pser * pser.astype(bool), psser * psser.astype(bool))
            self.assert_eq(pser * True, psser * True)
            self.assert_eq(pser * False, psser * False)

            if psser.dtype in [int, np.int32]:
                self.assert_eq(pser * pdf["string"], psser * psdf["string"])
            else:
                self.assertRaises(TypeError, lambda: psser * psdf["string"])

            self.assert_eq(pser * pdf["bool"], psser * psdf["bool"])

            self.assertRaises(TypeError, lambda: psser * psdf["datetime"])
            self.assertRaises(TypeError, lambda: psser * psdf["date"])
            self.assertRaises(TypeError, lambda: psser * psdf["categorical"])

    def test_truediv(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            if psser.dtype in [float, int, np.int32]:
                self.assert_eq(pser / pser, psser / psser)
                self.assert_eq(pser / pser.astype(bool), psser / psser.astype(bool))
                self.assert_eq(pser / True, psser / True)
                self.assert_eq(pser / False, psser / False)

            for n_col in self.non_numeric_df_cols:
                if n_col == "bool":
                    self.assert_eq(pdf["float"] / pdf[n_col], psdf["float"] / psdf[n_col])
                else:
                    self.assertRaises(TypeError, lambda: psser / psdf[n_col])

    def test_floordiv(self):
        pdf, psdf = self.pdf, self.psdf
        pser, psser = pdf["float"], psdf["float"]
        self.assert_eq(pser // pser, psser // psser)
        self.assert_eq(pser // pser.astype(bool), psser // psser.astype(bool))
        self.assert_eq(pser // True, psser // True)
        self.assert_eq(pser // False, psser // False)

        for n_col in self.non_numeric_df_cols:
            if n_col == "bool":
                if LooseVersion(pd.__version__) >= LooseVersion("0.25.3"):
                    self.assert_eq(
                        pdf["float"] // pdf["bool"],
                        psdf["float"] // psdf["bool"],
                    )
                else:
                    self.assert_eq(
                        pd.Series([1.0, 2.0, np.inf]),
                        psdf["float"] // psdf["bool"],
                    )
            else:
                for col in self.numeric_df_cols:
                    psser = psdf[col]
                    self.assertRaises(TypeError, lambda: psser // psdf[n_col])

    def test_mod(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            self.assert_eq(pser % pser, psser % psser)
            self.assert_eq(pser % pser.astype(bool), psser % psser.astype(bool))
            self.assert_eq(pser % True, psser % True)
            if col in ["int", "int32"]:
                self.assert_eq(
                    pd.Series([np.nan, np.nan, np.nan], dtype=float, name=col), psser % False
                )
            else:
                self.assert_eq(
                    pd.Series([np.nan, np.nan, np.nan], dtype=pser.dtype, name=col), psser % False
                )

            for n_col in self.non_numeric_df_cols:
                if n_col == "bool":
                    self.assert_eq(pdf["float"] % pdf[n_col], psdf["float"] % psdf[n_col])
                else:
                    self.assertRaises(TypeError, lambda: psser % psdf[n_col])

    def test_pow(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            if col == "float":
                self.assert_eq(pser ** pser, psser ** psser)
                self.assert_eq(pser ** pser.astype(bool), psser ** psser.astype(bool))
                self.assert_eq(pser ** True, psser ** True)
                self.assert_eq(pser ** False, psser ** False)

            for n_col in self.non_numeric_df_cols:
                if n_col == "bool":
                    self.assert_eq(pdf["float"] ** pdf[n_col], psdf["float"] ** psdf[n_col])
                else:
                    self.assertRaises(TypeError, lambda: psser ** psdf[n_col])

    # TODO(SPARK-36031): Merge test_pow_with_nan into test_pow
    def test_pow_with_float_nan(self):
        for col in self.numeric_w_nan_df_cols:
            if col == "float_w_nan":
                pser, psser = self.numeric_w_nan_pdf[col], self.numeric_w_nan_psdf[col]
                self.assert_eq(pser ** pser, psser ** psser)
                self.assert_eq(pser ** pser.astype(bool), psser ** psser.astype(bool))
                self.assert_eq(pser ** True, psser ** True)
                self.assert_eq(pser ** False, psser ** False)
                self.assert_eq(pser ** 1, psser ** 1)
                self.assert_eq(pser ** 0, psser ** 0)

    def test_radd(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            self.assert_eq(1 + pser, 1 + psser)
            # self.assert_eq(0.1 + pser, 0.1 + psser)
            self.assertRaises(TypeError, lambda: "x" + psser)
            self.assert_eq(True + pser, True + psser)
            self.assert_eq(False + pser, False + psser)
            self.assertRaises(TypeError, lambda: datetime.date(1994, 1, 1) + psser)
            self.assertRaises(TypeError, lambda: datetime.datetime(1994, 1, 1) + psser)

    def test_rsub(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            self.assert_eq(1 - pser, 1 - psser)
            # self.assert_eq(0.1 - pser, 0.1 - psser)
            self.assertRaises(TypeError, lambda: "x" - psser)
            self.assert_eq(True - pser, True - psser)
            self.assert_eq(False - pser, False - psser)
            self.assertRaises(TypeError, lambda: datetime.date(1994, 1, 1) - psser)
            self.assertRaises(TypeError, lambda: datetime.datetime(1994, 1, 1) - psser)

    def test_rmul(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            self.assert_eq(1 * pser, 1 * psser)
            # self.assert_eq(0.1 * pser, 0.1 * psser)
            self.assertRaises(TypeError, lambda: "x" * psser)
            self.assert_eq(True * pser, True * psser)
            self.assert_eq(False * pser, False * psser)
            self.assertRaises(TypeError, lambda: datetime.date(1994, 1, 1) * psser)
            self.assertRaises(TypeError, lambda: datetime.datetime(1994, 1, 1) * psser)

    def test_rtruediv(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            # self.assert_eq(5 / pser, 5 / psser)
            # self.assert_eq(0.1 / pser, 0.1 / psser)
            self.assertRaises(TypeError, lambda: "x" / psser)
            self.assert_eq((True / pser).astype(float), True / psser, check_exact=False)
            self.assert_eq((False / pser).astype(float), False / psser)
            self.assertRaises(TypeError, lambda: datetime.date(1994, 1, 1) / psser)
            self.assertRaises(TypeError, lambda: datetime.datetime(1994, 1, 1) / psser)

    def test_rfloordiv(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            # self.assert_eq(5 // pser, 5 // psser)
            # self.assert_eq(0.1 // pser, 0.1 // psser)
            self.assertRaises(TypeError, lambda: "x" // psser)
            self.assert_eq((True // pser).astype(float), True // psser)
            self.assert_eq((False // pser).astype(float), False // psser)
            self.assertRaises(TypeError, lambda: datetime.date(1994, 1, 1) // psser)
            self.assertRaises(TypeError, lambda: datetime.datetime(1994, 1, 1) // psser)

    def test_rpow(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            # self.assert_eq(1 ** pser, 1 ** psser)
            # self.assert_eq(0.1 ** pser, 0.1 ** psser)
            self.assertRaises(TypeError, lambda: "x" ** psser)
            self.assert_eq((True ** pser).astype(float), True ** psser)
            self.assert_eq((False ** pser).astype(float), False ** psser)
            self.assertRaises(TypeError, lambda: datetime.date(1994, 1, 1) ** psser)
            self.assertRaises(TypeError, lambda: datetime.datetime(1994, 1, 1) ** psser)

    def test_rmod(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            self.assert_eq(1 % pser, 1 % psser)
            # self.assert_eq(0.1 % pser, 0.1 % psser)
            self.assert_eq(True % pser, True % psser)
            self.assert_eq(False % pser, False % psser)
            self.assertRaises(TypeError, lambda: datetime.date(1994, 1, 1) % psser)
            self.assertRaises(TypeError, lambda: datetime.datetime(1994, 1, 1) % psser)

    def test_and(self):
        psdf = self.psdf
        for col in self.numeric_df_cols:
            psser = psdf[col]
            self.assertRaises(TypeError, lambda: psser & True)
            self.assertRaises(TypeError, lambda: psser & False)
            self.assertRaises(TypeError, lambda: psser & psser)

    def test_rand(self):
        psdf = self.psdf
        for col in self.numeric_df_cols:
            psser = psdf[col]
            self.assertRaises(TypeError, lambda: True & psser)
            self.assertRaises(TypeError, lambda: False & psser)

    def test_or(self):
        psdf = self.psdf
        for col in self.numeric_df_cols:
            psser = psdf[col]
            self.assertRaises(TypeError, lambda: psser | True)
            self.assertRaises(TypeError, lambda: psser | False)
            self.assertRaises(TypeError, lambda: psser | psser)

    def test_ror(self):
        psdf = self.psdf
        for col in self.numeric_df_cols:
            psser = psdf[col]
            self.assertRaises(TypeError, lambda: True | psser)
            self.assertRaises(TypeError, lambda: False | psser)

    def test_from_to_pandas(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            self.assert_eq(pser, psser.to_pandas())
            self.assert_eq(ps.from_pandas(pser), psser)

    def test_isnull(self):
        pdf, psdf = self.numeric_w_nan_pdf, self.numeric_w_nan_psdf
        for col in self.numeric_w_nan_df_cols:
            self.assert_eq(pdf[col].isnull(), psdf[col].isnull())

    def test_astype(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            self.assert_eq(pser.astype(int), psser.astype(int))
            self.assert_eq(pser.astype(float), psser.astype(float))
            self.assert_eq(pser.astype(np.float32), psser.astype(np.float32))
            self.assert_eq(pser.astype(np.int32), psser.astype(np.int32))
            self.assert_eq(pser.astype(np.int16), psser.astype(np.int16))
            self.assert_eq(pser.astype(np.int8), psser.astype(np.int8))
            self.assert_eq(pser.astype(str), psser.astype(str))
            self.assert_eq(pser.astype(bool), psser.astype(bool))
            self.assert_eq(pser.astype("category"), psser.astype("category"))
            cat_type = CategoricalDtype(categories=[2, 1, 3])
            self.assert_eq(pser.astype(cat_type), psser.astype(cat_type))
        self.assertRaisesRegex(
            ValueError,
            "Cannot convert fractions with missing values to integer",
            lambda: self.float_withnan_psser.astype(int),
        )
        self.assertRaisesRegex(
            ValueError,
            "Cannot convert fractions with missing values to integer",
            lambda: self.float_withnan_psser.astype(np.int32),
        )
        self.assert_eq(self.float_withnan_psser.astype(str), self.float_withnan_psser.astype(str))
        self.assert_eq(self.float_withnan_psser.astype(bool), self.float_withnan_psser.astype(bool))
        self.assert_eq(
            self.float_withnan_psser.astype("category"), self.float_withnan_psser.astype("category")
        )
        if extension_object_dtypes_available and extension_float_dtypes_available:
            pser = pd.Series(pd.Categorical([1.0, 2.0, 3.0]), dtype=pd.Float64Dtype())
            psser = ps.from_pandas(pser)
            self.assert_eq(pser.astype(pd.BooleanDtype()), psser.astype(pd.BooleanDtype()))

    def test_neg(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            self.assert_eq(-pdf[col], -psdf[col])

    def test_abs(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            self.assert_eq(abs(pdf[col]), abs(psdf[col]))

    def test_invert(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            if isinstance(psser.spark.data_type, IntegralType):
                self.assert_eq(~pser, ~psser)
            else:
                self.assertRaises(TypeError, lambda: ~psser)

    def test_eq(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            self.assert_eq(pdf[col] == pdf[col], psdf[col] == psdf[col])

    def test_ne(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            self.assert_eq(pdf[col] != pdf[col], psdf[col] != psdf[col])

    def test_lt(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            if isinstance(psser.spark.data_type, DecimalType):
                self.assertRaisesRegex(TypeError, "< can not be applied to", lambda: psser < psser)
            else:
                self.assert_eq(pser < pser, psser < psser)

    def test_le(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            if isinstance(psser.spark.data_type, DecimalType):
                self.assertRaisesRegex(
                    TypeError, "<= can not be applied to", lambda: psser <= psser
                )
            else:
                self.assert_eq(pser <= pser, psser <= psser)

    def test_gt(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            if isinstance(psser.spark.data_type, DecimalType):
                self.assertRaisesRegex(TypeError, "> can not be applied to", lambda: psser > psser)
            else:
                self.assert_eq(pser > pser, psser > psser)

    def test_ge(self):
        pdf, psdf = self.pdf, self.psdf
        for col in self.numeric_df_cols:
            pser, psser = pdf[col], psdf[col]
            if isinstance(psser.spark.data_type, DecimalType):
                self.assertRaisesRegex(
                    TypeError, ">= can not be applied to", lambda: psser >= psser
                )
            else:
                self.assert_eq(pser >= pser, psser >= psser)


@unittest.skipIf(not extension_dtypes_available, "pandas extension dtypes are not available")
class IntegralExtensionOpsTest(PandasOnSparkTestCase, TestCasesUtils):
    @property
    def intergral_extension_psers(self):
        return [pd.Series([1, 2, 3, None], dtype=dtype) for dtype in self.integral_extension_dtypes]

    @property
    def intergral_extension_pssers(self):
        return [ps.from_pandas(pser) for pser in self.intergral_extension_psers]

    @property
    def intergral_extension_pser_psser_pairs(self):
        return zip(self.intergral_extension_psers, self.intergral_extension_pssers)

    def test_from_to_pandas(self):
        for pser, psser in self.intergral_extension_pser_psser_pairs:
            self.check_extension(pser, psser.to_pandas())
            self.check_extension(ps.from_pandas(pser), psser)

    def test_isnull(self):
        for pser, psser in self.intergral_extension_pser_psser_pairs:
            self.assert_eq(pser.isnull(), psser.isnull())

    def test_astype(self):
        for pser, psser in self.intergral_extension_pser_psser_pairs:
            for dtype in self.extension_dtypes:
                if dtype in self.string_extension_dtype:
                    if LooseVersion(pd.__version__) >= LooseVersion("1.1.0"):
                        # Limit pandas version due to
                        # https://github.com/pandas-dev/pandas/issues/31204
                        self.check_extension(pser.astype(dtype), psser.astype(dtype))
                else:
                    self.check_extension(pser.astype(dtype), psser.astype(dtype))
        for pser, psser in self.intergral_extension_pser_psser_pairs:
            self.assert_eq(pser.astype(float), psser.astype(float))
            self.assert_eq(pser.astype(np.float32), psser.astype(np.float32))
            self.assertRaisesRegex(
                ValueError,
                "Cannot convert integrals with missing values to bool",
                lambda: psser.astype(bool),
            )
            self.assertRaisesRegex(
                ValueError,
                "Cannot convert integrals with missing values to integer",
                lambda: psser.astype(int),
            )
            self.assertRaisesRegex(
                ValueError,
                "Cannot convert integrals with missing values to integer",
                lambda: psser.astype(np.int32),
            )

    def test_neg(self):
        for pser, psser in self.intergral_extension_pser_psser_pairs:
            if LooseVersion(pd.__version__) < LooseVersion("1.1.3"):
                # pandas < 1.1.0: object dtype is returned after negation
                # pandas 1.1.1 and 1.1.2:
                #   a TypeError "bad operand type for unary -: 'IntegerArray'" is raised
                # Please refer to https://github.com/pandas-dev/pandas/issues/36063.
                self.check_extension(pd.Series([-1, -2, -3, None], dtype=pser.dtype), -psser)
            else:
                self.check_extension(-pser, -psser)

    def test_abs(self):
        for pser, psser in self.intergral_extension_pser_psser_pairs:
            self.check_extension(abs(pser), abs(psser))

    def test_invert(self):
        for pser, psser in self.intergral_extension_pser_psser_pairs:
            self.check_extension(~pser, ~psser)

    def test_eq(self):
        with option_context("compute.ops_on_diff_frames", True):
            for pser, psser in self.intergral_extension_pser_psser_pairs:
                self.check_extension(pser == pser, (psser == psser).sort_index())

    def test_ne(self):
        with option_context("compute.ops_on_diff_frames", True):
            for pser, psser in self.intergral_extension_pser_psser_pairs:
                self.check_extension(pser != pser, (psser != psser).sort_index())

    def test_lt(self):
        with option_context("compute.ops_on_diff_frames", True):
            for pser, psser in self.intergral_extension_pser_psser_pairs:
                self.check_extension(pser < pser, (psser < psser).sort_index())

    def test_le(self):
        with option_context("compute.ops_on_diff_frames", True):
            for pser, psser in self.intergral_extension_pser_psser_pairs:
                self.check_extension(pser <= pser, (psser <= psser).sort_index())

    def test_gt(self):
        with option_context("compute.ops_on_diff_frames", True):
            for pser, psser in self.intergral_extension_pser_psser_pairs:
                self.check_extension(pser > pser, (psser > psser).sort_index())

    def test_ge(self):
        with option_context("compute.ops_on_diff_frames", True):
            for pser, psser in self.intergral_extension_pser_psser_pairs:
                self.check_extension(pser >= pser, (psser >= psser).sort_index())


@unittest.skipIf(
    not extension_float_dtypes_available, "pandas extension float dtypes are not available"
)
class FractionalExtensionOpsTest(PandasOnSparkTestCase, TestCasesUtils):
    @property
    def fractional_extension_psers(self):
        return [
            pd.Series([0.1, 0.2, 0.3, None], dtype=dtype)
            for dtype in self.fractional_extension_dtypes
        ]

    @property
    def fractional_extension_pssers(self):
        return [ps.from_pandas(pser) for pser in self.fractional_extension_psers]

    @property
    def fractional_extension_pser_psser_pairs(self):
        return zip(self.fractional_extension_psers, self.fractional_extension_pssers)

    def test_from_to_pandas(self):
        for pser, psser in self.fractional_extension_pser_psser_pairs:
            self.check_extension(pser, psser.to_pandas())
            self.check_extension(ps.from_pandas(pser), psser)

    def test_isnull(self):
        for pser, psser in self.fractional_extension_pser_psser_pairs:
            self.assert_eq(pser.isnull(), psser.isnull())

    def test_astype(self):
        for pser, psser in self.fractional_extension_pser_psser_pairs:
            for dtype in self.extension_dtypes:
                self.check_extension(pser.astype(dtype), psser.astype(dtype))
        for pser, psser in self.fractional_extension_pser_psser_pairs:
            self.assert_eq(pser.astype(float), psser.astype(float))
            self.assert_eq(pser.astype(np.float32), psser.astype(np.float32))
            self.assertRaisesRegex(
                ValueError,
                "Cannot convert fractions with missing values to bool",
                lambda: psser.astype(bool),
            )
            self.assertRaisesRegex(
                ValueError,
                "Cannot convert fractions with missing values to integer",
                lambda: psser.astype(int),
            )
            self.assertRaisesRegex(
                ValueError,
                "Cannot convert fractions with missing values to integer",
                lambda: psser.astype(np.int32),
            )

    def test_neg(self):
        # pandas raises "TypeError: bad operand type for unary -: 'FloatingArray'"
        for dtype in self.fractional_extension_dtypes:
            self.assert_eq(
                ps.Series([-0.1, -0.2, -0.3, None], dtype=dtype),
                -ps.Series([0.1, 0.2, 0.3, None], dtype=dtype),
            )

    def test_abs(self):
        for pser, psser in self.fractional_extension_pser_psser_pairs:
            self.check_extension(abs(pser), abs(psser))

    def test_invert(self):
        for psser in self.fractional_extension_pssers:
            self.assertRaises(TypeError, lambda: ~psser)

    def test_eq(self):
        with option_context("compute.ops_on_diff_frames", True):
            for pser, psser in self.fractional_extension_pser_psser_pairs:
                self.check_extension(pser == pser, (psser == psser).sort_index())

    def test_ne(self):
        with option_context("compute.ops_on_diff_frames", True):
            for pser, psser in self.fractional_extension_pser_psser_pairs:
                self.check_extension(pser != pser, (psser != psser).sort_index())

    def test_lt(self):
        with option_context("compute.ops_on_diff_frames", True):
            for pser, psser in self.fractional_extension_pser_psser_pairs:
                self.check_extension(pser < pser, (psser < psser).sort_index())

    def test_le(self):
        with option_context("compute.ops_on_diff_frames", True):
            for pser, psser in self.fractional_extension_pser_psser_pairs:
                self.check_extension(pser <= pser, (psser <= psser).sort_index())

    def test_gt(self):
        with option_context("compute.ops_on_diff_frames", True):
            for pser, psser in self.fractional_extension_pser_psser_pairs:
                self.check_extension(pser > pser, (psser > psser).sort_index())

    def test_ge(self):
        with option_context("compute.ops_on_diff_frames", True):
            for pser, psser in self.fractional_extension_pser_psser_pairs:
                self.check_extension(pser >= pser, (psser >= psser).sort_index())


if __name__ == "__main__":
    from pyspark.pandas.tests.data_type_ops.test_num_ops import *  # noqa: F401

    try:
        import xmlrunner  # type: ignore[import]

        testRunner = xmlrunner.XMLTestRunner(output="target/test-reports", verbosity=2)
    except ImportError:
        testRunner = None
    unittest.main(testRunner=testRunner, verbosity=2)
