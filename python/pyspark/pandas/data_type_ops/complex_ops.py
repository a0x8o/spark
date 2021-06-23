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

from typing import TYPE_CHECKING, Union

from pandas.api.types import CategoricalDtype

from pyspark.pandas.base import column_op, IndexOpsMixin
from pyspark.pandas.data_type_ops.base import (
    DataTypeOps,
    T_IndexOps,
    _as_bool_type,
    _as_categorical_type,
    _as_other_type,
    _as_string_type,
)
from pyspark.pandas.typedef import Dtype, pandas_on_spark_type
from pyspark.sql import functions as F
from pyspark.sql.types import ArrayType, BooleanType, NumericType, StringType

if TYPE_CHECKING:
    from pyspark.pandas.indexes import Index  # noqa: F401 (SPARK-34943)
    from pyspark.pandas.series import Series  # noqa: F401 (SPARK-34943)


class ArrayOps(DataTypeOps):
    """
    The class for binary operations of pandas-on-Spark objects with ArrayType.
    """

    @property
    def pretty_name(self) -> str:
        return "arrays"

    def add(self, left, right) -> Union["Series", "Index"]:
        if not isinstance(right, IndexOpsMixin) or (
            isinstance(right, IndexOpsMixin) and not isinstance(right.spark.data_type, ArrayType)
        ):
            raise TypeError(
                "Concatenation can not be applied to %s and the given type." % self.pretty_name
            )

        left_type = left.spark.data_type.elementType
        right_type = right.spark.data_type.elementType

        if left_type != right_type and not (
            isinstance(left_type, NumericType) and isinstance(right_type, NumericType)
        ):
            raise TypeError(
                "Concatenation can only be applied to %s of the same type" % self.pretty_name
            )

        return column_op(F.concat)(left, right)

    def astype(self, index_ops: T_IndexOps, dtype: Union[str, type, Dtype]) -> T_IndexOps:
        dtype, spark_type = pandas_on_spark_type(dtype)

        if isinstance(dtype, CategoricalDtype):
            return _as_categorical_type(index_ops, dtype, spark_type)
        elif isinstance(spark_type, BooleanType):
            return _as_bool_type(index_ops, dtype)
        elif isinstance(spark_type, StringType):
            return _as_string_type(index_ops, dtype)
        else:
            return _as_other_type(index_ops, dtype, spark_type)


class MapOps(DataTypeOps):
    """
    The class for binary operations of pandas-on-Spark objects with MapType.
    """

    @property
    def pretty_name(self) -> str:
        return "maps"


class StructOps(DataTypeOps):
    """
    The class for binary operations of pandas-on-Spark objects with StructType.
    """

    @property
    def pretty_name(self) -> str:
        return "structs"
