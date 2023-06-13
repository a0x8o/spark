/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.python

import org.apache.spark.sql.{IntegratedUDFTestUtils, QueryTest, Row}
import org.apache.spark.sql.functions.lit
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types.StructType

class PythonUDTFSuite extends QueryTest with SharedSparkSession {

  import testImplicits._

  import IntegratedUDFTestUtils._

  private val pythonScript: String =
    """
      |from pyspark.sql.types import StructType, StructField, IntegerType
      |returnType = StructType([
      |  StructField("a", IntegerType()),
      |  StructField("b", IntegerType()),
      |  StructField("c", IntegerType()),
      |])
      |class SimpleUDTF:
      |    def eval(self, a: int, b: int):
      |        yield a, b, a + b
      |        yield a, b, a - b
      |        yield a, b, b - a
      |""".stripMargin

  private val returnType: StructType = StructType.fromDDL("a int, b int, c int")

  private val pythonUDTF: UserDefinedPythonTableFunction =
    createUserDefinedPythonTableFunction("SimpleUDTF", pythonScript, returnType)

  test("Simple PythonUDTF") {
    assume(shouldTestPythonUDFs)
    val df = pythonUDTF(spark, lit(1), lit(2))
    checkAnswer(df, Seq(Row(1, 2, -1), Row(1, 2, 1), Row(1, 2, 3)))
  }

  test("PythonUDTF with lateral join") {
    assume(shouldTestPythonUDFs)
    withTempView("t") {
      spark.udtf.registerPython("testUDTF", pythonUDTF)
      Seq((0, 1), (1, 2)).toDF("a", "b").createOrReplaceTempView("t")
      checkAnswer(
        sql("SELECT f.* FROM t, LATERAL testUDTF(a, b) f"),
        sql("SELECT * FROM t, LATERAL explode(array(a + b, a - b, b - a)) t(c)"))
    }
  }

  test("PythonUDTF in correlated subquery") {
    assume(shouldTestPythonUDFs)
    withTempView("t") {
      spark.udtf.registerPython("testUDTF", pythonUDTF)
      Seq((0, 1), (1, 2)).toDF("a", "b").createOrReplaceTempView("t")
      checkAnswer(
        sql("SELECT (SELECT sum(f.b) AS r FROM testUDTF(1, 2) f WHERE f.a = t.a) FROM t"),
        Seq(Row(6), Row(null)))
    }
  }
}
