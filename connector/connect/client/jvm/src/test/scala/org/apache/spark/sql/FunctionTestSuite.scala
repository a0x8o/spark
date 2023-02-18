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
package org.apache.spark.sql

import org.scalatest.funsuite.{AnyFunSuite => ConnectFunSuite} // scalastyle:ignore funsuite

import org.apache.spark.sql.functions._

/**
 * Tests for client local function behavior.
 *
 * This mostly tests is various function variants produce the same columns.
 */
class FunctionTestSuite extends ConnectFunSuite {
  private def testEquals(name: String, columns: Column*): Unit = {
    test(name) {
      assert(columns.nonEmpty)
      val unique = columns.distinct
      assert(unique.size == 1)
    }
  }

  private val a = col("a")
  private val b = col("b")
  private val c = col("c")

  testEquals("col/column", a, column("a"))
  testEquals("asc/asc_nulls_first", asc("a"), asc_nulls_first("a"))
  testEquals("desc/desc_nulls_last", desc("a"), desc_nulls_last("a"))
  testEquals(
    "approx_count_distinct",
    approxCountDistinct(a),
    approxCountDistinct("a"),
    approx_count_distinct("a"),
    approx_count_distinct(a))
  testEquals(
    "approx_count_distinct rsd",
    approxCountDistinct(a, 0.1),
    approxCountDistinct("a", 0.1),
    approx_count_distinct("a", 0.1),
    approx_count_distinct(a, 0.1))
  testEquals("avg/mean", avg("a"), avg(a), mean(a), mean("a"))
  testEquals("collect_list", collect_list("a"), collect_list(a))
  testEquals("collect_set", collect_set("a"), collect_set(a))
  testEquals("corr", corr("a", "b"), corr(a, b))
  testEquals(
    "count_distinct",
    countDistinct(a, b, c),
    countDistinct("a", "b", "c"),
    count_distinct(a, b, c))
  testEquals("covar_pop", covar_pop(a, b), covar_pop("a", "b"))
  testEquals("covar_samp", covar_samp(a, b), covar_samp("a", "b"))
  testEquals(
    "first",
    first("a"),
    first(a),
    first("a", ignoreNulls = false),
    first(a, ignoreNulls = false))
  testEquals("grouping", grouping("a"), grouping(a))
  testEquals("grouping_id", grouping_id("a", "b"), grouping_id(a, b))
  testEquals("kurtosis", kurtosis("a"), kurtosis(a))
  testEquals(
    "last",
    last("a"),
    last(a),
    last("a", ignoreNulls = false),
    last(a, ignoreNulls = false))
  testEquals("max", max("a"), max(a))
  testEquals("min", min("a"), min(a))
  testEquals("skewness", skewness("a"), skewness(a))
  testEquals("stddev", stddev("a"), stddev(a))
  testEquals("stddev_samp", stddev_samp("a"), stddev_samp(a))
  testEquals("stddev_pop", stddev_pop("a"), stddev_pop(a))
  testEquals("sum", sum("a"), sum(a))
  testEquals("sum_distinct", sumDistinct("a"), sumDistinct(a), sum_distinct(a))
  testEquals("variance", variance("a"), variance(a))
  testEquals("var_samp", var_samp("a"), var_samp(a))
  testEquals("var_pop", var_pop("a"), var_pop(a))
  testEquals("array", array(a, b, c), array("a", "b", "c"))
  testEquals(
    "monotonicallyIncreasingId",
    monotonicallyIncreasingId(),
    monotonically_increasing_id())
  testEquals("sqrt", sqrt("a"), sqrt(a))
  testEquals("struct", struct(a, c, b), struct("a", "c", "b"))
  testEquals("bitwise_not", bitwiseNOT(a), bitwise_not(a))
  testEquals("acos", acos("a"), acos(a))
  testEquals("acosh", acosh("a"), acosh(a))
  testEquals("asin", asin("a"), asin(a))
  testEquals("asinh", asinh("a"), asinh(a))
  testEquals("atan", atan("a"), atan(a))
  testEquals("atan2", atan2(a, b), atan2(a, "b"), atan2("a", b), atan2("a", "b"))
  testEquals("atanh", atanh("a"), atanh(a))
  testEquals("bin", bin("a"), bin(a))
  testEquals("cbrt", cbrt("a"), cbrt(a))
  testEquals("ceil", ceil(a), ceil("a"))
  testEquals("cos", cos("a"), cos(a))
  testEquals("cosh", cosh("a"), cosh(a))
  testEquals("exp", exp("a"), exp(a))
  testEquals("expm1", expm1("a"), expm1(a))
  testEquals("floor", floor(a), floor("a"))
  testEquals("greatest", greatest(a, b, c), greatest("a", "b", "c"))
  testEquals("hypot", hypot(a, b), hypot("a", b), hypot(a, "b"), hypot("a", "b"))
  testEquals(
    "hypot right fixed",
    hypot(lit(3d), a),
    hypot(lit(3d), "a"),
    hypot(3d, a),
    hypot(3d, "a"))
  testEquals(
    "hypot left fixed",
    hypot(a, lit(4d)),
    hypot(a, 4d),
    hypot("a", lit(4d)),
    hypot("a", 4d))
  testEquals("least", least(a, b, c), least("a", "b", "c"))
  testEquals("log", log("a"), log(a))
  testEquals("log base", log(2.0, "a"), log(2.0, a))
  testEquals("log10", log10("a"), log10(a))
  testEquals("log1p", log1p("a"), log1p(a))
  testEquals("log2", log2("a"), log2(a))
  testEquals("pow", pow(a, b), pow(a, "b"), pow("a", b), pow("a", "b"))
  testEquals("pow left fixed", pow(lit(7d), b), pow(lit(7d), "b"), pow(7d, b), pow(7d, "b"))
  testEquals("pow right fixed", pow(a, lit(9d)), pow(a, 9d), pow("a", lit(9d)), pow("a", 9d))
  testEquals("rint", rint(a), rint("a"))
  testEquals("round", round(a), round(a, 0))
  testEquals("bround", bround(a), bround(a, 0))
  testEquals("shiftleft", shiftLeft(a, 2), shiftleft(a, 2))
  testEquals("shiftright", shiftRight(a, 3), shiftright(a, 3))
  testEquals("shiftrightunsigned", shiftRightUnsigned(a, 3), shiftrightunsigned(a, 3))
  testEquals("signum", signum("a"), signum(a))
  testEquals("sin", sin("a"), sin(a))
  testEquals("sinh", sinh("a"), sinh(a))
  testEquals("tan", tan("a"), tan(a))
  testEquals("tanh", tanh("a"), tanh(a))
  testEquals("degrees", toDegrees(a), toDegrees("a"), degrees(a), degrees("a"))
  testEquals("radians", toRadians(a), toRadians("a"), radians(a), radians("a"))

  test("rand no seed") {
    val e = rand().expr
    assert(e.hasUnresolvedFunction)
    val fn = e.getUnresolvedFunction
    assert(fn.getFunctionName == "rand")
    assert(fn.getArgumentsCount == 0)
  }

  test("randn no seed") {
    val e = randn().expr
    assert(e.hasUnresolvedFunction)
    val fn = e.getUnresolvedFunction
    assert(fn.getFunctionName == "randn")
    assert(fn.getArgumentsCount == 0)
  }
}
