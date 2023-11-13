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
from typing import Any, Dict, Optional
import uuid

from pyspark.errors import IllegalArgumentException
from pyspark.sql.connect.column import Column
from pyspark.sql.connect.dataframe import DataFrame
from pyspark.sql.observation import Observation as PySparkObservation
import pyspark.sql.connect.plan as plan


__all__ = ["Observation"]


class Observation:
    def __init__(self, name: Optional[str] = None) -> None:
        if name is not None:
            if not isinstance(name, str):
                raise TypeError("name should be a string")
            if name == "":
                raise ValueError("name should not be empty")
        self._name = name
        self._result: Optional[Dict[str, Any]] = None

    __init__.__doc__ = PySparkObservation.__init__.__doc__

    def _on(self, df: DataFrame, *exprs: Column) -> DataFrame:
        assert self._result is None, "an Observation can be used with a DataFrame only once"

        if self._name is None:
            self._name = str(uuid.uuid4())

        if df.isStreaming:
            raise IllegalArgumentException("Observation does not support streaming Datasets")

        self._result = {}
        return DataFrame.withPlan(plan.CollectMetrics(df._plan, self, list(exprs)), df._session)

    _on.__doc__ = PySparkObservation._on.__doc__

    @property
    def get(self) -> Dict[str, Any]:
        assert self._result is not None
        return self._result

    get.__doc__ = PySparkObservation.get.__doc__


Observation.__doc__ = PySparkObservation.__doc__


def _test() -> None:
    import sys
    import doctest
    from pyspark.sql import SparkSession as PySparkSession
    import pyspark.sql.connect.observation

    globs = pyspark.sql.connect.observation.__dict__.copy()
    globs["spark"] = (
        PySparkSession.builder.appName("sql.connect.observation tests")
        .remote("local[4]")
        .getOrCreate()
    )

    (failure_count, test_count) = doctest.testmod(
        pyspark.sql.connect.observation,
        globs=globs,
        optionflags=doctest.ELLIPSIS
        | doctest.NORMALIZE_WHITESPACE
        | doctest.IGNORE_EXCEPTION_DETAIL,
    )

    globs["spark"].stop()

    if failure_count:
        sys.exit(-1)


if __name__ == "__main__":
    _test()
