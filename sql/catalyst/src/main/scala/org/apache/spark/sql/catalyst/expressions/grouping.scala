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

package org.apache.spark.sql.catalyst.expressions

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenFallback
import org.apache.spark.sql.catalyst.trees.UnaryLike
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._

/**
 * A placeholder expression for cube/rollup, which will be replaced by analyzer
 */
trait BaseGroupingSets extends Expression with CodegenFallback {

  def groupingSets: Seq[Seq[Expression]]
  def selectedGroupByExprs: Seq[Seq[Expression]]

  def groupByExprs: Seq[Expression] = {
    assert(children.forall(_.resolved),
      "Cannot call BaseGroupingSets.groupByExprs before the children expressions are all resolved.")
    children.foldLeft(Seq.empty[Expression]) { (result, currentExpr) =>
      // Only unique expressions are included in the group by expressions and is determined
      // based on their semantic equality. Example. grouping sets ((a * b), (b * a)) results
      // in grouping expression (a * b)
      if (result.exists(_.semanticEquals(currentExpr))) {
        result
      } else {
        result :+ currentExpr
      }
    }
  }

  // this should be replaced first
  override lazy val resolved: Boolean = false

  override def dataType: DataType = throw new UnsupportedOperationException
  override def foldable: Boolean = false
  override def nullable: Boolean = true
  override def eval(input: InternalRow): Any = throw new UnsupportedOperationException
}

object BaseGroupingSets {
  /**
   * 'GROUP BY a, b, c WITH ROLLUP'
   * is equivalent to
   * 'GROUP BY GROUPING SETS ( (a, b, c), (a, b), (a), ( ) )'.
   * Group Count: N + 1 (N is the number of group expressions)
   *
   * We need to get all of its subsets for the rule described above, the subset is
   * represented as sequence of expressions.
   */
  def rollupExprs(exprs: Seq[Seq[Expression]]): Seq[Seq[Expression]] =
    exprs.inits.map(_.flatten).toIndexedSeq

  /**
   * 'GROUP BY a, b, c WITH CUBE'
   * is equivalent to
   * 'GROUP BY GROUPING SETS ( (a, b, c), (a, b), (b, c), (a, c), (a), (b), (c), ( ) )'.
   * Group Count: 2 ^ N (N is the number of group expressions)
   *
   * We need to get all of its subsets for a given GROUPBY expression, the subsets are
   * represented as sequence of expressions.
   */
  def cubeExprs(exprs: Seq[Seq[Expression]]): Seq[Seq[Expression]] = {
    // `cubeExprs0` is recursive and returns a lazy Stream. Here we call `toIndexedSeq` to
    // materialize it and avoid serialization problems later on.
    cubeExprs0(exprs).toIndexedSeq
  }

  def cubeExprs0(exprs: Seq[Seq[Expression]]): Seq[Seq[Expression]] = exprs.toList match {
    case x :: xs =>
      val initial = cubeExprs0(xs)
      initial.map(x ++ _) ++ initial
    case Nil =>
      Seq(Seq.empty)
  }

  /**
   * This methods converts given grouping sets into the indexes of the flatten grouping sets.
   * Let's say we have a query below:
   *   SELECT k1, k2, avg(v) FROM t GROUP BY GROUPING SETS ((k1), (k1, k2), (k2, k1));
   * In this case, flatten grouping sets are "[k1, k1, k2, k2, k1]" and the method
   * will return indexes "[[1], [2, 3], [4, 5]]".
   */
  def computeGroupingSetIndexes(groupingSets: Seq[Seq[Expression]]): Seq[Seq[Int]] = {
    val startOffsets = groupingSets.map(_.length).scanLeft(0)(_ + _).init
    groupingSets.zip(startOffsets).map {
      case (gs, startOffset) => gs.indices.map(_ + startOffset)
    }
  }
}

case class Cube(
    groupingSetIndexes: Seq[Seq[Int]],
    children: Seq[Expression]) extends BaseGroupingSets {
  override def groupingSets: Seq[Seq[Expression]] = groupingSetIndexes.map(_.map(children))
  override def selectedGroupByExprs: Seq[Seq[Expression]] = BaseGroupingSets.cubeExprs(groupingSets)
}

object Cube {
  def apply(groupingSets: Seq[Seq[Expression]]): Cube = {
    Cube(BaseGroupingSets.computeGroupingSetIndexes(groupingSets), groupingSets.flatten)
  }
}

