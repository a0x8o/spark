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

"""
Utilities to deal with types. This is mostly focused on python3.
"""
import datetime
import decimal
import typing
from collections import Iterable
from inspect import getfullargspec, isclass
from typing import (  # noqa: F401
    Any,
    Callable,
    Generic,
    List,
    Optional,
    Tuple,
    Union,
    Type,
)

import numpy as np
import pandas as pd
from pandas.api.types import CategoricalDtype, pandas_dtype
from pandas.api.extensions import ExtensionDtype

try:
    from pandas import Int8Dtype, Int16Dtype, Int32Dtype, Int64Dtype

    extension_dtypes_available = True
    extension_dtypes = (Int8Dtype, Int16Dtype, Int32Dtype, Int64Dtype)  # type: Tuple

    try:
        from pandas import BooleanDtype, StringDtype

        extension_object_dtypes_available = True
        extension_dtypes += (BooleanDtype, StringDtype)
    except ImportError:
        extension_object_dtypes_available = False

    try:
        from pandas import Float32Dtype, Float64Dtype

        extension_float_dtypes_available = True
        extension_dtypes += (Float32Dtype, Float64Dtype)
    except ImportError:
        extension_float_dtypes_available = False

except ImportError:
    extension_dtypes_available = False
    extension_object_dtypes_available = False
    extension_float_dtypes_available = False
    extension_dtypes = ()

import pyarrow as pa
import pyspark.sql.types as types
from pyspark.sql.pandas.types import to_arrow_type, from_arrow_type

from pyspark import pandas as ps  # For running doctests and reference resolution in PyCharm.
from pyspark.pandas._typing import Dtype, T
from pyspark.pandas.typedef.string_typehints import resolve_string_type_hint

if typing.TYPE_CHECKING:
    from pyspark.pandas.internal import InternalField


# A column of data, with the data type.
class SeriesType(Generic[T]):
    def __init__(self, dtype: Dtype, spark_type: types.DataType):
        self.dtype = dtype
        self.spark_type = spark_type

    def __repr__(self) -> str:
        return "SeriesType[{}]".format(self.spark_type)


class DataFrameType(object):
    def __init__(
        self,
        index_field: Optional["InternalField"],
        data_fields: List["InternalField"],
    ):
        self.index_field = index_field
        self.data_fields = data_fields
        self.fields = [index_field] + data_fields if index_field is not None else data_fields

    @property
    def dtypes(self) -> List[Dtype]:
        return [field.dtype for field in self.fields]

    @property
    def spark_type(self) -> types.StructType:
        return types.StructType([field.struct_field for field in self.fields])

    def __repr__(self) -> str:
        return "DataFrameType[{}]".format(self.spark_type)


# The type is a scalar type that is furthermore understood by Spark.
class ScalarType(object):
    def __init__(self, dtype: Dtype, spark_type: types.DataType):
        self.dtype = dtype
        self.spark_type = spark_type

    def __repr__(self) -> str:
        return "ScalarType[{}]".format(self.spark_type)


# The type is left unspecified or we do not know about this type.
class UnknownType(object):
    def __init__(self, tpe: Any):
        self.tpe = tpe

    def __repr__(self) -> str:
        return "UnknownType[{}]".format(self.tpe)


class IndexNameTypeHolder(object):
    name = None
    tpe = None


class NameTypeHolder(object):
    name = None
    tpe = None


