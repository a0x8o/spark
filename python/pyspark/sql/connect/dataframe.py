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

from typing import (
    Any,
    Dict,
    List,
    Optional,
    Sequence,
    Tuple,
    Union,
    TYPE_CHECKING,
    overload,
)

import pandas

import pyspark.sql.connect.plan as plan
from pyspark.sql.connect.column import (
    Column,
    Expression,
    LiteralExpression,
)
from pyspark.sql.types import (
    StructType,
    Row,
)

if TYPE_CHECKING:
    from pyspark.sql.connect.typing import ColumnOrString, ExpressionOrString
    from pyspark.sql.connect.client import RemoteSparkSession

ColumnOrName = Union[Column, str]


class GroupingFrame(object):

    MeasuresType = Union[Sequence[Tuple["ExpressionOrString", str]], Dict[str, str]]
    OptMeasuresType = Optional[MeasuresType]

    def __init__(self, df: "DataFrame", *grouping_cols: Union[Column, str]) -> None:
        self._df = df
        self._grouping_cols = [x if isinstance(x, Column) else df[x] for x in grouping_cols]

    def agg(self, exprs: Optional[MeasuresType] = None) -> "DataFrame":

        # Normalize the dictionary into a list of tuples.
        if isinstance(exprs, Dict):
            measures = list(exprs.items())
        elif isinstance(exprs, List):
            measures = exprs
        else:
            measures = []

        res = DataFrame.withPlan(
            plan.Aggregate(
                child=self._df._plan,
                grouping_cols=self._grouping_cols,
                measures=measures,
            ),
            session=self._df._session,
        )
        return res

    def _map_cols_to_dict(self, fun: str, cols: List[Union[Column, str]]) -> Dict[str, str]:
        return {x if isinstance(x, str) else x.name(): fun for x in cols}

    def min(self, *cols: Union[Column, str]) -> "DataFrame":
        expr = self._map_cols_to_dict("min", list(cols))
        return self.agg(expr)

    def max(self, *cols: Union[Column, str]) -> "DataFrame":
        expr = self._map_cols_to_dict("max", list(cols))
        return self.agg(expr)

    def sum(self, *cols: Union[Column, str]) -> "DataFrame":
        expr = self._map_cols_to_dict("sum", list(cols))
        return self.agg(expr)

    def count(self) -> "DataFrame":
        return self.agg([(LiteralExpression(1), "count")])


