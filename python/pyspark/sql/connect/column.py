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
import uuid
from typing import cast, get_args, TYPE_CHECKING, Optional, Callable, Any

import decimal
import datetime

import pyspark.sql.connect.proto as proto
from pyspark.sql.connect._typing import PrimitiveType

if TYPE_CHECKING:
    from pyspark.sql.connect.client import RemoteSparkSession
    import pyspark.sql.connect.proto as proto


def _bin_op(
    name: str, doc: str = "binary function", reverse: bool = False
) -> Callable[["ColumnRef", Any], "Expression"]:
    def _(self: "ColumnRef", other: Any) -> "Expression":
        if isinstance(other, get_args(PrimitiveType)):
            other = LiteralExpression(other)
        if not reverse:
            return ScalarFunctionExpression(name, self, other)
        else:
            return ScalarFunctionExpression(name, other, self)

    return _


class Expression(object):
    """
    Expression base class.
    """

    __gt__ = _bin_op(">")
    __lt__ = _bin_op("<")
    __add__ = _bin_op("+")
    __sub__ = _bin_op("-")
    __mul__ = _bin_op("*")
    __div__ = _bin_op("/")
    __truediv__ = _bin_op("/")
    __mod__ = _bin_op("%")
    __radd__ = _bin_op("+", reverse=True)
    __rsub__ = _bin_op("-", reverse=True)
    __rmul__ = _bin_op("*", reverse=True)
    __rdiv__ = _bin_op("/", reverse=True)
    __rtruediv__ = _bin_op("/", reverse=True)
    __pow__ = _bin_op("pow")
    __rpow__ = _bin_op("pow", reverse=True)
    __ge__ = _bin_op(">=")
    __le__ = _bin_op("<=")

    def __eq__(self, other: Any) -> "Expression":  # type: ignore[override]
        """Returns a binary expression with the current column as the left
        side and the other expression as the right side.
        """
        if isinstance(other, get_args(PrimitiveType)):
            other = LiteralExpression(other)
        return ScalarFunctionExpression("==", self, other)

    def __init__(self) -> None:
        pass

    def to_plan(self, session: Optional["RemoteSparkSession"]) -> "proto.Expression":
        ...

    def __str__(self) -> str:
        ...


class LiteralExpression(Expression):
    """A literal expression.

    The Python types are converted best effort into the relevant proto types. On the Spark Connect
    server side, the proto types are converted to the Catalyst equivalents."""

    def __init__(self, value: Any) -> None:
        super().__init__()
        self._value = value

    def to_plan(self, session: Optional["RemoteSparkSession"]) -> "proto.Expression":
        """Converts the literal expression to the literal in proto.

        TODO(SPARK-40533) This method always assumes the largest type and can thus
             create weird interpretations of the literal."""
        value_type = type(self._value)
        exp = proto.Expression()
        if value_type is int:
            exp.literal.i64 = cast(int, self._value)
        elif value_type is bool:
            exp.literal.boolean = cast(bool, self._value)
        elif value_type is str:
            exp.literal.string = cast(str, self._value)
        elif value_type is float:
            exp.literal.fp64 = cast(float, self._value)
        elif value_type is decimal.Decimal:
            d_v = cast(decimal.Decimal, self._value)
            v_tuple = d_v.as_tuple()
            exp.literal.decimal.scale = abs(v_tuple.exponent)
            exp.literal.decimal.precision = len(v_tuple.digits) - abs(v_tuple.exponent)
            # Two complement yeah...
            raise ValueError("Python Decimal not supported.")
        elif value_type is bytes:
            exp.literal.binary = self._value
        elif value_type is datetime.datetime:
            # Microseconds since epoch.
            dt = cast(datetime.datetime, self._value)
            v = dt - datetime.datetime(1970, 1, 1, 0, 0, 0, 0)
            exp.literal.timestamp = int(v / datetime.timedelta(microseconds=1))
        elif value_type is datetime.time:
            # Nanoseconds of the day.
            tv = cast(datetime.time, self._value)
            offset = (tv.second + tv.minute * 60 + tv.hour * 3600) * 1000 + tv.microsecond
            exp.literal.time = int(offset * 1000)
        elif value_type is datetime.date:
            # Days since epoch.
            days_since_epoch = (cast(datetime.date, self._value) - datetime.date(1970, 1, 1)).days
            exp.literal.date = days_since_epoch
        elif value_type is uuid.UUID:
            raise ValueError("Python UUID type not supported.")
        elif value_type is list:
            lv = cast(list, self._value)
            for k in lv:
                if type(k) is LiteralExpression:
                    exp.literal.list.values.append(k.to_plan(session).literal)
                else:
                    exp.literal.list.values.append(LiteralExpression(k).to_plan(session).literal)
        elif value_type is dict:
            mv = cast(dict, self._value)
            for k in mv:
                kv = proto.Expression.Literal.Map.KeyValue()
                if type(k) is LiteralExpression:
                    kv.key.CopyFrom(k.to_plan(session).literal)
                else:
                    kv.key.CopyFrom(LiteralExpression(k).to_plan(session).literal)

                if type(mv[k]) is LiteralExpression:
                    kv.value.CopyFrom(mv[k].to_plan(session).literal)
                else:
                    kv.value.CopyFrom(LiteralExpression(mv[k]).to_plan(session).literal)
                exp.literal.map.key_values.append(kv)
        else:
            raise ValueError(f"Could not convert literal for type {type(self._value)}")

        return exp

    def __str__(self) -> str:
        return f"Literal({self._value})"


class ColumnRef(Expression):
    """Represents a column reference. There is no guarantee that this column
    actually exists. In the context of this project, we refer by its name and
    treat it as an unresolved attribute. Attributes that have the same fully
    qualified name are identical"""

    @classmethod
    def from_qualified_name(cls, name: str) -> "ColumnRef":
        return ColumnRef(name)

    def __init__(self, name: str) -> None:
        super().__init__()
        self._unparsed_identifier: str = name

    def name(self) -> str:
        """Returns the qualified name of the column reference."""
        return self._unparsed_identifier

    def to_plan(self, session: Optional["RemoteSparkSession"]) -> proto.Expression:
        """Returns the Proto representation of the expression."""
        expr = proto.Expression()
        expr.unresolved_attribute.unparsed_identifier = self._unparsed_identifier
        return expr

    def desc(self) -> "SortOrder":
        return SortOrder(self, ascending=False)

    def asc(self) -> "SortOrder":
        return SortOrder(self, ascending=True)

    def __str__(self) -> str:
        return f"Column({self._unparsed_identifier})"


class SortOrder(Expression):
    def __init__(self, col: ColumnRef, ascending: bool = True, nullsLast: bool = True) -> None:
        super().__init__()
        self.ref = col
        self.ascending = ascending
        self.nullsLast = nullsLast

    def __str__(self) -> str:
        return str(self.ref) + " ASC" if self.ascending else " DESC"

    def to_plan(self, session: Optional["RemoteSparkSession"]) -> proto.Expression:
        return self.ref.to_plan(session)


class ScalarFunctionExpression(Expression):
    def __init__(
        self,
        op: str,
        *args: Expression,
    ) -> None:
        super().__init__()
        self._args = args
        self._op = op

    def to_plan(self, session: Optional["RemoteSparkSession"]) -> proto.Expression:
        fun = proto.Expression()
        fun.unresolved_function.parts.append(self._op)
        fun.unresolved_function.arguments.extend([x.to_plan(session) for x in self._args])
        return fun

    def __str__(self) -> str:
        return f"({self._op} ({', '.join([str(x) for x in self._args])}))"