def as_spark_type(
    tpe: Union[str, type, Dtype], *, raise_error: bool = True, prefer_timestamp_ntz: bool = False
) -> types.DataType:
    """
    Given a Python type, returns the equivalent spark type.
    Accepts:
    - the built-in types in Python
    - the built-in types in numpy
    - list of pairs of (field_name, type)
    - dictionaries of field_name -> type
    - Python3's typing system
    """
    if isinstance(tpe, np.dtype) and tpe == np.dtype("object"):
        pass
    # ArrayType
    elif tpe in (np.ndarray,):
        return types.ArrayType(types.StringType())
    elif hasattr(tpe, "__origin__") and issubclass(tpe.__origin__, list):  # type: ignore
        element_type = as_spark_type(tpe.__args__[0], raise_error=raise_error)  # type: ignore
        if element_type is None:
            return None
        return types.ArrayType(element_type)
    # BinaryType
    elif tpe in (bytes, np.character, np.bytes_, np.string_):
        return types.BinaryType()
    # BooleanType
    elif tpe in (bool, np.bool_, "bool", "?"):
        return types.BooleanType()
    # DateType
    elif tpe in (datetime.date,):
        return types.DateType()
    # NumericType
    elif tpe in (np.int8, np.byte, "int8", "byte", "b"):
        return types.ByteType()
    elif tpe in (decimal.Decimal,):
        # TODO: considering about the precision & scale for decimal type.
        return types.DecimalType(38, 18)
    elif tpe in (float, np.float_, np.float64, "float", "float64", "double"):
        return types.DoubleType()
    elif tpe in (np.float32, "float32", "f"):
        return types.FloatType()
    elif tpe in (np.int32, "int32", "i"):
        return types.IntegerType()
    elif tpe in (int, np.int64, "int", "int64", "long"):
        return types.LongType()
    elif tpe in (np.int16, "int16", "short"):
        return types.ShortType()
    # StringType
    elif tpe in (str, np.unicode_, "str", "U"):
        return types.StringType()
    # TimestampType or TimestampNTZType if timezone is not specified.
    elif tpe in (datetime.datetime, np.datetime64, "datetime64[ns]", "M"):
        return types.TimestampNTZType() if prefer_timestamp_ntz else types.TimestampType()

    # categorical types
    elif isinstance(tpe, CategoricalDtype) or (isinstance(tpe, str) and type == "category"):
        return types.LongType()

    # extension types
    elif extension_dtypes_available:
        # IntegralType
        if isinstance(tpe, Int8Dtype) or (isinstance(tpe, str) and tpe == "Int8"):
            return types.ByteType()
        elif isinstance(tpe, Int16Dtype) or (isinstance(tpe, str) and tpe == "Int16"):
            return types.ShortType()
        elif isinstance(tpe, Int32Dtype) or (isinstance(tpe, str) and tpe == "Int32"):
            return types.IntegerType()
        elif isinstance(tpe, Int64Dtype) or (isinstance(tpe, str) and tpe == "Int64"):
            return types.LongType()

        if extension_object_dtypes_available:
            # BooleanType
            if isinstance(tpe, BooleanDtype) or (isinstance(tpe, str) and tpe == "boolean"):
                return types.BooleanType()
            # StringType
            elif isinstance(tpe, StringDtype) or (isinstance(tpe, str) and tpe == "string"):
                return types.StringType()

        if extension_float_dtypes_available:
            # FractionalType
            if isinstance(tpe, Float32Dtype) or (isinstance(tpe, str) and tpe == "Float32"):
                return types.FloatType()
            elif isinstance(tpe, Float64Dtype) or (isinstance(tpe, str) and tpe == "Float64"):
                return types.DoubleType()

    if raise_error:
        raise TypeError("Type %s was not understood." % tpe)
    else:
        return None


def spark_type_to_pandas_dtype(
    spark_type: types.DataType, *, use_extension_dtypes: bool = False
) -> Dtype:
    """Return the given Spark DataType to pandas dtype."""

    if use_extension_dtypes and extension_dtypes_available:
        # IntegralType
        if isinstance(spark_type, types.ByteType):
            return Int8Dtype()
        elif isinstance(spark_type, types.ShortType):
            return Int16Dtype()
        elif isinstance(spark_type, types.IntegerType):
            return Int32Dtype()
        elif isinstance(spark_type, types.LongType):
            return Int64Dtype()

        if extension_object_dtypes_available:
            # BooleanType
            if isinstance(spark_type, types.BooleanType):
                return BooleanDtype()
            # StringType
            elif isinstance(spark_type, types.StringType):
                return StringDtype()

        # FractionalType
        if extension_float_dtypes_available:
            if isinstance(spark_type, types.FloatType):
                return Float32Dtype()
            elif isinstance(spark_type, types.DoubleType):
                return Float64Dtype()

    if isinstance(
        spark_type,
        (
            types.DateType,
            types.NullType,
            types.ArrayType,
            types.MapType,
            types.StructType,
            types.UserDefinedType,
        ),
    ):
        return np.dtype("object")
    elif isinstance(spark_type, types.TimestampType):
        return np.dtype("datetime64[ns]")
    else:
        return np.dtype(to_arrow_type(spark_type).to_pandas_dtype())


