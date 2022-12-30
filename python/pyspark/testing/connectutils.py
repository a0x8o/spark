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
import os
from typing import Any, Dict
import functools
import unittest
import uuid

from pyspark.testing.utils import search_jar


connect_jar = search_jar("connector/connect", "spark-connect-assembly-", "spark-connect")
if connect_jar is None:
    connect_requirement_message = (
        "Skipping all Spark Connect Python tests as the optional Spark Connect project was "
        "not compiled into a JAR. To run these tests, you need to build Spark with "
        "'build/sbt package' or 'build/mvn package' before running this test."
    )
else:
    existing_args = os.environ.get("PYSPARK_SUBMIT_ARGS", "pyspark-shell")
    jars_args = "--jars %s" % connect_jar
    plugin_args = "--conf spark.plugins=org.apache.spark.sql.connect.SparkConnectPlugin"
    os.environ["PYSPARK_SUBMIT_ARGS"] = " ".join([jars_args, plugin_args, existing_args])
    connect_requirement_message = None  # type: ignore

should_test_connect = connect_requirement_message is None


class MockRemoteSession:
    def __init__(self) -> None:
        self.hooks: Dict[str, Any] = {}

    def set_hook(self, name: str, hook: Any) -> None:
        self.hooks[name] = hook

    def __getattr__(self, item: str) -> Any:
        if item not in self.hooks:
            raise LookupError(f"{item} is not defined as a method hook in MockRemoteSession")
        return functools.partial(self.hooks[item])


@unittest.skipIf(not should_test_connect, connect_requirement_message)
class PlanOnlyTestFixture(unittest.TestCase):
    @classmethod
    def setUpClass(cls: Any) -> None:
        cls.connect = MockRemoteSession()
        cls.tbl_name = f"tbl{uuid.uuid4()}".replace("-", "")