class DataFrame(object):
    """Every DataFrame object essentially is a Relation that is refined using the
    member functions. Calling a method on a dataframe will essentially return a copy
    of the DataFrame with the changes applied.
    """

    def __init__(
        self,
        session: "RemoteSparkSession",
        data: Optional[List[Any]] = None,
        schema: Optional[StructType] = None,
    ):
        """Creates a new data frame"""
        self._schema = schema
        self._plan: Optional[plan.LogicalPlan] = None
        self._cache: Dict[str, Any] = {}
        self._session: "RemoteSparkSession" = session

    @classmethod
    def withPlan(cls, plan: plan.LogicalPlan, session: "RemoteSparkSession") -> "DataFrame":
        """Main initialization method used to construct a new data frame with a child plan."""
        new_frame = DataFrame(session=session)
        new_frame._plan = plan
        return new_frame

    def select(self, *cols: ColumnOrName) -> "DataFrame":
        return DataFrame.withPlan(plan.Project(self._plan, *cols), session=self._session)

    def agg(self, exprs: Optional[GroupingFrame.MeasuresType]) -> "DataFrame":
        return self.groupBy().agg(exprs)

    def alias(self, alias: str) -> "DataFrame":
        return DataFrame.withPlan(plan.SubqueryAlias(self._plan, alias), session=self._session)

    def approxQuantile(self, col: Column, probabilities: Any, relativeError: Any) -> "DataFrame":
        ...

    def colRegex(self, regex: str) -> "DataFrame":
        ...

    @property
    def columns(self) -> List[str]:
        """Returns the list of columns of the current data frame."""
        if self._plan is None:
            return []

        return self.schema().names

    def sparkSession(self) -> "RemoteSparkSession":
        """Returns Spark session that created this :class:`DataFrame`.

        .. versionadded:: 3.4.0

        Returns
        -------
        :class:`RemoteSparkSession`
        """
        return self._session

    def count(self) -> int:
        """Returns the number of rows in the data frame"""
        pdd = self.agg([(LiteralExpression(1), "count")]).toPandas()
        if pdd is None:
            raise Exception("Empty result")
        return pdd.iloc[0, 0]

    def crossJoin(self, other: "DataFrame") -> "DataFrame":
        ...

    def coalesce(self, numPartitions: int) -> "DataFrame":
        """
        Returns a new :class:`DataFrame` that has exactly `numPartitions` partitions.

        Coalesce does not trigger a shuffle.

        .. versionadded:: 3.4.0

        Parameters
        ----------
        numPartitions : int
            specify the target number of partitions

        Returns
        -------
        :class:`DataFrame`
        """
        if not numPartitions > 0:
            raise ValueError("numPartitions must be positive.")
        return DataFrame.withPlan(
            plan.Repartition(self._plan, num_partitions=numPartitions, shuffle=False),
            self._session,
        )

    def repartition(self, numPartitions: int) -> "DataFrame":
        """
        Returns a new :class:`DataFrame` that has exactly `numPartitions` partitions.

        Repartition will shuffle source partition into partitions specified by numPartitions.

        .. versionadded:: 3.4.0

        Parameters
        ----------
        numPartitions : int
            specify the target number of partitions

        Returns
        -------
        :class:`DataFrame`
        """
        if not numPartitions > 0:
            raise ValueError("numPartitions must be positive.")
        return DataFrame.withPlan(
            plan.Repartition(self._plan, num_partitions=numPartitions, shuffle=True),
            self._session,
        )

    def describe(self, cols: List[Column]) -> Any:
        ...

    def dropDuplicates(self, subset: Optional[List[str]] = None) -> "DataFrame":
        """Return a new :class:`DataFrame` with duplicate rows removed,
        optionally only deduplicating based on certain columns.

        .. versionadded:: 3.4.0

        Parameters
        ----------
        subset : List of column names, optional
            List of columns to use for duplicate comparison (default All columns).

        Returns
        -------
        :class:`DataFrame`
            DataFrame without duplicated rows.
        """
        if subset is None:
            return DataFrame.withPlan(
                plan.Deduplicate(child=self._plan, all_columns_as_keys=True), session=self._session
            )
        else:
            return DataFrame.withPlan(
                plan.Deduplicate(child=self._plan, column_names=subset), session=self._session
            )

    def distinct(self) -> "DataFrame":
        """Returns a new :class:`DataFrame` containing the distinct rows in this :class:`DataFrame`.

        .. versionadded:: 3.4.0

        Returns
        -------
        :class:`DataFrame`
            DataFrame with distinct rows.
        """
        return DataFrame.withPlan(
            plan.Deduplicate(child=self._plan, all_columns_as_keys=True), session=self._session
        )

    def drop(self, *cols: "ColumnOrString") -> "DataFrame":
        all_cols = self.columns
        dropped = set([c.name() if isinstance(c, Column) else self[c].name() for c in cols])
        dropped_cols = filter(lambda x: x in dropped, all_cols)
        return DataFrame.withPlan(plan.Project(self._plan, *dropped_cols), session=self._session)

    def filter(self, condition: Expression) -> "DataFrame":
        return DataFrame.withPlan(
            plan.Filter(child=self._plan, filter=condition), session=self._session
        )

    def first(self) -> Optional[Row]:
        """Returns the first row as a :class:`Row`.

        .. versionadded:: 3.4.0

        Returns
        -------
        :class:`Row`
           First row if :class:`DataFrame` is not empty, otherwise ``None``.
        """
        return self.head()

    def groupBy(self, *cols: "ColumnOrString") -> GroupingFrame:
        return GroupingFrame(self, *cols)

    @overload
    def head(self) -> Optional[Row]:
        ...

    @overload
    def head(self, n: int) -> List[Row]:
        ...

    def head(self, n: Optional[int] = None) -> Union[Optional[Row], List[Row]]:
        """Returns the first ``n`` rows.

        .. versionadded:: 3.4.0

        Parameters
        ----------
        n : int, optional
            default 1. Number of rows to return.

        Returns
        -------
        If n is greater than 1, return a list of :class:`Row`.
        If n is 1, return a single Row.
        """
        if n is None:
            rs = self.head(1)
            return rs[0] if rs else None
        return self.take(n)

    def take(self, num: int) -> List[Row]:
        """Returns the first ``num`` rows as a :class:`list` of :class:`Row`.

        .. versionadded:: 3.4.0

        Parameters
        ----------
        num : int
            Number of records to return. Will return this number of records
            or whataver number is available.

        Returns
        -------
        list
            List of rows
        """
        return self.limit(num).collect()

    # TODO: extend `on` to also be type List[Column].
    def join(
        self,
        other: "DataFrame",
        on: Optional[Union[str, List[str], Column]] = None,
        how: Optional[str] = None,
    ) -> "DataFrame":
        if self._plan is None:
            raise Exception("Cannot join when self._plan is empty.")
        if other._plan is None:
            raise Exception("Cannot join when other._plan is empty.")

        return DataFrame.withPlan(
            plan.Join(left=self._plan, right=other._plan, on=on, how=how),
            session=self._session,
        )

    def limit(self, n: int) -> "DataFrame":
        return DataFrame.withPlan(plan.Limit(child=self._plan, limit=n), session=self._session)

    def offset(self, n: int) -> "DataFrame":
        return DataFrame.withPlan(plan.Offset(child=self._plan, offset=n), session=self._session)

    def sort(self, *cols: "ColumnOrString") -> "DataFrame":
        """Sort by a specific column"""
        return DataFrame.withPlan(
            plan.Sort(self._plan, columns=list(cols), is_global=True), session=self._session
        )

    def sortWithinPartitions(self, *cols: "ColumnOrString") -> "DataFrame":
        """Sort within each partition by a specific column"""
        return DataFrame.withPlan(
            plan.Sort(self._plan, columns=list(cols), is_global=False), session=self._session
        )

    def sample(
        self,
        fraction: float,
        *,
        withReplacement: bool = False,
        seed: Optional[int] = None,
    ) -> "DataFrame":
        if not isinstance(fraction, float):
            raise TypeError(f"'fraction' must be float, but got {type(fraction).__name__}")
        if not isinstance(withReplacement, bool):
            raise TypeError(
                f"'withReplacement' must be bool, but got {type(withReplacement).__name__}"
            )
        if seed is not None and not isinstance(seed, int):
            raise TypeError(f"'seed' must be None or int, but got {type(seed).__name__}")

        return DataFrame.withPlan(
            plan.Sample(
                child=self._plan,
                lower_bound=0.0,
                upper_bound=fraction,
                with_replacement=withReplacement,
                seed=seed,
            ),
            session=self._session,
        )

    def _show_string(
        self, n: int = 20, truncate: Union[bool, int] = True, vertical: bool = False
    ) -> str:
        if not isinstance(n, int) or isinstance(n, bool):
            raise TypeError("Parameter 'n' (number of rows) must be an int")
        if not isinstance(vertical, bool):
            raise TypeError("Parameter 'vertical' must be a bool")

        _truncate: int = -1
        if isinstance(truncate, bool) and truncate:
            _truncate = 20
        else:
            try:
                _truncate = int(truncate)
            except ValueError:
                raise TypeError(
                    "Parameter 'truncate={}' should be either bool or int.".format(truncate)
                )

        pdf = DataFrame.withPlan(
            plan.ShowString(child=self._plan, numRows=n, truncate=_truncate, vertical=vertical),
            session=self._session,
        ).toPandas()
        assert pdf is not None
        return pdf["show_string"][0]

    def show(self, n: int = 20, truncate: Union[bool, int] = True, vertical: bool = False) -> None:
        """
        Prints the first ``n`` rows to the console.

        .. versionadded:: 3.4.0

        Parameters
        ----------
        n : int, optional
            Number of rows to show.
        truncate : bool or int, optional
            If set to ``True``, truncate strings longer than 20 chars by default.
            If set to a number greater than one, truncates long strings to length ``truncate``
            and align cells right.
        vertical : bool, optional
            If set to ``True``, print output rows vertically (one line
            per column value).
        """
        print(self._show_string(n, truncate, vertical))

    def union(self, other: "DataFrame") -> "DataFrame":
        return self.unionAll(other)

    def unionAll(self, other: "DataFrame") -> "DataFrame":
        if other._plan is None:
            raise ValueError("Argument to Union does not contain a valid plan.")
        return DataFrame.withPlan(
            plan.SetOperation(self._plan, other._plan, "union", is_all=True), session=self._session
        )

    def unionByName(self, other: "DataFrame", allowMissingColumns: bool = False) -> "DataFrame":
        """Returns a new :class:`DataFrame` containing union of rows in this and another
        :class:`DataFrame`.

        This is different from both `UNION ALL` and `UNION DISTINCT` in SQL. To do a SQL-style set
        union (that does deduplication of elements), use this function followed by :func:`distinct`.

        .. versionadded:: 3.4.0

        Parameters
        ----------
        other : :class:`DataFrame`
            Another :class:`DataFrame` that needs to be combined.
        allowMissingColumns : bool, optional, default False
           Specify whether to allow missing columns.

        Returns
        -------
        :class:`DataFrame`
            Combined DataFrame.
        """
        if other._plan is None:
            raise ValueError("Argument to UnionByName does not contain a valid plan.")
        return DataFrame.withPlan(
            plan.SetOperation(
                self._plan, other._plan, "union", is_all=True, by_name=allowMissingColumns
            ),
            session=self._session,
        )

    def exceptAll(self, other: "DataFrame") -> "DataFrame":
        """Return a new :class:`DataFrame` containing rows in this :class:`DataFrame` but
        not in another :class:`DataFrame` while preserving duplicates.

        This is equivalent to `EXCEPT ALL` in SQL.
        As standard in SQL, this function resolves columns by position (not by name).

        .. versionadded:: 3.4.0

        Parameters
        ----------
        other : :class:`DataFrame`
            The other :class:`DataFrame` to compare to.

        Returns
        -------
        :class:`DataFrame`
        """
        return DataFrame.withPlan(
            plan.SetOperation(self._plan, other._plan, "except", is_all=True), session=self._session
        )

    def intersect(self, other: "DataFrame") -> "DataFrame":
        """Return a new :class:`DataFrame` containing rows only in
        both this :class:`DataFrame` and another :class:`DataFrame`.
        Note that any duplicates are removed. To preserve duplicates
        use :func:`intersectAll`.

        .. versionadded:: 3.4.0

        Parameters
        ----------
        other : :class:`DataFrame`
            Another :class:`DataFrame` that needs to be combined.

        Returns
        -------
        :class:`DataFrame`
            Combined DataFrame.

        Notes
        -----
        This is equivalent to `INTERSECT` in SQL.
        """
        return DataFrame.withPlan(
            plan.SetOperation(self._plan, other._plan, "intersect", is_all=False),
            session=self._session,
        )

    def intersectAll(self, other: "DataFrame") -> "DataFrame":
        """Return a new :class:`DataFrame` containing rows in both this :class:`DataFrame`
        and another :class:`DataFrame` while preserving duplicates.

        This is equivalent to `INTERSECT ALL` in SQL. As standard in SQL, this function
        resolves columns by position (not by name).

        .. versionadded:: 3.4.0

        Parameters
        ----------
        other : :class:`DataFrame`
            Another :class:`DataFrame` that needs to be combined.

        Returns
        -------
        :class:`DataFrame`
            Combined DataFrame.
        """
        return DataFrame.withPlan(
            plan.SetOperation(self._plan, other._plan, "intersect", is_all=True),
            session=self._session,
        )

    def where(self, condition: Expression) -> "DataFrame":
        return self.filter(condition)

    @property
    def stat(self) -> "DataFrameStatFunctions":
        """Returns a :class:`DataFrameStatFunctions` for statistic functions.

        .. versionadded:: 3.4.0

        Returns
        -------
        :class:`DataFrameStatFunctions`
        """
        return DataFrameStatFunctions(self)

    def summary(self, *statistics: str) -> "DataFrame":
        _statistics: List[str] = list(statistics)
        for s in _statistics:
            if not isinstance(s, str):
                raise TypeError(f"'statistics' must be list[str], but got {type(s).__name__}")
        return DataFrame.withPlan(
            plan.StatSummary(child=self._plan, statistics=_statistics),
            session=self._session,
        )

    def crosstab(self, col1: str, col2: str) -> "DataFrame":
        """
        Computes a pair-wise frequency table of the given columns. Also known as a contingency
        table. The number of distinct values for each column should be less than 1e4. At most 1e6
        non-zero pair frequencies will be returned.
        The first column of each row will be the distinct values of `col1` and the column names
        will be the distinct values of `col2`. The name of the first column will be `$col1_$col2`.
        Pairs that have no occurrences will have zero as their counts.
        :func:`DataFrame.crosstab` and :func:`DataFrameStatFunctions.crosstab` are aliases.

        .. versionadded:: 3.4.0

        Parameters
        ----------
        col1 : str
            The name of the first column. Distinct items will make the first item of
            each row.
        col2 : str
            The name of the second column. Distinct items will make the column names
            of the :class:`DataFrame`.

        Returns
        -------
        :class:`DataFrame`
            Frequency matrix of two columns.
        """
        if not isinstance(col1, str):
            raise TypeError(f"'col1' must be str, but got {type(col1).__name__}")
        if not isinstance(col2, str):
            raise TypeError(f"'col2' must be str, but got {type(col2).__name__}")
        return DataFrame.withPlan(
            plan.StatCrosstab(child=self._plan, col1=col1, col2=col2),
            session=self._session,
        )

    def _get_alias(self) -> Optional[str]:
        p = self._plan
        while p is not None:
            if isinstance(p, plan.Project) and p.alias:
                return p.alias
            p = p._child
        return None

    def __getattr__(self, name: str) -> "Column":
        return self[name]

    def __getitem__(self, name: str) -> "Column":
        # Check for alias
        alias = self._get_alias()
        if alias is not None:
            return Column(alias)
        else:
            return Column(name)

    def _print_plan(self) -> str:
        if self._plan:
            return self._plan.print()
        return ""

    def collect(self) -> List[Row]:
        pdf = self.toPandas()
        if pdf is not None:
            return list(pdf.apply(lambda row: Row(**row), axis=1))
        else:
            return []

    def toPandas(self) -> Optional["pandas.DataFrame"]:
        if self._plan is None:
            raise Exception("Cannot collect on empty plan.")
        if self._session is None:
            raise Exception("Cannot collect on empty session.")
        query = self._plan.to_proto(self._session)
        return self._session._to_pandas(query)

    def schema(self) -> StructType:
        """Returns the schema of this :class:`DataFrame` as a :class:`pyspark.sql.types.StructType`.

        .. versionadded:: 3.4.0

        Returns
        -------
        :class:`StructType`
        """
        if self._schema is None:
            if self._plan is not None:
                query = self._plan.to_proto(self._session)
                if self._session is None:
                    raise Exception("Cannot analyze without RemoteSparkSession.")
                self._schema = self._session.schema(query)
                return self._schema
            else:
                raise Exception("Empty plan.")
        else:
            return self._schema

    def explain(self) -> str:
        if self._plan is not None:
            query = self._plan.to_proto(self._session)
            if self._session is None:
                raise Exception("Cannot analyze without RemoteSparkSession.")
            return self._session.explain_string(query)
        else:
            return ""


class DataFrameStatFunctions:
    """Functionality for statistic functions with :class:`DataFrame`.

    .. versionadded:: 3.4.0
    """

    def __init__(self, df: DataFrame):
        self.df = df

    def crosstab(self, col1: str, col2: str) -> DataFrame:
        return self.df.crosstab(col1, col2)

    crosstab.__doc__ = DataFrame.crosstab.__doc__