def pandas_on_spark_type(tpe: Union[str, type, Dtype]) -> Tuple[Dtype, types.DataType]:
    """
    Convert input into a pandas only dtype object or a numpy dtype object,
    and its corresponding Spark DataType.

    Parameters
    ----------
    tpe : object to be converted

    Returns
    -------
    tuple of np.dtype or a pandas dtype, and Spark DataType

    Raises
    ------
    TypeError if not a dtype

    Examples
    --------
    >>> pandas_on_spark_type(int)
    (dtype('int64'), LongType)
    >>> pandas_on_spark_type(str)
    (dtype('<U'), StringType)
    >>> pandas_on_spark_type(datetime.date)
    (dtype('O'), DateType)
    >>> pandas_on_spark_type(datetime.datetime)
    (dtype('<M8[ns]'), TimestampType)
    >>> pandas_on_spark_type(List[bool])
    (dtype('O'), ArrayType(BooleanType,true))
    """
    try:
        dtype = pandas_dtype(tpe)
        spark_type = as_spark_type(dtype)
    except TypeError:
        spark_type = as_spark_type(tpe)
        dtype = spark_type_to_pandas_dtype(spark_type)
    return dtype, spark_type


def infer_pd_series_spark_type(
    pser: pd.Series, dtype: Dtype, prefer_timestamp_ntz: bool = False
) -> types.DataType:
    """Infer Spark DataType from pandas Series dtype.

    :param pser: :class:`pandas.Series` to be inferred
    :param dtype: the Series' dtype
    :param prefer_timestamp_ntz: if true, infers datetime without timezone as
        TimestampNTZType type. If false, infers it as TimestampType.
    :return: the inferred Spark data type
    """
    if dtype == np.dtype("object"):
        if len(pser) == 0 or pser.isnull().all():
            return types.NullType()
        elif hasattr(pser.iloc[0], "__UDT__"):
            return pser.iloc[0].__UDT__
        else:
            return from_arrow_type(pa.Array.from_pandas(pser).type, prefer_timestamp_ntz)
    elif isinstance(dtype, CategoricalDtype):
        if isinstance(pser.dtype, CategoricalDtype):
            return as_spark_type(pser.cat.codes.dtype, prefer_timestamp_ntz=prefer_timestamp_ntz)
        else:
            # `pser` must already be converted to codes.
            return as_spark_type(pser.dtype, prefer_timestamp_ntz=prefer_timestamp_ntz)
    else:
        return as_spark_type(dtype, prefer_timestamp_ntz=prefer_timestamp_ntz)


