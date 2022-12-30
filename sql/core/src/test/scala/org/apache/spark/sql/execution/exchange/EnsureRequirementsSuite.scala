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

package org.apache.spark.sql.execution.exchange

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.Inner
import org.apache.spark.sql.catalyst.plans.physical._
import org.apache.spark.sql.connector.catalog.functions._
import org.apache.spark.sql.execution.{DummySparkPlan, SortExec}
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.joins.SortMergeJoinExec
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf.REQUIRE_ALL_CLUSTER_KEYS_FOR_CO_PARTITION
import org.apache.spark.sql.test.SharedSparkSession

class EnsureRequirementsSuite extends SharedSparkSession {
  private val exprA = Literal(1)
  private val exprB = Literal(2)
  private val exprC = Literal(3)
  private val exprD = Literal(4)

  private val EnsureRequirements = new EnsureRequirements()

  test("reorder should handle PartitioningCollection") {
    val plan1 = DummySparkPlan(
      outputPartitioning = PartitioningCollection(Seq(
        HashPartitioning(exprA :: exprB :: Nil, 5),
        HashPartitioning(exprA :: Nil, 5))))
    val plan2 = DummySparkPlan()

    // Test PartitioningCollection on the left side of join.
    val smjExec1 = SortMergeJoinExec(
      exprB :: exprA :: Nil, exprA :: exprB :: Nil, Inner, None, plan1, plan2)
    EnsureRequirements.apply(smjExec1) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
        SortExec(_, _, DummySparkPlan(_, _, _: PartitioningCollection, _, _), _),
        SortExec(_, _, ShuffleExchangeExec(_: HashPartitioning, _, _), _), _) =>
        assert(leftKeys === Seq(exprA, exprB))
        assert(rightKeys === Seq(exprB, exprA))
      case other => fail(other.toString)
    }

    // Test PartitioningCollection on the right side of join.
    val smjExec2 = SortMergeJoinExec(
      exprA :: exprB :: Nil, exprB :: exprA :: Nil, Inner, None, plan2, plan1)
    EnsureRequirements.apply(smjExec2) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
        SortExec(_, _, ShuffleExchangeExec(_: HashPartitioning, _, _), _),
        SortExec(_, _, DummySparkPlan(_, _, _: PartitioningCollection, _, _), _), _) =>
        assert(leftKeys === Seq(exprB, exprA))
        assert(rightKeys === Seq(exprA, exprB))
      case other => fail(other.toString)
    }

    // Both sides are PartitioningCollection, but left side cannot be reordered to match
    // and it should fall back to the right side.
    val smjExec3 = SortMergeJoinExec(
      exprD :: exprC :: Nil, exprB :: exprA :: Nil, Inner, None, plan1, plan1)
    EnsureRequirements.apply(smjExec3) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
        SortExec(_, _, ShuffleExchangeExec(_: HashPartitioning, _, _), _),
        SortExec(_, _, DummySparkPlan(_, _, _: PartitioningCollection, _, _), _), _) =>
        assert(leftKeys === Seq(exprC, exprD))
        assert(rightKeys === Seq(exprA, exprB))
      case other => fail(other.toString)
    }
  }

  test("reorder should handle KeyGroupedPartitioning") {
    // partitioning on the left
    val plan1 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(Seq(
        years(exprA), bucket(4, exprB), days(exprC)), 4)
    )
    val plan2 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(Seq(
        years(exprB), bucket(4, exprA), days(exprD)), 4)
    )
    val smjExec = SortMergeJoinExec(
      exprB :: exprC :: exprA :: Nil, exprA :: exprD :: exprB :: Nil,
      Inner, None, plan1, plan2
    )
    EnsureRequirements.apply(smjExec) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
      SortExec(_, _, DummySparkPlan(_, _, _: KeyGroupedPartitioning, _, _), _),
      SortExec(_, _, DummySparkPlan(_, _, _: KeyGroupedPartitioning, _, _), _), _) =>
        assert(leftKeys === Seq(exprA, exprB, exprC))
        assert(rightKeys === Seq(exprB, exprA, exprD))
      case other => fail(other.toString)
    }

    // partitioning on the right
    val plan3 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(Seq(
        bucket(4, exprD), days(exprA), years(exprC)), 4)
    )
    val smjExec2 = SortMergeJoinExec(
      exprB :: exprD :: exprC :: Nil, exprA :: exprC :: exprD :: Nil,
      Inner, None, plan1, plan3
    )
    EnsureRequirements.apply(smjExec2) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
      SortExec(_, _, ShuffleExchangeExec(_: HashPartitioning, _, _), _),
      SortExec(_, _, ShuffleExchangeExec(_: HashPartitioning, _, _), _), _) =>
        assert(leftKeys === Seq(exprC, exprB, exprD))
        assert(rightKeys === Seq(exprD, exprA, exprC))
      case other => fail(other.toString)
    }
  }

  test("reorder should fallback to the other side partitioning") {
    val plan1 = DummySparkPlan(
      outputPartitioning = HashPartitioning(exprA :: exprB :: exprC :: Nil, 5))
    val plan2 = DummySparkPlan(
      outputPartitioning = HashPartitioning(exprB :: exprC :: Nil, 5))

    // Test fallback to the right side, which has HashPartitioning.
    val smjExec1 = SortMergeJoinExec(
      exprA :: exprB :: Nil, exprC :: exprB :: Nil, Inner, None, plan1, plan2)
    EnsureRequirements.apply(smjExec1) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
        SortExec(_, _, ShuffleExchangeExec(_: HashPartitioning, _, _), _),
        SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _), _) =>
        assert(leftKeys === Seq(exprB, exprA))
        assert(rightKeys === Seq(exprB, exprC))
      case other => fail(other.toString)
    }

    // Test fallback to the right side, which has PartitioningCollection.
    val plan3 = DummySparkPlan(
      outputPartitioning = PartitioningCollection(Seq(HashPartitioning(exprB :: exprC :: Nil, 5))))
    val smjExec2 = SortMergeJoinExec(
      exprA :: exprB :: Nil, exprC :: exprB :: Nil, Inner, None, plan1, plan3)
    EnsureRequirements.apply(smjExec2) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
        SortExec(_, _, ShuffleExchangeExec(_: HashPartitioning, _, _), _),
        SortExec(_, _, DummySparkPlan(_, _, _: PartitioningCollection, _, _), _), _) =>
        assert(leftKeys === Seq(exprB, exprA))
        assert(rightKeys === Seq(exprB, exprC))
      case other => fail(other.toString)
    }

    // The right side has HashPartitioning, so it is matched first, but no reordering match is
    // found, and it should fall back to the left side, which has a PartitioningCollection.
    val smjExec3 = SortMergeJoinExec(
      exprC :: exprB :: Nil, exprA :: exprB :: Nil, Inner, None, plan3, plan1)
    EnsureRequirements.apply(smjExec3) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
        SortExec(_, _, DummySparkPlan(_, _, _: PartitioningCollection, _, _), _),
        SortExec(_, _, ShuffleExchangeExec(_: HashPartitioning, _, _), _), _) =>
        assert(leftKeys === Seq(exprB, exprC))
        assert(rightKeys === Seq(exprB, exprA))
      case other => fail(other.toString)
    }
  }

  test("SPARK-35675: EnsureRequirements remove shuffle should respect PartitioningCollection") {
    import testImplicits._
    withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false") {
      val df1 = Seq((1, 2)).toDF("c1", "c2")
      val df2 = Seq((1, 3)).toDF("c3", "c4")
      val res = df1.join(df2, $"c1" === $"c3").repartition($"c1")
      assert(res.queryExecution.executedPlan.collect {
        case s: ShuffleExchangeLike => s
      }.size == 2)
    }
  }

  private def applyEnsureRequirementsWithSubsetKeys(plan: SparkPlan): SparkPlan = {
    var res: SparkPlan = null
    withSQLConf(SQLConf.REQUIRE_ALL_CLUSTER_KEYS_FOR_CO_PARTITION.key -> "false") {
      res = EnsureRequirements.apply(plan)
    }
    res
  }

  test("Successful compatibility check with HashShuffleSpec") {
    val plan1 = DummySparkPlan(
      outputPartitioning = HashPartitioning(exprA :: Nil, 5))
    val plan2 = DummySparkPlan(
      outputPartitioning = HashPartitioning(exprB :: Nil, 5))

    var smjExec = SortMergeJoinExec(
      exprA :: Nil, exprB :: Nil, Inner, None, plan1, plan2)
    EnsureRequirements.apply(smjExec) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
      SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _),
      SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _), _) =>
        assert(leftKeys === Seq(exprA))
        assert(rightKeys === Seq(exprB))
      case other => fail(other.toString)
    }

    smjExec = SortMergeJoinExec(
      exprA :: exprB :: Nil, exprB :: exprC :: Nil, Inner, None, plan1, plan2)
    // By default we can't eliminate shuffles if the partitions keys are subset of join keys.
    assert(EnsureRequirements.apply(smjExec)
      .collect { case s: ShuffleExchangeLike => s }.length == 2)
    // with the config set, it should also work if both partition keys are subset of their
    // corresponding cluster keys
    applyEnsureRequirementsWithSubsetKeys(smjExec) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
        SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _),
        SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _), _) =>
        assert(leftKeys === Seq(exprA, exprB))
        assert(rightKeys === Seq(exprB, exprC))
      case other => fail(other.toString)
    }

    smjExec = SortMergeJoinExec(
      exprB :: exprA :: Nil, exprC :: exprB :: Nil, Inner, None, plan1, plan2)
    applyEnsureRequirementsWithSubsetKeys(smjExec) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
        SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _),
        SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _), _) =>
        assert(leftKeys === Seq(exprB, exprA))
        assert(rightKeys === Seq(exprC, exprB))
      case other => fail(other.toString)
    }
  }

  test("Successful compatibility check with HashShuffleSpec and duplicate keys") {
    var plan1 = DummySparkPlan(
      outputPartitioning = HashPartitioning(exprA :: exprB :: Nil, 5))
    var plan2 = DummySparkPlan(
      outputPartitioning = HashPartitioning(exprA :: exprC :: Nil, 5))
    var smjExec = SortMergeJoinExec(
      exprA :: exprB :: exprB :: Nil, exprA :: exprC :: exprC :: Nil, Inner, None, plan1, plan2)
    applyEnsureRequirementsWithSubsetKeys(smjExec) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
      SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _),
      SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _), _) =>
        assert(leftKeys === Seq(exprA, exprB, exprB))
        assert(rightKeys === Seq(exprA, exprC, exprC))
      case other => fail(other.toString)
    }

    plan1 = DummySparkPlan(
      outputPartitioning = HashPartitioning(exprA :: exprB :: exprA :: Nil, 5))
    plan2 = DummySparkPlan(
      outputPartitioning = HashPartitioning(exprA :: exprC :: exprA :: Nil, 5))
    smjExec = SortMergeJoinExec(
      exprA :: exprB :: exprB :: Nil, exprA :: exprC :: exprC :: Nil, Inner, None, plan1, plan2)
    applyEnsureRequirementsWithSubsetKeys(smjExec) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
      SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _),
      SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _), _) =>
        assert(leftKeys === Seq(exprA, exprB, exprB))
        assert(rightKeys === Seq(exprA, exprC, exprC))
      case other => fail(other.toString)
    }

    plan1 = DummySparkPlan(
      outputPartitioning = HashPartitioning(exprA :: exprB :: exprA :: Nil, 5))
    plan2 = DummySparkPlan(
      outputPartitioning = HashPartitioning(exprA :: exprC :: exprA :: Nil, 5))
    smjExec = SortMergeJoinExec(
      exprA :: exprB :: exprB :: Nil, exprA :: exprC :: exprD :: Nil, Inner, None, plan1, plan2)
    applyEnsureRequirementsWithSubsetKeys(smjExec) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
      SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _),
      SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _), _) =>
        assert(leftKeys === Seq(exprA, exprB, exprB))
        assert(rightKeys === Seq(exprA, exprC, exprD))
      case other => fail(other.toString)
    }

    plan1 = DummySparkPlan(
      outputPartitioning = HashPartitioning(exprA :: exprB :: Nil, 5))
    plan2 = DummySparkPlan(
      outputPartitioning = HashPartitioning(exprA :: exprC :: Nil, 5))
    smjExec = SortMergeJoinExec(
      exprA :: exprB :: exprB :: Nil, exprA :: exprC :: exprC :: Nil, Inner, None, plan1, plan2)
    applyEnsureRequirementsWithSubsetKeys(smjExec) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
      SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _),
      SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _), _) =>
        assert(leftKeys === Seq(exprA, exprB, exprB))
        assert(rightKeys === Seq(exprA, exprC, exprC))
      case other => fail(other.toString)
    }
  }

  test("incompatible & repartitioning with HashShuffleSpec") {
    withSQLConf(SQLConf.SHUFFLE_PARTITIONS.key -> 5.toString) {
      var plan1 = DummySparkPlan(
        outputPartitioning = HashPartitioning(exprA :: Nil, 10))
      var plan2 = DummySparkPlan(
        outputPartitioning = HashPartitioning(exprD :: Nil, 5))
      var smjExec = SortMergeJoinExec(
        exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
      applyEnsureRequirementsWithSubsetKeys(smjExec) match {
        case SortMergeJoinExec(leftKeys, rightKeys, _, _,
        SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _),
        SortExec(_, _, ShuffleExchangeExec(p: HashPartitioning, _, _), _), _) =>
          assert(leftKeys === Seq(exprA, exprB))
          assert(rightKeys === Seq(exprC, exprD))
          assert(p.expressions == Seq(exprC))
        case other => fail(other.toString)
      }

      // RHS has more partitions so should be chosen
      plan1 = DummySparkPlan(
        outputPartitioning = HashPartitioning(exprA :: Nil, 5))
      plan2 = DummySparkPlan(
        outputPartitioning = HashPartitioning(exprD :: Nil, 10))
      smjExec = SortMergeJoinExec(
        exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
      applyEnsureRequirementsWithSubsetKeys(smjExec) match {
        case SortMergeJoinExec(leftKeys, rightKeys, _, _,
        SortExec(_, _, ShuffleExchangeExec(p: HashPartitioning, _, _), _),
        SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _), _) =>
          assert(leftKeys === Seq(exprA, exprB))
          assert(rightKeys === Seq(exprC, exprD))
          assert(p.expressions == Seq(exprB))
        case other => fail(other.toString)
      }

      // If both sides have the same # of partitions, should pick the first one from left
      plan1 = DummySparkPlan(
        outputPartitioning = HashPartitioning(exprA :: Nil, 5))
      plan2 = DummySparkPlan(
        outputPartitioning = HashPartitioning(exprD :: Nil, 5))
      smjExec = SortMergeJoinExec(
        exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
      applyEnsureRequirementsWithSubsetKeys(smjExec) match {
        case SortMergeJoinExec(leftKeys, rightKeys, _, _,
        SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _),
        SortExec(_, _, ShuffleExchangeExec(p: HashPartitioning, _, _), _), _) =>
          assert(leftKeys === Seq(exprA, exprB))
          assert(rightKeys === Seq(exprC, exprD))
          assert(p.expressions == Seq(exprC))
        case other => fail(other.toString)
      }
    }
  }

  test("Incompatible & repartitioning with HashShuffleSpec and duplicate keys") {
    var plan1 = DummySparkPlan(
      outputPartitioning = HashPartitioning(exprA :: exprB :: exprA :: Nil, 10))
    var plan2 = DummySparkPlan(
      outputPartitioning = HashPartitioning(exprA :: exprC :: exprB :: Nil, 5))
    var smjExec = SortMergeJoinExec(
      exprA :: exprB :: exprB :: Nil, exprA :: exprC :: exprC :: Nil, Inner, None, plan1, plan2)
    applyEnsureRequirementsWithSubsetKeys(smjExec) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
      SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _),
      SortExec(_, _, ShuffleExchangeExec(p: HashPartitioning, _, _), _), _) =>
        assert(leftKeys === Seq(exprA, exprB, exprB))
        assert(rightKeys === Seq(exprA, exprC, exprC))
        assert(p.expressions == Seq(exprA, exprC, exprA))
      case other => fail(other.toString)
    }

    plan1 = DummySparkPlan(
      outputPartitioning = HashPartitioning(exprA :: exprB :: exprA :: Nil, 10))
    plan2 = DummySparkPlan(
      outputPartitioning = HashPartitioning(exprA :: exprC :: exprB :: Nil, 5))
    smjExec = SortMergeJoinExec(
      exprA :: exprB :: exprB :: Nil, exprA :: exprC :: exprD :: Nil, Inner, None, plan1, plan2)
    applyEnsureRequirementsWithSubsetKeys(smjExec) match {
      case SortMergeJoinExec(leftKeys, rightKeys, _, _,
      SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _),
      SortExec(_, _, ShuffleExchangeExec(p: HashPartitioning, _, _), _), _) =>
        assert(leftKeys === Seq(exprA, exprB, exprB))
        assert(rightKeys === Seq(exprA, exprC, exprD))
        assert(p.expressions == Seq(exprA, exprC, exprA))
      case other => fail(other.toString)
    }
  }

  test("Successful compatibility check with other specs") {
    var plan1 = DummySparkPlan(outputPartitioning = SinglePartition)
    var plan2 = DummySparkPlan(outputPartitioning = SinglePartition)
    var smjExec = SortMergeJoinExec(
      exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
    EnsureRequirements.apply(smjExec) match {
      case SortMergeJoinExec(_, _, _, _,
      SortExec(_, _, DummySparkPlan(_, _, SinglePartition, _, _), _),
      SortExec(_, _, DummySparkPlan(_, _, SinglePartition, _, _), _), _) =>
      case other => fail(other.toString)
    }

    plan1 = DummySparkPlan(outputPartitioning = SinglePartition)
    plan2 = DummySparkPlan(outputPartitioning = HashPartitioning(exprC :: exprD :: Nil, 1))
    smjExec = SortMergeJoinExec(
      exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
    EnsureRequirements.apply(smjExec) match {
      case SortMergeJoinExec(_, _, _, _,
      SortExec(_, _, DummySparkPlan(_, _, SinglePartition, _, _), _),
      SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _), _) =>
      case other => fail(other.toString)
    }

    plan1 = DummySparkPlan(outputPartitioning = PartitioningCollection(Seq(
        HashPartitioning(Seq(exprA), 10), HashPartitioning(Seq(exprA, exprB), 10))))
    plan2 = DummySparkPlan(outputPartitioning = HashPartitioning(Seq(exprC, exprD), 10))
    smjExec = SortMergeJoinExec(
      exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
    EnsureRequirements.apply(smjExec) match {
      case SortMergeJoinExec(_, _, _, _,
      SortExec(_, _, DummySparkPlan(_, _, _: PartitioningCollection, _, _), _),
      SortExec(_, _, DummySparkPlan(_, _, _: HashPartitioning, _, _), _), _) =>
      case other => fail(other.toString)
    }
  }

  test("Incompatible & repartitioning with other specs") {
    withSQLConf(SQLConf.SHUFFLE_PARTITIONS.key -> 5.toString) {

      // SinglePartition <-> RangePartitioning(10)
      // Only RHS should be shuffled and be converted to SinglePartition <-> SinglePartition
      var plan1 = DummySparkPlan(outputPartitioning = SinglePartition)
      var plan2 = DummySparkPlan(outputPartitioning = RangePartitioning(
        Seq(SortOrder.apply(exprC, Ascending, sameOrderExpressions = Seq.empty)), 10))
      var smjExec = SortMergeJoinExec(
        exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
      EnsureRequirements.apply(smjExec) match {
        case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, DummySparkPlan(_, _, SinglePartition, _, _), _),
        SortExec(_, _, ShuffleExchangeExec(SinglePartition, _, _), _), _) =>
        case other => fail(other.toString)
      }

      // HashPartitioning(10) <-> RangePartitioning(5)
      // Only RHS should be shuffled and be converted to
      //   HashPartitioning(10) <-> HashPartitioning(10)
      plan1 = DummySparkPlan(outputPartitioning = HashPartitioning(Seq(exprA, exprB), 10))
      plan2 = DummySparkPlan(outputPartitioning = RangePartitioning(
        Seq(SortOrder.apply(exprC, Ascending, sameOrderExpressions = Seq.empty)), 5))
      smjExec = SortMergeJoinExec(
        exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
      EnsureRequirements.apply(smjExec) match {
        case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, DummySparkPlan(_, _, left: HashPartitioning, _, _), _),
        SortExec(_, _, ShuffleExchangeExec(right: HashPartitioning, _, _), _), _) =>
          assert(left.numPartitions == 10)
          assert(right.numPartitions == 10)
          assert(right.expressions == Seq(exprC, exprD))
        case other => fail(other.toString)
      }

      // HashPartitioning(1) <-> RangePartitioning(10)
      // If the conf is not set, both sides should be shuffled and be converted to
      // HashPartitioning(5) <-> HashPartitioning(5)
      // If the conf is set, only RHS should be shuffled and be converted to
      // HashPartitioning(1) <-> HashPartitioning(1)
      plan1 = DummySparkPlan(outputPartitioning = HashPartitioning(Seq(exprA), 1))
      plan2 = DummySparkPlan(outputPartitioning = RangePartitioning(
        Seq(SortOrder.apply(exprC, Ascending, sameOrderExpressions = Seq.empty)), 10))
      smjExec = SortMergeJoinExec(
        exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
      EnsureRequirements.apply(smjExec) match {
        case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, ShuffleExchangeExec(left: HashPartitioning, _, _), _),
        SortExec(_, _, ShuffleExchangeExec(right: HashPartitioning, _, _), _), _) =>
          assert(left.numPartitions == 5)
          assert(left.expressions == Seq(exprA, exprB))
          assert(right.numPartitions == 5)
          assert(right.expressions == Seq(exprC, exprD))
        case other => fail(other.toString)
      }
      applyEnsureRequirementsWithSubsetKeys(smjExec) match {
        case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, DummySparkPlan(_, _, left: HashPartitioning, _, _), _),
        SortExec(_, _, ShuffleExchangeExec(right: HashPartitioning, _, _), _), _) =>
          assert(left.numPartitions == 1)
          assert(right.numPartitions == 1)
          assert(right.expressions == Seq(exprC))
        case other => fail(other.toString)
      }

      // RangePartitioning(1) <-> RangePartitioning(1)
      // Both sides should be shuffled and be converted to
      //   HashPartitioning(5) <-> HashPartitioning(5)
      plan1 = DummySparkPlan(outputPartitioning = RangePartitioning(
        Seq(SortOrder.apply(exprA, Ascending, sameOrderExpressions = Seq.empty)), 1))
      plan2 = DummySparkPlan(outputPartitioning = RangePartitioning(
        Seq(SortOrder.apply(exprD, Ascending, sameOrderExpressions = Seq.empty)), 1))
      smjExec = SortMergeJoinExec(
        exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
      EnsureRequirements.apply(smjExec) match {
        case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, ShuffleExchangeExec(left: HashPartitioning, _, _), _),
        SortExec(_, _, ShuffleExchangeExec(right: HashPartitioning, _, _), _), _) =>
          assert(left.numPartitions == conf.numShufflePartitions)
          assert(left.expressions == Seq(exprA, exprB))
          assert(right.numPartitions == conf.numShufflePartitions)
          assert(right.expressions == Seq(exprC, exprD))
        case other => fail(other.toString)
      }

      plan1 = DummySparkPlan(outputPartitioning = PartitioningCollection(Seq(
        HashPartitioning(Seq(exprA), 10), HashPartitioning(Seq(exprB), 10))))
      plan2 = DummySparkPlan(outputPartitioning = PartitioningCollection(Seq(
        HashPartitioning(Seq(exprC), 10), HashPartitioning(Seq(exprD), 10))))
      smjExec = SortMergeJoinExec(
        exprA :: exprB :: exprC :: exprD :: Nil, exprA :: exprB :: exprC :: exprD :: Nil,
        Inner, None, plan1, plan2)
      applyEnsureRequirementsWithSubsetKeys(smjExec) match {
        case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, DummySparkPlan(_, _, left: PartitioningCollection, _, _), _),
        SortExec(_, _, ShuffleExchangeExec(right: HashPartitioning, _, _), _), _) =>
          assert(left.numPartitions == 10)
          assert(right.numPartitions == 10)
          assert(right.expressions == Seq(exprA))
        case other => fail(other.toString)
      }

      plan1 = DummySparkPlan(outputPartitioning = PartitioningCollection(Seq(
        HashPartitioning(Seq(exprA), 10), HashPartitioning(Seq(exprB), 10))))
      plan2 = DummySparkPlan(outputPartitioning = PartitioningCollection(Seq(
        HashPartitioning(Seq(exprC), 20), HashPartitioning(Seq(exprD), 20))))
      smjExec = SortMergeJoinExec(
        exprA :: exprB :: exprC :: exprD :: Nil, exprA :: exprB :: exprC :: exprD :: Nil,
        Inner, None, plan1, plan2)
      applyEnsureRequirementsWithSubsetKeys(smjExec) match {
        case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, ShuffleExchangeExec(left: HashPartitioning, _, _), _),
        SortExec(_, _, DummySparkPlan(_, _, right: PartitioningCollection, _, _), _), _) =>
          assert(left.numPartitions == 20)
          assert(left.expressions == Seq(exprC))
          assert(right.numPartitions == 20)
        case other => fail(other.toString)
      }
    }
  }

  test("EnsureRequirements should respect spark.sql.shuffle.partitions") {
    val defaultNumPartitions = 10
    withSQLConf(SQLConf.SHUFFLE_PARTITIONS.key -> defaultNumPartitions.toString) {

      // HashPartitioning(5) <-> HashPartitioning(5)
      // No shuffle should be inserted
      var plan1: SparkPlan = DummySparkPlan(
        outputPartitioning = HashPartitioning(exprA :: exprB :: Nil, 5))
      var plan2: SparkPlan = DummySparkPlan(
        outputPartitioning = HashPartitioning(exprC :: exprD :: Nil, 5))
      var smjExec = SortMergeJoinExec(
        exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
      EnsureRequirements.apply(smjExec) match {
        case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, DummySparkPlan(_, _, left: HashPartitioning, _, _), _),
        SortExec(_, _, DummySparkPlan(_, _, right: HashPartitioning, _, _), _), _) =>
          assert(left.expressions === Seq(exprA, exprB))
          assert(right.expressions === Seq(exprC, exprD))
        case other => fail(other.toString)
      }

      // HashPartitioning(6) <-> HashPartitioning(5)
      // Should shuffle RHS and convert to HashPartitioning(6) <-> HashPartitioning(6)
      plan1 = DummySparkPlan(
        outputPartitioning = HashPartitioning(exprA :: exprB :: Nil, 6))
      plan2 = DummySparkPlan(
        outputPartitioning = HashPartitioning(exprC :: exprD :: Nil, 5))
      smjExec = SortMergeJoinExec(
        exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
      EnsureRequirements.apply(smjExec) match {
        case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, DummySparkPlan(_, _, left: HashPartitioning, _, _), _),
        SortExec(_, _, ShuffleExchangeExec(right: HashPartitioning, _, _), _), _) =>
          assert(left.expressions === Seq(exprA, exprB))
          assert(right.expressions === Seq(exprC, exprD))
          assert(left.numPartitions == 6)
          assert(right.numPartitions == 6)
        case other => fail(other.toString)
      }

      // RangePartitioning(10) <-> HashPartitioning(5)
      // Should shuffle LHS and convert to HashPartitioning(5) <-> HashPartitioning(5)
      plan1 = DummySparkPlan(
        outputPartitioning = RangePartitioning(
          Seq(SortOrder.apply(exprA, Ascending, sameOrderExpressions = Seq.empty)), 10))
      plan2 = DummySparkPlan(
        outputPartitioning = HashPartitioning(exprC :: exprD :: Nil, 5))
      smjExec = SortMergeJoinExec(
        exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
      EnsureRequirements.apply(smjExec) match {
        case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, ShuffleExchangeExec(left: HashPartitioning, _, _), _),
        SortExec(_, _, DummySparkPlan(_, _, right: HashPartitioning, _, _), _), _) =>
          assert(left.expressions === Seq(exprA, exprB))
          assert(right.expressions === Seq(exprC, exprD))
          assert(left.numPartitions == 5)
          assert(right.numPartitions == 5)
        case other => fail(other.toString)
      }

      // SinglePartition <-> HashPartitioning(5)
      // Should shuffle LHS and convert to HashPartitioning(5) <-> HashPartitioning(5)
      plan1 = DummySparkPlan(outputPartitioning = SinglePartition)
      plan2 = DummySparkPlan(
        outputPartitioning = HashPartitioning(exprC :: exprD :: Nil, 5))
      smjExec = SortMergeJoinExec(
        exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
      EnsureRequirements.apply(smjExec) match {
        case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, ShuffleExchangeExec(left: HashPartitioning, _, _), _),
        SortExec(_, _, DummySparkPlan(_, _, right: HashPartitioning, _, _), _), _) =>
          assert(left.expressions === Seq(exprA, exprB))
          assert(right.expressions === Seq(exprC, exprD))
          assert(left.numPartitions == 5)
          assert(right.numPartitions == 5)
        case other => fail(other.toString)
      }

      // ShuffleExchange(7) <-> HashPartitioning(6)
      // Should shuffle LHS and convert to HashPartitioning(6) <-> HashPartitioning(6)
      plan1 = ShuffleExchangeExec(
        outputPartitioning = HashPartitioning(exprA :: exprB :: Nil, 7),
        child = DummySparkPlan())
      plan2 = DummySparkPlan(
        outputPartitioning = HashPartitioning(exprC :: exprD :: Nil, 6))
      smjExec = SortMergeJoinExec(
        exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
      EnsureRequirements.apply(smjExec) match {
        case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, ShuffleExchangeExec(left: HashPartitioning, _, _), _),
        SortExec(_, _, DummySparkPlan(_, _, right: HashPartitioning, _, _), _), _) =>
          assert(left.expressions === Seq(exprA, exprB))
          assert(right.expressions === Seq(exprC, exprD))
          assert(left.numPartitions == 6)
          assert(right.numPartitions == 6)
        case other => fail(other.toString)
      }

      // ShuffleExchange(7) <-> ShuffleExchange(6)
      // Should consider `spark.sql.shuffle.partitions` and shuffle both sides, and
      // convert to HashPartitioning(10) <-> HashPartitioning(10)
      plan1 = ShuffleExchangeExec(
        outputPartitioning = HashPartitioning(exprA :: Nil, 7),
        child = DummySparkPlan())
      plan2 = ShuffleExchangeExec(
        outputPartitioning = HashPartitioning(exprC :: Nil, 6),
        child = DummySparkPlan())
      smjExec = SortMergeJoinExec(
        exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
      EnsureRequirements.apply(smjExec) match {
        case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, ShuffleExchangeExec(left: HashPartitioning, _, _), _),
        SortExec(_, _, ShuffleExchangeExec(right: HashPartitioning, _, _), _), _) =>
          assert(left.expressions === Seq(exprA, exprB))
          assert(right.expressions === Seq(exprC, exprD))
          assert(left.numPartitions == conf.numShufflePartitions)
          assert(right.numPartitions == conf.numShufflePartitions)
        case other => fail(other.toString)
      }
    }
  }

  test("Respect spark.sql.shuffle.partitions with AQE") {
    withSQLConf(SQLConf.SHUFFLE_PARTITIONS.key -> 8.toString,
      SQLConf.COALESCE_PARTITIONS_INITIAL_PARTITION_NUM.key -> 10.toString) {
      Seq(true, false).foreach { enable =>
        withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> s"$enable") {
          val plan1 = DummySparkPlan(
            outputPartitioning = HashPartitioning(exprA :: exprB :: Nil, 9))
          val plan2 = DummySparkPlan(
            outputPartitioning = UnknownPartitioning(8))
          val smjExec = SortMergeJoinExec(
            exprA :: exprB :: Nil, exprC :: exprD :: Nil, Inner, None, plan1, plan2)
          EnsureRequirements.apply(smjExec) match {
            case SortMergeJoinExec(leftKeys, rightKeys, _, _,
            SortExec(_, _, DummySparkPlan(_, _, left: HashPartitioning, _, _), _),
            SortExec(_, _, ShuffleExchangeExec(right: HashPartitioning, _, _), _), _) =>
              assert(leftKeys === Seq(exprA, exprB))
              assert(rightKeys === Seq(exprC, exprD))
              assert(left.numPartitions == 9)
              assert(right.numPartitions == 9)
            case other => fail(other.toString)
          }
        }
      }
    }
  }

  test("Check with KeyGroupedPartitioning") {
    // simplest case: identity transforms
    var plan1 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(exprA :: exprB :: Nil, 5))
    var plan2 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(exprA :: exprC :: Nil, 5))
    var smjExec = SortMergeJoinExec(
      exprA :: exprB :: Nil, exprA :: exprC :: Nil, Inner, None, plan1, plan2)
    EnsureRequirements.apply(smjExec) match {
      case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, DummySparkPlan(_, _, left: KeyGroupedPartitioning, _, _), _),
        SortExec(_, _, DummySparkPlan(_, _, right: KeyGroupedPartitioning, _, _), _), _) =>
        assert(left.expressions === Seq(exprA, exprB))
        assert(right.expressions === Seq(exprA, exprC))
      case other => fail(other.toString)
    }

    // matching bucket transforms from both sides
    plan1 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        bucket(4, exprA) :: bucket(16, exprB) :: Nil, 4)
    )
    plan2 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        bucket(4, exprA) :: bucket(16, exprC) :: Nil, 4)
    )
    smjExec = SortMergeJoinExec(
      exprA :: exprB :: Nil, exprA :: exprC :: Nil, Inner, None, plan1, plan2)
    EnsureRequirements.apply(smjExec) match {
      case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, DummySparkPlan(_, _, left: KeyGroupedPartitioning, _, _), _),
        SortExec(_, _, DummySparkPlan(_, _, right: KeyGroupedPartitioning, _, _), _), _) =>
        assert(left.expressions === Seq(bucket(4, exprA), bucket(16, exprB)))
        assert(right.expressions === Seq(bucket(4, exprA), bucket(16, exprC)))
      case other => fail(other.toString)
    }

    // partition collections
    plan1 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        bucket(4, exprA) :: bucket(16, exprB) :: Nil, 4)
    )
    plan2 = DummySparkPlan(
      outputPartitioning = PartitioningCollection(Seq(
        KeyGroupedPartitioning(bucket(4, exprA) :: bucket(16, exprC) :: Nil, 4),
        HashPartitioning(exprA :: exprC :: Nil, 4))
      )
    )
    smjExec = SortMergeJoinExec(
      exprA :: exprB :: Nil, exprA :: exprC :: Nil, Inner, None, plan1, plan2)
    EnsureRequirements.apply(smjExec) match {
      case SortMergeJoinExec(_, _, _, _,
      SortExec(_, _, DummySparkPlan(_, _, left: KeyGroupedPartitioning, _, _), _),
      SortExec(_, _, DummySparkPlan(_, _, _: PartitioningCollection, _, _), _), _) =>
        assert(left.expressions === Seq(bucket(4, exprA), bucket(16, exprB)))
      case other => fail(other.toString)
    }
    smjExec = SortMergeJoinExec(
      exprA :: exprC :: Nil, exprA :: exprB :: Nil, Inner, None, plan2, plan1)
    EnsureRequirements.apply(smjExec) match {
      case SortMergeJoinExec(_, _, _, _,
      SortExec(_, _, DummySparkPlan(_, _, _: PartitioningCollection, _, _), _),
      SortExec(_, _, DummySparkPlan(_, _, right: KeyGroupedPartitioning, _, _), _), _) =>
        assert(right.expressions === Seq(bucket(4, exprA), bucket(16, exprB)))
      case other => fail(other.toString)
    }

    // bucket + years transforms from both sides
    plan1 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(bucket(4, exprA) :: years(exprB) :: Nil, 4)
    )
    plan2 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(bucket(4, exprA) :: years(exprC) :: Nil, 4)
    )
    smjExec = SortMergeJoinExec(
      exprA :: exprB :: Nil, exprA :: exprC :: Nil, Inner, None, plan1, plan2)
    EnsureRequirements.apply(smjExec) match {
      case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, DummySparkPlan(_, _, left: KeyGroupedPartitioning, _, _), _),
        SortExec(_, _, DummySparkPlan(_, _, right: KeyGroupedPartitioning, _, _), _), _) =>
        assert(left.expressions === Seq(bucket(4, exprA), years(exprB)))
        assert(right.expressions === Seq(bucket(4, exprA), years(exprC)))
      case other => fail(other.toString)
    }

    // by default spark.sql.requireAllClusterKeysForCoPartition is true, so when there isn't
    // exact match on all partition keys, Spark will fallback to shuffle.
    plan1 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        bucket(4, exprA) :: bucket(4, exprB) :: Nil, 4)
    )
    plan2 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        bucket(4, exprA) :: bucket(4, exprC) :: Nil, 4)
    )
    smjExec = SortMergeJoinExec(
      exprA :: exprB :: exprB :: Nil, exprA :: exprC :: exprC :: Nil, Inner, None, plan1, plan2)
    EnsureRequirements.apply(smjExec) match {
      case SortMergeJoinExec(_, _, _, _,
        SortExec(_, _, ShuffleExchangeExec(left: HashPartitioning, _, _), _),
        SortExec(_, _, ShuffleExchangeExec(right: HashPartitioning, _, _), _), _) =>
        assert(left.expressions === Seq(exprA, exprB, exprB))
        assert(right.expressions === Seq(exprA, exprC, exprC))
      case other => fail(other.toString)
    }
  }

  test(s"KeyGroupedPartitioning with ${REQUIRE_ALL_CLUSTER_KEYS_FOR_CO_PARTITION.key} = false") {
    var plan1 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        bucket(4, exprB) :: years(exprC) :: Nil, 4)
    )
    var plan2 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        bucket(4, exprC) :: years(exprB) :: Nil, 4)
    )

    // simple case
    var smjExec = SortMergeJoinExec(
      exprA :: exprB :: exprC :: Nil, exprA :: exprC :: exprB :: Nil, Inner, None, plan1, plan2)
    applyEnsureRequirementsWithSubsetKeys(smjExec) match {
      case SortMergeJoinExec(_, _, _, _,
      SortExec(_, _, DummySparkPlan(_, _, left: KeyGroupedPartitioning, _, _), _),
      SortExec(_, _, DummySparkPlan(_, _, right: KeyGroupedPartitioning, _, _), _), _) =>
        assert(left.expressions === Seq(bucket(4, exprB), years(exprC)))
        assert(right.expressions === Seq(bucket(4, exprC), years(exprB)))
      case other => fail(other.toString)
    }

    // should also work with distributions with duplicated keys
    plan1 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        bucket(4, exprA) :: years(exprB) :: Nil, 4)
    )
    plan2 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        bucket(4, exprA) :: years(exprC) :: Nil, 4)
    )
    smjExec = SortMergeJoinExec(
      exprA :: exprB :: exprB :: Nil, exprA :: exprC :: exprC :: Nil, Inner, None, plan1, plan2)
    applyEnsureRequirementsWithSubsetKeys(smjExec) match {
      case SortMergeJoinExec(_, _, _, _,
      SortExec(_, _, DummySparkPlan(_, _, left: KeyGroupedPartitioning, _, _), _),
      SortExec(_, _, DummySparkPlan(_, _, right: KeyGroupedPartitioning, _, _), _), _) =>
        assert(left.expressions === Seq(bucket(4, exprA), years(exprB)))
        assert(right.expressions === Seq(bucket(4, exprA), years(exprC)))
      case other => fail(other.toString)
    }

    // both partitioning and distribution have duplicated keys
    plan1 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        years(exprA) :: bucket(4, exprB) :: days(exprA) :: Nil, 5))
    plan2 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        years(exprA) :: bucket(4, exprC) :: days(exprA) :: Nil, 5))
    smjExec = SortMergeJoinExec(
      exprA :: exprB :: exprB :: Nil, exprA :: exprC :: exprC :: Nil, Inner, None, plan1, plan2)
    applyEnsureRequirementsWithSubsetKeys(smjExec) match {
      case SortMergeJoinExec(_, _, _, _,
      SortExec(_, _, DummySparkPlan(_, _, left: KeyGroupedPartitioning, _, _), _),
      SortExec(_, _, DummySparkPlan(_, _, right: KeyGroupedPartitioning, _, _), _), _) =>
        assert(left.expressions === Seq(years(exprA), bucket(4, exprB), days(exprA)))
        assert(right.expressions === Seq(years(exprA), bucket(4, exprC), days(exprA)))
      case other => fail(other.toString)
    }

    // invalid case: partitioning key positions don't match
    plan1 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        bucket(4, exprA) :: bucket(4, exprB) :: Nil, 4)
    )
    plan2 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        bucket(4, exprB) :: bucket(4, exprC) :: Nil, 4)
    )

    smjExec = SortMergeJoinExec(
      exprA :: exprB :: exprC :: Nil, exprA :: exprB :: exprC :: Nil, Inner, None, plan1, plan2)
    applyEnsureRequirementsWithSubsetKeys(smjExec) match {
      case SortMergeJoinExec(_, _, _, _,
      SortExec(_, _, ShuffleExchangeExec(left: HashPartitioning, _, _), _),
      SortExec(_, _, ShuffleExchangeExec(right: HashPartitioning, _, _), _), _) =>
        assert(left.expressions === Seq(exprA, exprB, exprC))
        assert(right.expressions === Seq(exprA, exprB, exprC))
      case other => fail(other.toString)
    }

    // invalid case: different number of buckets (we don't support coalescing/repartitioning yet)
    plan1 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        bucket(4, exprA) :: bucket(4, exprB) :: Nil, 4)
    )
    plan2 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        bucket(4, exprA) :: bucket(8, exprC) :: Nil, 4)
    )
    smjExec = SortMergeJoinExec(
      exprA :: exprB :: exprB :: Nil, exprA :: exprC :: exprC :: Nil, Inner, None, plan1, plan2)
    applyEnsureRequirementsWithSubsetKeys(smjExec) match {
      case SortMergeJoinExec(_, _, _, _,
      SortExec(_, _, ShuffleExchangeExec(left: HashPartitioning, _, _), _),
      SortExec(_, _, ShuffleExchangeExec(right: HashPartitioning, _, _), _), _) =>
        assert(left.expressions === Seq(exprA, exprB, exprB))
        assert(right.expressions === Seq(exprA, exprC, exprC))
      case other => fail(other.toString)
    }

    // invalid case: partition key positions match but with different transforms
    plan1 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(years(exprA) :: bucket(4, exprB) :: Nil, 4)
    )
    plan2 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(days(exprA) :: bucket(4, exprC) :: Nil, 4)
    )
    smjExec = SortMergeJoinExec(
      exprA :: exprB :: exprB :: Nil, exprA :: exprC :: exprC :: Nil, Inner, None, plan1, plan2)
    applyEnsureRequirementsWithSubsetKeys(smjExec) match {
      case SortMergeJoinExec(_, _, _, _,
      SortExec(_, _, ShuffleExchangeExec(left: HashPartitioning, _, _), _),
      SortExec(_, _, ShuffleExchangeExec(right: HashPartitioning, _, _), _), _) =>
        assert(left.expressions === Seq(exprA, exprB, exprB))
        assert(right.expressions === Seq(exprA, exprC, exprC))
      case other => fail(other.toString)
    }


    // invalid case: multiple references in transform
    plan1 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        years(exprA) :: buckets(4, Seq(exprB, exprC)) :: Nil, 4)
    )
    plan2 = DummySparkPlan(
      outputPartitioning = KeyGroupedPartitioning(
        years(exprA) :: buckets(4, Seq(exprB, exprC)) :: Nil, 4)
    )
    smjExec = SortMergeJoinExec(
      exprA :: exprB :: exprB :: Nil, exprA :: exprC :: exprC :: Nil, Inner, None, plan1, plan2)
    applyEnsureRequirementsWithSubsetKeys(smjExec) match {
      case SortMergeJoinExec(_, _, _, _,
      SortExec(_, _, ShuffleExchangeExec(left: HashPartitioning, _, _), _),
      SortExec(_, _, ShuffleExchangeExec(right: HashPartitioning, _, _), _), _) =>
        assert(left.expressions === Seq(exprA, exprB, exprB))
        assert(right.expressions === Seq(exprA, exprC, exprC))
      case other => fail(other.toString)
    }
  }

  def bucket(numBuckets: Int, expr: Expression): TransformExpression = {
    TransformExpression(BucketFunction, Seq(expr), Some(numBuckets))
  }

  def buckets(numBuckets: Int, expr: Seq[Expression]): TransformExpression = {
    TransformExpression(BucketFunction, expr, Some(numBuckets))
  }

  def years(expr: Expression): TransformExpression = {
    TransformExpression(YearsFunction, Seq(expr))
  }

  def days(expr: Expression): TransformExpression = {
    TransformExpression(DaysFunction, Seq(expr))
  }
}