case class Rollup(
    groupingSetIndexes: Seq[Seq[Int]],
    children: Seq[Expression]) extends BaseGroupingSets {
  override def groupingSets: Seq[Seq[Expression]] = groupingSetIndexes.map(_.map(children))
  override def selectedGroupByExprs: Seq[Seq[Expression]] =
    BaseGroupingSets.rollupExprs(groupingSets)
}

object Rollup {
  def apply(groupingSets: Seq[Seq[Expression]]): Rollup = {
    Rollup(BaseGroupingSets.computeGroupingSetIndexes(groupingSets), groupingSets.flatten)
  }
}

case class GroupingSets(
    groupingSetIndexes: Seq[Seq[Int]],
    flatGroupingSets: Seq[Expression],
    userGivenGroupByExprs: Seq[Expression]) extends BaseGroupingSets {
  override def groupingSets: Seq[Seq[Expression]] = groupingSetIndexes.map(_.map(flatGroupingSets))
  override def selectedGroupByExprs: Seq[Seq[Expression]] = groupingSets
  // Includes the `userGivenGroupByExprs` in the children, which will be included in the final
  // GROUP BY expressions, so that `SELECT c ... GROUP BY (a, b, c) GROUPING SETS (a, b)` works.
  override def children: Seq[Expression] = flatGroupingSets ++ userGivenGroupByExprs
}

object GroupingSets {
  def apply(
      groupingSets: Seq[Seq[Expression]],
      userGivenGroupByExprs: Seq[Expression]): GroupingSets = {
    val groupingSetIndexes = BaseGroupingSets.computeGroupingSetIndexes(groupingSets)
    GroupingSets(groupingSetIndexes, groupingSets.flatten, userGivenGroupByExprs)
  }

  def apply(groupingSets: Seq[Seq[Expression]]): GroupingSets = {
    apply(groupingSets, userGivenGroupByExprs = Nil)
  }
}

/**
 * Indicates whether a specified column expression in a GROUP BY list is aggregated or not.
 * GROUPING returns 1 for aggregated or 0 for not aggregated in the result set.
 */
// scalastyle:off line.size.limit line.contains.tab
@ExpressionDescription(
  usage = """
    _FUNC_(col) - indicates whether a specified column in a GROUP BY is aggregated or
      not, returns 1 for aggregated or 0 for not aggregated in the result set.",
  """,
  examples = """
    Examples:
      > SELECT name, _FUNC_(name), sum(age) FROM VALUES (2, 'Alice'), (5, 'Bob') people(age, name) GROUP BY cube(name);
        Alice	0	2
        Bob	0	5
        NULL	1	7
  """,
  since = "2.0.0",
  group = "agg_funcs")
// scalastyle:on line.size.limit line.contains.tab
case class Grouping(child: Expression) extends Expression with Unevaluable
  with UnaryLike[Expression] {
  @transient
  override lazy val references: AttributeSet =
    AttributeSet(VirtualColumn.groupingIdAttribute :: Nil)
  override def dataType: DataType = ByteType
  override def nullable: Boolean = false
}

/**
 * GroupingID is a function that computes the level of grouping.
 *
 * If groupByExprs is empty, it means all grouping expressions in GroupingSets.
 */
// scalastyle:off line.size.limit line.contains.tab
@ExpressionDescription(
  usage = """
    _FUNC_([col1[, col2 ..]]) - returns the level of grouping, equals to
      `(grouping(c1) << (n-1)) + (grouping(c2) << (n-2)) + ... + grouping(cn)`
  """,
  examples = """
    Examples:
      > SELECT name, _FUNC_(), sum(age), avg(height) FROM VALUES (2, 'Alice', 165), (5, 'Bob', 180) people(age, name, height) GROUP BY cube(name, height);
        Alice	0	2	165.0
        Alice	1	2	165.0
        NULL	3	7	172.5
        Bob	0	5	180.0
        Bob	1	5	180.0
        NULL	2	2	165.0
        NULL	2	5	180.0
  """,
  note = """
    Input columns should match with grouping columns exactly, or empty (means all the grouping
    columns).
  """,
  since = "2.0.0",
  group = "agg_funcs")
// scalastyle:on line.size.limit line.contains.tab
case class GroupingID(groupByExprs: Seq[Expression]) extends Expression with Unevaluable {
  @transient
  override lazy val references: AttributeSet =
    AttributeSet(VirtualColumn.groupingIdAttribute :: Nil)
  override def children: Seq[Expression] = groupByExprs
  override def dataType: DataType = GroupingID.dataType
  override def nullable: Boolean = false
  override def prettyName: String = "grouping_id"
}

object GroupingID {

  def dataType: DataType = {
    if (SQLConf.get.integerGroupingIdEnabled) IntegerType else LongType
  }
}