def infer_return_type(f: Callable) -> Union[SeriesType, DataFrameType, ScalarType, UnknownType]:
    """
    Infer the return type from the return type annotation of the given function.

    The returned type class indicates both dtypes (a pandas only dtype object
    or a numpy dtype object) and its corresponding Spark DataType.

    >>> def func() -> int:
    ...    pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtype
    dtype('int64')
    >>> inferred.spark_type
    LongType

    >>> def func() -> ps.Series[int]:
    ...    pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtype
    dtype('int64')
    >>> inferred.spark_type
    LongType

    >>> def func() -> ps.DataFrame[np.float, str]:
    ...    pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtypes
    [dtype('float64'), dtype('<U')]
    >>> inferred.spark_type
    StructType(List(StructField(c0,DoubleType,true),StructField(c1,StringType,true)))

    >>> def func() -> ps.DataFrame[np.float]:
    ...    pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtypes
    [dtype('float64')]
    >>> inferred.spark_type
    StructType(List(StructField(c0,DoubleType,true)))

    >>> def func() -> 'int':
    ...    pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtype
    dtype('int64')
    >>> inferred.spark_type
    LongType

    >>> def func() -> 'ps.Series[int]':
    ...    pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtype
    dtype('int64')
    >>> inferred.spark_type
    LongType

    >>> def func() -> 'ps.DataFrame[np.float, str]':
    ...    pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtypes
    [dtype('float64'), dtype('<U')]
    >>> inferred.spark_type
    StructType(List(StructField(c0,DoubleType,true),StructField(c1,StringType,true)))

    >>> def func() -> 'ps.DataFrame[np.float]':
    ...    pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtypes
    [dtype('float64')]
    >>> inferred.spark_type
    StructType(List(StructField(c0,DoubleType,true)))

    >>> def func() -> ps.DataFrame['a': np.float, 'b': int]:
    ...     pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtypes
    [dtype('float64'), dtype('int64')]
    >>> inferred.spark_type
    StructType(List(StructField(a,DoubleType,true),StructField(b,LongType,true)))

    >>> def func() -> "ps.DataFrame['a': np.float, 'b': int]":
    ...     pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtypes
    [dtype('float64'), dtype('int64')]
    >>> inferred.spark_type
    StructType(List(StructField(a,DoubleType,true),StructField(b,LongType,true)))

    >>> pdf = pd.DataFrame({"a": [1, 2, 3], "b": [3, 4, 5]})
    >>> def func() -> ps.DataFrame[pdf.dtypes]:
    ...     pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtypes
    [dtype('int64'), dtype('int64')]
    >>> inferred.spark_type
    StructType(List(StructField(c0,LongType,true),StructField(c1,LongType,true)))

    >>> pdf = pd.DataFrame({"a": [1, 2, 3], "b": [3, 4, 5]})
    >>> def func() -> ps.DataFrame[zip(pdf.columns, pdf.dtypes)]:
    ...     pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtypes
    [dtype('int64'), dtype('int64')]
    >>> inferred.spark_type
    StructType(List(StructField(a,LongType,true),StructField(b,LongType,true)))

    >>> pdf = pd.DataFrame({("x", "a"): [1, 2, 3], ("y", "b"): [3, 4, 5]})
    >>> def func() -> ps.DataFrame[zip(pdf.columns, pdf.dtypes)]:
    ...     pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtypes
    [dtype('int64'), dtype('int64')]
    >>> inferred.spark_type
    StructType(List(StructField((x, a),LongType,true),StructField((y, b),LongType,true)))

    >>> pdf = pd.DataFrame({"a": [1, 2, 3], "b": pd.Categorical([3, 4, 5])})
    >>> def func() -> ps.DataFrame[pdf.dtypes]:
    ...     pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtypes
    [dtype('int64'), CategoricalDtype(categories=[3, 4, 5], ordered=False)]
    >>> inferred.spark_type
    StructType(List(StructField(c0,LongType,true),StructField(c1,LongType,true)))

    >>> def func() -> ps.DataFrame[zip(pdf.columns, pdf.dtypes)]:
    ...     pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtypes
    [dtype('int64'), CategoricalDtype(categories=[3, 4, 5], ordered=False)]
    >>> inferred.spark_type
    StructType(List(StructField(a,LongType,true),StructField(b,LongType,true)))

    >>> def func() -> ps.Series[pdf.b.dtype]:
    ...     pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtype
    CategoricalDtype(categories=[3, 4, 5], ordered=False)
    >>> inferred.spark_type
    LongType

    >>> def func() -> ps.DataFrame[int, [int, int]]:
    ...     pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtypes
    [dtype('int64'), dtype('int64'), dtype('int64')]
    >>> inferred.spark_type.simpleString()
    'struct<__index_level_0__:bigint,c0:bigint,c1:bigint>'
    >>> inferred.index_field
    InternalField(dtype=int64,struct_field=StructField(__index_level_0__,LongType,true))

    >>> def func() -> ps.DataFrame[pdf.index.dtype, pdf.dtypes]:
    ...     pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtypes
    [dtype('int64'), dtype('int64'), CategoricalDtype(categories=[3, 4, 5], ordered=False)]
    >>> inferred.spark_type.simpleString()
    'struct<__index_level_0__:bigint,c0:bigint,c1:bigint>'
    >>> inferred.index_field
    InternalField(dtype=int64,struct_field=StructField(__index_level_0__,LongType,true))

    >>> def func() -> ps.DataFrame[
    ...     ("index", CategoricalDtype(categories=[3, 4, 5], ordered=False)),
    ...     [("id", int), ("A", int)]]:
    ...     pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtypes
    [CategoricalDtype(categories=[3, 4, 5], ordered=False), dtype('int64'), dtype('int64')]
    >>> inferred.spark_type.simpleString()
    'struct<index:bigint,id:bigint,A:bigint>'
    >>> inferred.index_field
    InternalField(dtype=category,struct_field=StructField(index,LongType,true))

    >>> def func() -> ps.DataFrame[
    ...         (pdf.index.name, pdf.index.dtype), zip(pdf.columns, pdf.dtypes)]:
    ...     pass
    >>> inferred = infer_return_type(func)
    >>> inferred.dtypes
    [dtype('int64'), dtype('int64'), CategoricalDtype(categories=[3, 4, 5], ordered=False)]
    >>> inferred.spark_type.simpleString()
    'struct<__index_level_0__:bigint,a:bigint,b:bigint>'
    >>> inferred.index_field
    InternalField(dtype=int64,struct_field=StructField(__index_level_0__,LongType,true))
    """
    # We should re-import to make sure the class 'SeriesType' is not treated as a class
    # within this module locally. See Series.__class_getitem__ which imports this class
    # canonically.
    from pyspark.pandas.internal import InternalField, SPARK_DEFAULT_INDEX_NAME
    from pyspark.pandas.typedef import SeriesType, NameTypeHolder, IndexNameTypeHolder
    from pyspark.pandas.utils import name_like_string

    spec = getfullargspec(f)
    tpe = spec.annotations.get("return", None)
    if isinstance(tpe, str):
        # This type hint can happen when given hints are string to avoid forward reference.
        tpe = resolve_string_type_hint(tpe)

    if hasattr(tpe, "__origin__") and (
        tpe.__origin__ == ps.DataFrame or tpe.__origin__ == ps.Series
    ):
        # When Python version is lower then 3.7. Unwrap it to a Tuple/SeriesType type hints.
        tpe = tpe.__args__[0]

    if hasattr(tpe, "__origin__") and issubclass(tpe.__origin__, SeriesType):
        tpe = tpe.__args__[0]
        if issubclass(tpe, NameTypeHolder):
            tpe = tpe.tpe
        dtype, spark_type = pandas_on_spark_type(tpe)
        return SeriesType(dtype, spark_type)

    # Note that, DataFrame type hints will create a Tuple.
    # Python 3.6 has `__name__`. Python 3.7 and 3.8 have `_name`.
    # Check if the name is Tuple.
    name = getattr(tpe, "_name", getattr(tpe, "__name__", None))
    if name == "Tuple":
        tuple_type = tpe
        if hasattr(tuple_type, "__tuple_params__"):
            # Python 3.5.0 to 3.5.2 has '__tuple_params__' instead.
            # See https://github.com/python/cpython/blob/v3.5.2/Lib/typing.py
            parameters = getattr(tuple_type, "__tuple_params__")
        else:
            parameters = getattr(tuple_type, "__args__")

        index_parameters = [p for p in parameters if issubclass(p, IndexNameTypeHolder)]
        data_parameters = [p for p in parameters if p not in index_parameters]
        assert len(data_parameters) > 0, "Type hints for data must not be empty."

        if len(index_parameters) == 1:
            index_name = index_parameters[0].name
            index_dtype, index_spark_type = pandas_on_spark_type(index_parameters[0].tpe)
            index_field = InternalField(
                dtype=index_dtype,
                struct_field=types.StructField(
                    name=index_name if index_name is not None else SPARK_DEFAULT_INDEX_NAME,
                    dataType=index_spark_type,
                ),
            )
        else:
            assert len(index_parameters) == 0
            # No type hint for index.
            index_field = None

        data_dtypes, data_spark_types = zip(
            *(
                pandas_on_spark_type(p.tpe)
                if isclass(p) and issubclass(p, NameTypeHolder)
                else pandas_on_spark_type(p)
                for p in data_parameters
            )
        )
        data_names = [
            p.name if isclass(p) and issubclass(p, NameTypeHolder) else None
            for p in data_parameters
        ]
        data_fields = []
        for i, (data_name, data_dtype, data_spark_type) in enumerate(
            zip(data_names, data_dtypes, data_spark_types)
        ):
            data_fields.append(
                InternalField(
                    dtype=data_dtype,
                    struct_field=types.StructField(
                        name=name_like_string(data_name) if data_name is not None else ("c%s" % i),
                        dataType=data_spark_type,
                    ),
                )
            )

        return DataFrameType(index_field=index_field, data_fields=data_fields)

    tpes = pandas_on_spark_type(tpe)
    if tpes is None:
        return UnknownType(tpe)
    else:
        return ScalarType(*tpes)


# TODO: once pandas exposes a typing module like numpy.typing, we should deprecate
#   this logic and migrate to it with implementing the typing module in pandas API on Spark.


def create_type_for_series_type(param: Any) -> Type[SeriesType]:
    """
    Supported syntax:

    >>> str(ps.Series[float]).endswith("SeriesType[float]")
    True
    """
    from pyspark.pandas.typedef import NameTypeHolder

    if isinstance(param, ExtensionDtype):
        new_class = type("NameType", (NameTypeHolder,), {})  # type: Type[NameTypeHolder]
        new_class.tpe = param
    else:
        new_class = param.type if isinstance(param, np.dtype) else param

    return SeriesType[new_class]  # type: ignore


# TODO: Remove this variadic-generic hack by tuple once ww drop Python up to 3.9.
#   See also PEP 646. One problem is that pandas doesn't inherits Generic[T]
#   so we might have to leave this hack only for monkey-patching pandas DataFrame.
def create_tuple_for_frame_type(params: Any) -> object:
    """
    This is a workaround to support variadic generic in DataFrame.

    See https://github.com/python/typing/issues/193
    we always wraps the given type hints by a tuple to mimic the variadic generic.

    Supported syntax:

    >>> import pandas as pd
    >>> pdf = pd.DataFrame({'a': range(1)})

    Typing data columns only:

        >>> ps.DataFrame[float, float]
        typing.Tuple[float, float]
        >>> ps.DataFrame[pdf.dtypes]
        typing.Tuple[numpy.int64]
        >>> ps.DataFrame["id": int, "A": int]  # doctest: +ELLIPSIS
        typing.Tuple[...NameType, ...NameType]
        >>> ps.DataFrame[zip(pdf.columns, pdf.dtypes)]  # doctest: +ELLIPSIS
        typing.Tuple[...NameType]

    Typing data columns with an index:

        >>> ps.DataFrame[int, [int, int]]  # doctest: +ELLIPSIS
        typing.Tuple[...IndexNameType, int, int]
        >>> ps.DataFrame[pdf.index.dtype, pdf.dtypes]  # doctest: +ELLIPSIS
        typing.Tuple[...IndexNameType, numpy.int64]
        >>> ps.DataFrame[("index", int), [("id", int), ("A", int)]]  # doctest: +ELLIPSIS
        typing.Tuple[...IndexNameType, ...NameType, ...NameType]
        >>> ps.DataFrame[(pdf.index.name, pdf.index.dtype), zip(pdf.columns, pdf.dtypes)]
        ... # doctest: +ELLIPSIS
        typing.Tuple[...IndexNameType, ...NameType]
    """
    return Tuple[extract_types(params)]


# TODO(SPARK-36708): numpy.typing (numpy 1.21+) support for nested types.
def extract_types(params: Any) -> Tuple:
    origin = params
    if isinstance(params, zip):  # type: ignore
        # Example:
        #   DataFrame[zip(pdf.columns, pdf.dtypes)]
        params = tuple(slice(name, tpe) for name, tpe in params)  # type: ignore

    if isinstance(params, Iterable):
        params = tuple(params)
    else:
        params = (params,)

    if all(
        isinstance(param, slice)
        and param.start is not None
        and param.step is None
        and param.stop is not None
        for param in params
    ):
        # Example:
        #   DataFrame["id": int, "A": int]
        new_params = []
        for param in params:
            new_param = type("NameType", (NameTypeHolder,), {})  # type: Type[NameTypeHolder]
            new_param.name = param.start
            # When the given argument is a numpy's dtype instance.
            new_param.tpe = param.stop.type if isinstance(param.stop, np.dtype) else param.stop
            new_params.append(new_param)

        return tuple(new_params)
    elif len(params) == 2 and isinstance(params[1], (zip, list, pd.Series)):
        # Example:
        #   DataFrame[int, [int, int]]
        #   DataFrame[pdf.index.dtype, pdf.dtypes]
        #   DataFrame[("index", int), [("id", int), ("A", int)]]
        #   DataFrame[(pdf.index.name, pdf.index.dtype), zip(pdf.columns, pdf.dtypes)]

        index_param = params[0]
        index_type = type(
            "IndexNameType", (IndexNameTypeHolder,), {}
        )  # type: Type[IndexNameTypeHolder]
        if isinstance(index_param, tuple):
            if len(index_param) != 2:
                raise TypeError(
                    "Type hints for index should be specified as "
                    "DataFrame[('name', type), ...]; however, got %s" % index_param
                )
            name, tpe = index_param
        else:
            name, tpe = None, index_param

        index_type.name = name
        if isinstance(tpe, ExtensionDtype):
            index_type.tpe = tpe
        else:
            index_type.tpe = tpe.type if isinstance(tpe, np.dtype) else tpe

        data_types = params[1]
        if (
            isinstance(data_types, list)
            and len(data_types) >= 1
            and isinstance(data_types[0], tuple)
        ):  # type: ignore
            # Example:
            #   DataFrame[("index", int), [("id", int), ("A", int)]]
            data_types = zip((name for name, _ in data_types), (tpe for _, tpe in data_types))
        return (index_type,) + extract_types(data_types)
    elif all(not isinstance(param, slice) and not isinstance(param, Iterable) for param in params):
        # Exaxmples:
        #   DataFrame[float, float]
        #   DataFrame[pdf.dtypes]
        new_types = []
        for param in params:
            if isinstance(param, ExtensionDtype):
                new_type = type("NameType", (NameTypeHolder,), {})  # type: Type[NameTypeHolder]
                new_type.tpe = param
                new_types.append(new_type)
            else:
                new_types.append(param.type if isinstance(param, np.dtype) else param)
        return tuple(new_types)
    else:
        raise TypeError(
            """Type hints should be specified as one of:
  - DataFrame[type, type, ...]
  - DataFrame[name: type, name: type, ...]
  - DataFrame[dtypes instance]
  - DataFrame[zip(names, types)]
  - DataFrame[index_type, [type, ...]]
  - DataFrame[(index_name, index_type), [(name, type), ...]]
  - DataFrame[dtype instance, dtypes instance]
  - DataFrame[(index_name, index_type), zip(names, types)]\n"""
            + "However, got %s." % str(origin)
        )


def _test() -> None:
    import doctest
    import sys
    import pyspark.pandas.typedef.typehints

    globs = pyspark.pandas.typedef.typehints.__dict__.copy()
    (failure_count, test_count) = doctest.testmod(
        pyspark.pandas.typedef.typehints,
        globs=globs,
        optionflags=doctest.ELLIPSIS | doctest.NORMALIZE_WHITESPACE,
    )
    if failure_count:
        sys.exit(-1)


if __name__ == "__main__":
    _test()
