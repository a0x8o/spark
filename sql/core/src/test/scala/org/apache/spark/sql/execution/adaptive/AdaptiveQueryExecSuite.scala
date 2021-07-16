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

package org.apache.spark.sql.execution.adaptive

import java.io.File
import java.net.URI

import org.apache.log4j.Level
import org.scalatest.PrivateMethodTester

import org.apache.spark.scheduler.{SparkListener, SparkListenerEvent, SparkListenerJobStart}
import org.apache.spark.sql.{Dataset, QueryTest, Row, SparkSession, Strategy}
import org.apache.spark.sql.catalyst.optimizer.{BuildLeft, BuildRight}
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, LogicalPlan}
import org.apache.spark.sql.execution.{CommandResultExec, LocalTableScanExec, PartialReducerPartitionSpec, QueryExecution, ReusedSubqueryExec, ShuffledRowRDD, SortExec, SparkPlan, UnaryExecNode}
import org.apache.spark.sql.execution.command.DataWritingCommandExec
import org.apache.spark.sql.execution.datasources.noop.NoopDataSource
import org.apache.spark.sql.execution.datasources.v2.V2TableWriteExec
import org.apache.spark.sql.execution.exchange.{BroadcastExchangeExec, ENSURE_REQUIREMENTS, Exchange, REPARTITION_BY_COL, REPARTITION_BY_NUM, ReusedExchangeExec, ShuffleExchangeExec, ShuffleExchangeLike, ShuffleOrigin}
import org.apache.spark.sql.execution.joins.{BaseJoinExec, BroadcastHashJoinExec, ShuffledHashJoinExec, ShuffledJoin, SortMergeJoinExec}
import org.apache.spark.sql.execution.metric.SQLShuffleReadMetricsReporter
import org.apache.spark.sql.execution.ui.SparkListenerSQLAdaptiveExecutionUpdate
import org.apache.spark.sql.functions._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf.PartitionOverwriteMode
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.test.SQLTestData.TestData
import org.apache.spark.sql.types.{IntegerType, StructType}
import org.apache.spark.sql.util.QueryExecutionListener
import org.apache.spark.util.Utils

class AdaptiveQueryExecSuite
  extends QueryTest
  with SharedSparkSession
  with AdaptiveSparkPlanHelper
  with PrivateMethodTester {

  import testImplicits._

  setupTestData()

  private def runAdaptiveAndVerifyResult(query: String): (SparkPlan, SparkPlan) = {
    var finalPlanCnt = 0
    val listener = new SparkListener {
      override def onOtherEvent(event: SparkListenerEvent): Unit = {
        event match {
          case SparkListenerSQLAdaptiveExecutionUpdate(_, _, sparkPlanInfo) =>
            if (sparkPlanInfo.simpleString.startsWith(
              "AdaptiveSparkPlan isFinalPlan=true")) {
              finalPlanCnt += 1
            }
          case _ => // ignore other events
        }
      }
    }
    spark.sparkContext.addSparkListener(listener)

    val dfAdaptive = sql(query)
    val planBefore = dfAdaptive.queryExecution.executedPlan
    assert(planBefore.toString.startsWith("AdaptiveSparkPlan isFinalPlan=false"))
    val result = dfAdaptive.collect()
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "false") {
      val df = sql(query)
      checkAnswer(df, result)
    }
    val planAfter = dfAdaptive.queryExecution.executedPlan
    assert(planAfter.toString.startsWith("AdaptiveSparkPlan isFinalPlan=true"))
    val adaptivePlan = planAfter.asInstanceOf[AdaptiveSparkPlanExec].executedPlan

    spark.sparkContext.listenerBus.waitUntilEmpty()
    // AQE will post `SparkListenerSQLAdaptiveExecutionUpdate` twice in case of subqueries that
    // exist out of query stages.
    val expectedFinalPlanCnt = adaptivePlan.find(_.subqueries.nonEmpty).map(_ => 2).getOrElse(1)
    assert(finalPlanCnt == expectedFinalPlanCnt)
    spark.sparkContext.removeSparkListener(listener)

    val exchanges = adaptivePlan.collect {
      case e: Exchange => e
    }
    assert(exchanges.isEmpty, "The final plan should not contain any Exchange node.")
    (dfAdaptive.queryExecution.sparkPlan, adaptivePlan)
  }

  private def findTopLevelBroadcastHashJoin(plan: SparkPlan): Seq[BroadcastHashJoinExec] = {
    collect(plan) {
      case j: BroadcastHashJoinExec => j
    }
  }

  private def findTopLevelSortMergeJoin(plan: SparkPlan): Seq[SortMergeJoinExec] = {
    collect(plan) {
      case j: SortMergeJoinExec => j
    }
  }

  private def findTopLevelShuffledHashJoin(plan: SparkPlan): Seq[ShuffledHashJoinExec] = {
    collect(plan) {
      case j: ShuffledHashJoinExec => j
    }
  }

  private def findTopLevelBaseJoin(plan: SparkPlan): Seq[BaseJoinExec] = {
    collect(plan) {
      case j: BaseJoinExec => j
    }
  }

  private def findTopLevelSort(plan: SparkPlan): Seq[SortExec] = {
    collect(plan) {
      case s: SortExec => s
    }
  }

  private def findReusedExchange(plan: SparkPlan): Seq[ReusedExchangeExec] = {
    collectWithSubqueries(plan) {
      case ShuffleQueryStageExec(_, e: ReusedExchangeExec, _) => e
      case BroadcastQueryStageExec(_, e: ReusedExchangeExec, _) => e
    }
  }

  private def findReusedSubquery(plan: SparkPlan): Seq[ReusedSubqueryExec] = {
    collectWithSubqueries(plan) {
      case e: ReusedSubqueryExec => e
    }
  }

  private def checkNumLocalShuffleReaders(
      plan: SparkPlan, numShufflesWithoutLocalReader: Int = 0): Unit = {
    val numShuffles = collect(plan) {
      case s: ShuffleQueryStageExec => s
    }.length

    val numLocalReaders = collect(plan) {
      case reader: CustomShuffleReaderExec if reader.isLocalReader => reader
    }
    numLocalReaders.foreach { r =>
      val rdd = r.execute()
      val parts = rdd.partitions
      assert(parts.forall(rdd.preferredLocations(_).nonEmpty))
    }
    assert(numShuffles === (numLocalReaders.length + numShufflesWithoutLocalReader))
  }

  private def checkInitialPartitionNum(df: Dataset[_], numPartition: Int): Unit = {
    // repartition obeys initialPartitionNum when adaptiveExecutionEnabled
    val plan = df.queryExecution.executedPlan
    assert(plan.isInstanceOf[AdaptiveSparkPlanExec])
    val shuffle = plan.asInstanceOf[AdaptiveSparkPlanExec].executedPlan.collect {
      case s: ShuffleExchangeExec => s
    }
    assert(shuffle.size == 1)
    assert(shuffle(0).outputPartitioning.numPartitions == numPartition)
  }

  test("Change merge join to broadcast join") {
    withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80") {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        "SELECT * FROM testData join testData2 ON key = a where value = '1'")
      val smj = findTopLevelSortMergeJoin(plan)
      assert(smj.size == 1)
      val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
      assert(bhj.size == 1)
      checkNumLocalShuffleReaders(adaptivePlan)
    }
  }

  test("Reuse the parallelism of CoalescedShuffleReaderExec in LocalShuffleReaderExec") {
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80",
      SQLConf.ADVISORY_PARTITION_SIZE_IN_BYTES.key -> "10") {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        "SELECT * FROM testData join testData2 ON key = a where value = '1'")
      val smj = findTopLevelSortMergeJoin(plan)
      assert(smj.size == 1)
      val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
      assert(bhj.size == 1)
      val localReaders = collect(adaptivePlan) {
        case reader: CustomShuffleReaderExec if reader.isLocalReader => reader
      }
      assert(localReaders.length == 2)
      val localShuffleRDD0 = localReaders(0).execute().asInstanceOf[ShuffledRowRDD]
      val localShuffleRDD1 = localReaders(1).execute().asInstanceOf[ShuffledRowRDD]
      // The pre-shuffle partition size is [0, 0, 0, 72, 0]
      // We exclude the 0-size partitions, so only one partition, advisoryParallelism = 1
      // the final parallelism is
      // math.max(1, advisoryParallelism / numMappers): math.max(1, 1/2) = 1
      // and the partitions length is 1 * numMappers = 2
      assert(localShuffleRDD0.getPartitions.length == 2)
      // The pre-shuffle partition size is [0, 72, 0, 72, 126]
      // We exclude the 0-size partitions, so only 3 partition, advisoryParallelism = 3
      // the final parallelism is
      // math.max(1, advisoryParallelism / numMappers): math.max(1, 3/2) = 1
      // and the partitions length is 1 * numMappers = 2
      assert(localShuffleRDD1.getPartitions.length == 2)
    }
  }

  test("Reuse the default parallelism in LocalShuffleReaderExec") {
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80",
      SQLConf.COALESCE_PARTITIONS_ENABLED.key -> "false") {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        "SELECT * FROM testData join testData2 ON key = a where value = '1'")
      val smj = findTopLevelSortMergeJoin(plan)
      assert(smj.size == 1)
      val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
      assert(bhj.size == 1)
      val localReaders = collect(adaptivePlan) {
        case reader: CustomShuffleReaderExec if reader.isLocalReader => reader
      }
      assert(localReaders.length == 2)
      val localShuffleRDD0 = localReaders(0).execute().asInstanceOf[ShuffledRowRDD]
      val localShuffleRDD1 = localReaders(1).execute().asInstanceOf[ShuffledRowRDD]
      // the final parallelism is math.max(1, numReduces / numMappers): math.max(1, 5/2) = 2
      // and the partitions length is 2 * numMappers = 4
      assert(localShuffleRDD0.getPartitions.length == 4)
      // the final parallelism is math.max(1, numReduces / numMappers): math.max(1, 5/2) = 2
      // and the partitions length is 2 * numMappers = 4
      assert(localShuffleRDD1.getPartitions.length == 4)
    }
  }

  test("Empty stage coalesced to 1-partition RDD") {
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.COALESCE_PARTITIONS_ENABLED.key -> "true",
      SQLConf.ADAPTIVE_OPTIMIZER_EXCLUDED_RULES.key -> AQEPropagateEmptyRelation.ruleName) {
      val df1 = spark.range(10).withColumn("a", 'id)
      val df2 = spark.range(10).withColumn("b", 'id)
      withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1") {
        val testDf = df1.where('a > 10).join(df2.where('b > 10), Seq("id"), "left_outer")
          .groupBy('a).count()
        checkAnswer(testDf, Seq())
        val plan = testDf.queryExecution.executedPlan
        assert(find(plan)(_.isInstanceOf[SortMergeJoinExec]).isDefined)
        val coalescedReaders = collect(plan) {
          case r: CustomShuffleReaderExec => r
        }
        assert(coalescedReaders.length == 3)
        coalescedReaders.foreach(r => assert(r.partitionSpecs.length == 1))
      }

      withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "1") {
        val testDf = df1.where('a > 10).join(df2.where('b > 10), Seq("id"), "left_outer")
          .groupBy('a).count()
        checkAnswer(testDf, Seq())
        val plan = testDf.queryExecution.executedPlan
        assert(find(plan)(_.isInstanceOf[BroadcastHashJoinExec]).isDefined)
        val coalescedReaders = collect(plan) {
          case r: CustomShuffleReaderExec => r
        }
        assert(coalescedReaders.length == 3, s"$plan")
        coalescedReaders.foreach(r => assert(r.isLocalReader || r.partitionSpecs.length == 1))
      }
    }
  }

  test("Scalar subquery") {
    withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80") {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        "SELECT * FROM testData join testData2 ON key = a " +
        "where value = (SELECT max(a) from testData3)")
      val smj = findTopLevelSortMergeJoin(plan)
      assert(smj.size == 1)
      val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
      assert(bhj.size == 1)
      checkNumLocalShuffleReaders(adaptivePlan)
    }
  }

  test("Scalar subquery in later stages") {
    withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80") {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        "SELECT * FROM testData join testData2 ON key = a " +
        "where (value + a) = (SELECT max(a) from testData3)")
      val smj = findTopLevelSortMergeJoin(plan)
      assert(smj.size == 1)
      val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
      assert(bhj.size == 1)

      checkNumLocalShuffleReaders(adaptivePlan)
    }
  }

  test("multiple joins") {
    withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80") {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        """
          |WITH t4 AS (
          |  SELECT * FROM lowercaseData t2 JOIN testData3 t3 ON t2.n = t3.a where t2.n = '1'
          |)
          |SELECT * FROM testData
          |JOIN testData2 t2 ON key = t2.a
          |JOIN t4 ON t2.b = t4.a
          |WHERE value = 1
        """.stripMargin)
      val smj = findTopLevelSortMergeJoin(plan)
      assert(smj.size == 3)
      val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
      assert(bhj.size == 3)

      // A possible resulting query plan:
      // BroadcastHashJoin
      // +- BroadcastExchange
      //    +- LocalShuffleReader*
      //       +- ShuffleExchange
      //          +- BroadcastHashJoin
      //             +- BroadcastExchange
      //                +- LocalShuffleReader*
      //                   +- ShuffleExchange
      //             +- LocalShuffleReader*
      //                +- ShuffleExchange
      // +- BroadcastHashJoin
      //    +- LocalShuffleReader*
      //       +- ShuffleExchange
      //    +- BroadcastExchange
      //       +-LocalShuffleReader*
      //             +- ShuffleExchange

      // After applied the 'OptimizeLocalShuffleReader' rule, we can convert all the four
      // shuffle reader to local shuffle reader in the bottom two 'BroadcastHashJoin'.
      // For the top level 'BroadcastHashJoin', the probe side is not shuffle query stage
      // and the build side shuffle query stage is also converted to local shuffle reader.
      checkNumLocalShuffleReaders(adaptivePlan)
    }
  }

  test("multiple joins with aggregate") {
    withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80") {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        """
          |WITH t4 AS (
          |  SELECT * FROM lowercaseData t2 JOIN (
          |    select a, sum(b) from testData3 group by a
          |  ) t3 ON t2.n = t3.a where t2.n = '1'
          |)
          |SELECT * FROM testData
          |JOIN testData2 t2 ON key = t2.a
          |JOIN t4 ON t2.b = t4.a
          |WHERE value = 1
        """.stripMargin)
      val smj = findTopLevelSortMergeJoin(plan)
      assert(smj.size == 3)
      val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
      assert(bhj.size == 3)

      // A possible resulting query plan:
      // BroadcastHashJoin
      // +- BroadcastExchange
      //    +- LocalShuffleReader*
      //       +- ShuffleExchange
      //          +- BroadcastHashJoin
      //             +- BroadcastExchange
      //                +- LocalShuffleReader*
      //                   +- ShuffleExchange
      //             +- LocalShuffleReader*
      //                +- ShuffleExchange
      // +- BroadcastHashJoin
      //    +- LocalShuffleReader*
      //       +- ShuffleExchange
      //    +- BroadcastExchange
      //       +-HashAggregate
      //          +- CoalescedShuffleReader
      //             +- ShuffleExchange

      // The shuffle added by Aggregate can't apply local reader.
      checkNumLocalShuffleReaders(adaptivePlan, 1)
    }
  }

  test("multiple joins with aggregate 2") {
    withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "500") {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        """
          |WITH t4 AS (
          |  SELECT * FROM lowercaseData t2 JOIN (
          |    select a, max(b) b from testData2 group by a
          |  ) t3 ON t2.n = t3.b
          |)
          |SELECT * FROM testData
          |JOIN testData2 t2 ON key = t2.a
          |JOIN t4 ON value = t4.a
          |WHERE value = 1
        """.stripMargin)
      val smj = findTopLevelSortMergeJoin(plan)
      assert(smj.size == 3)
      val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
      assert(bhj.size == 3)

      // A possible resulting query plan:
      // BroadcastHashJoin
      // +- BroadcastExchange
      //    +- LocalShuffleReader*
      //       +- ShuffleExchange
      //          +- BroadcastHashJoin
      //             +- BroadcastExchange
      //                +- LocalShuffleReader*
      //                   +- ShuffleExchange
      //             +- LocalShuffleReader*
      //                +- ShuffleExchange
      // +- BroadcastHashJoin
      //    +- Filter
      //       +- HashAggregate
      //          +- CoalescedShuffleReader
      //             +- ShuffleExchange
      //    +- BroadcastExchange
      //       +-LocalShuffleReader*
      //           +- ShuffleExchange

      // The shuffle added by Aggregate can't apply local reader.
      checkNumLocalShuffleReaders(adaptivePlan, 1)
    }
  }

  test("Exchange reuse") {
    withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80") {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        "SELECT value FROM testData join testData2 ON key = a " +
        "join (SELECT value v from testData join testData3 ON key = a) on value = v")
      val smj = findTopLevelSortMergeJoin(plan)
      assert(smj.size == 3)
      val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
      assert(bhj.size == 2)
      // There is still a SMJ, and its two shuffles can't apply local reader.
      checkNumLocalShuffleReaders(adaptivePlan, 2)
      // Even with local shuffle reader, the query stage reuse can also work.
      val ex = findReusedExchange(adaptivePlan)
      assert(ex.size == 1)
    }
  }

  test("Exchange reuse with subqueries") {
    withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80") {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        "SELECT a FROM testData join testData2 ON key = a " +
        "where value = (SELECT max(a) from testData join testData2 ON key = a)")
      val smj = findTopLevelSortMergeJoin(plan)
      assert(smj.size == 1)
      val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
      assert(bhj.size == 1)
      checkNumLocalShuffleReaders(adaptivePlan)
      // Even with local shuffle reader, the query stage reuse can also work.
      val ex = findReusedExchange(adaptivePlan)
      assert(ex.size == 1)
    }
  }

  test("Exchange reuse across subqueries") {
    withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80",
        SQLConf.SUBQUERY_REUSE_ENABLED.key -> "false") {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        "SELECT a FROM testData join testData2 ON key = a " +
        "where value >= (SELECT max(a) from testData join testData2 ON key = a) " +
        "and a <= (SELECT max(a) from testData join testData2 ON key = a)")
      val smj = findTopLevelSortMergeJoin(plan)
      assert(smj.size == 1)
      val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
      assert(bhj.size == 1)
      checkNumLocalShuffleReaders(adaptivePlan)
      // Even with local shuffle reader, the query stage reuse can also work.
      val ex = findReusedExchange(adaptivePlan)
      assert(ex.nonEmpty)
      val sub = findReusedSubquery(adaptivePlan)
      assert(sub.isEmpty)
    }
  }

  test("Subquery reuse") {
    withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80") {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        "SELECT a FROM testData join testData2 ON key = a " +
        "where value >= (SELECT max(a) from testData join testData2 ON key = a) " +
        "and a <= (SELECT max(a) from testData join testData2 ON key = a)")
      val smj = findTopLevelSortMergeJoin(plan)
      assert(smj.size == 1)
      val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
      assert(bhj.size == 1)
      checkNumLocalShuffleReaders(adaptivePlan)
      // Even with local shuffle reader, the query stage reuse can also work.
      val ex = findReusedExchange(adaptivePlan)
      assert(ex.isEmpty)
      val sub = findReusedSubquery(adaptivePlan)
      assert(sub.nonEmpty)
    }
  }

  test("Broadcast exchange reuse across subqueries") {
    withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "20000000",
        SQLConf.SUBQUERY_REUSE_ENABLED.key -> "false") {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        "SELECT a FROM testData join testData2 ON key = a " +
        "where value >= (" +
        "SELECT /*+ broadcast(testData2) */ max(key) from testData join testData2 ON key = a) " +
        "and a <= (" +
        "SELECT /*+ broadcast(testData2) */ max(value) from testData join testData2 ON key = a)")
      val smj = findTopLevelSortMergeJoin(plan)
      assert(smj.size == 1)
      val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
      assert(bhj.size == 1)
      checkNumLocalShuffleReaders(adaptivePlan)
      // Even with local shuffle reader, the query stage reuse can also work.
      val ex = findReusedExchange(adaptivePlan)
      assert(ex.nonEmpty)
      assert(ex.head.child.isInstanceOf[BroadcastExchangeExec])
      val sub = findReusedSubquery(adaptivePlan)
      assert(sub.isEmpty)
    }
  }

  test("Union/Except/Intersect queries") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
      runAdaptiveAndVerifyResult(
        """
          |SELECT * FROM testData
          |EXCEPT
          |SELECT * FROM testData2
          |UNION ALL
          |SELECT * FROM testData
          |INTERSECT ALL
          |SELECT * FROM testData2
        """.stripMargin)
    }
  }

  test("Subquery de-correlation in Union queries") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
      withTempView("a", "b") {
        Seq("a" -> 2, "b" -> 1).toDF("id", "num").createTempView("a")
        Seq("a" -> 2, "b" -> 1).toDF("id", "num").createTempView("b")

        runAdaptiveAndVerifyResult(
          """
            |SELECT id,num,source FROM (
            |  SELECT id, num, 'a' as source FROM a
            |  UNION ALL
            |  SELECT id, num, 'b' as source FROM b
            |) AS c WHERE c.id IN (SELECT id FROM b WHERE num = 2)
          """.stripMargin)
      }
    }
  }

  test("Avoid plan change if cost is greater") {
    val origPlan = sql("SELECT * FROM testData " +
      "join testData2 t2 ON key = t2.a " +
      "join testData2 t3 on t2.a = t3.a where t2.b = 1").queryExecution.executedPlan

    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80",
      SQLConf.BROADCAST_HASH_JOIN_OUTPUT_PARTITIONING_EXPAND_LIMIT.key -> "0") {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        "SELECT * FROM testData " +
          "join testData2 t2 ON key = t2.a " +
          "join testData2 t3 on t2.a = t3.a where t2.b = 1")
      val smj = findTopLevelSortMergeJoin(plan)
      assert(smj.size == 2)
      val smj2 = findTopLevelSortMergeJoin(adaptivePlan)
      assert(smj2.size == 2, origPlan.toString)
    }
  }

  test("Change merge join to broadcast join without local shuffle reader") {
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.LOCAL_SHUFFLE_READER_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "40") {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        """
          |SELECT * FROM testData t1 join testData2 t2
          |ON t1.key = t2.a join testData3 t3 on t2.a = t3.a
          |where t1.value = 1
        """.stripMargin
      )
      val smj = findTopLevelSortMergeJoin(plan)
      assert(smj.size == 2)
      val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
      assert(bhj.size == 1)
      // There is still a SMJ, and its two shuffles can't apply local reader.
      checkNumLocalShuffleReaders(adaptivePlan, 2)
    }
  }

  test("Avoid changing merge join to broadcast join if too many empty partitions on build plan") {
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.NON_EMPTY_PARTITION_RATIO_FOR_BROADCAST_JOIN.key -> "0.5") {
      // `testData` is small enough to be broadcast but has empty partition ratio over the config.
      withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80") {
        val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
          "SELECT * FROM testData join testData2 ON key = a where value = '1'")
        val smj = findTopLevelSortMergeJoin(plan)
        assert(smj.size == 1)
        val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
        assert(bhj.isEmpty)
      }
      // It is still possible to broadcast `testData2`.
      withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "2000") {
        val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
          "SELECT * FROM testData join testData2 ON key = a where value = '1'")
        val smj = findTopLevelSortMergeJoin(plan)
        assert(smj.size == 1)
        val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
        assert(bhj.size == 1)
        assert(bhj.head.buildSide == BuildRight)
      }
    }
  }

  test("SPARK-29906: AQE should not introduce extra shuffle for outermost limit") {
    var numStages = 0
    val listener = new SparkListener {
      override def onJobStart(jobStart: SparkListenerJobStart): Unit = {
        numStages = jobStart.stageInfos.length
      }
    }
    try {
      withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
        spark.sparkContext.addSparkListener(listener)
        spark.range(0, 100, 1, numPartitions = 10).take(1)
        spark.sparkContext.listenerBus.waitUntilEmpty()
        // Should be only one stage since there is no shuffle.
        assert(numStages == 1)
      }
    } finally {
      spark.sparkContext.removeSparkListener(listener)
    }
  }

  test("SPARK-30524: Do not optimize skew join if introduce additional shuffle") {
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
      SQLConf.SKEW_JOIN_SKEWED_PARTITION_THRESHOLD.key -> "100",
      SQLConf.ADVISORY_PARTITION_SIZE_IN_BYTES.key -> "100") {
      withTempView("skewData1", "skewData2") {
        spark
          .range(0, 1000, 1, 10)
          .selectExpr("id % 3 as key1", "id as value1")
          .createOrReplaceTempView("skewData1")
        spark
          .range(0, 1000, 1, 10)
          .selectExpr("id % 1 as key2", "id as value2")
          .createOrReplaceTempView("skewData2")

        def checkSkewJoin(query: String, optimizeSkewJoin: Boolean): Unit = {
          val (_, innerAdaptivePlan) = runAdaptiveAndVerifyResult(query)
          val innerSmj = findTopLevelSortMergeJoin(innerAdaptivePlan)
          assert(innerSmj.size == 1 && innerSmj.head.isSkewJoin == optimizeSkewJoin)
        }

        checkSkewJoin(
          "SELECT key1 FROM skewData1 JOIN skewData2 ON key1 = key2", true)
        // Additional shuffle introduced, so disable the "OptimizeSkewedJoin" optimization
        checkSkewJoin(
          "SELECT key1 FROM skewData1 JOIN skewData2 ON key1 = key2 GROUP BY key1", false)
      }
    }
  }

  test("SPARK-29544: adaptive skew join with different join types") {
    Seq("SHUFFLE_MERGE", "SHUFFLE_HASH").foreach { joinHint =>
      def getJoinNode(plan: SparkPlan): Seq[ShuffledJoin] = if (joinHint == "SHUFFLE_MERGE") {
        findTopLevelSortMergeJoin(plan)
      } else {
        findTopLevelShuffledHashJoin(plan)
      }
      withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
        SQLConf.COALESCE_PARTITIONS_MIN_PARTITION_NUM.key -> "1",
        SQLConf.SHUFFLE_PARTITIONS.key -> "100",
        SQLConf.SKEW_JOIN_SKEWED_PARTITION_THRESHOLD.key -> "800",
        SQLConf.ADVISORY_PARTITION_SIZE_IN_BYTES.key -> "800") {
        withTempView("skewData1", "skewData2") {
          spark
            .range(0, 1000, 1, 10)
            .select(
              when('id < 250, 249)
                .when('id >= 750, 1000)
                .otherwise('id).as("key1"),
              'id as "value1")
            .createOrReplaceTempView("skewData1")
          spark
            .range(0, 1000, 1, 10)
            .select(
              when('id < 250, 249)
                .otherwise('id).as("key2"),
              'id as "value2")
            .createOrReplaceTempView("skewData2")

          def checkSkewJoin(
              joins: Seq[ShuffledJoin],
              leftSkewNum: Int,
              rightSkewNum: Int): Unit = {
            assert(joins.size == 1 && joins.head.isSkewJoin)
            assert(joins.head.left.collect {
              case r: CustomShuffleReaderExec => r
            }.head.partitionSpecs.collect {
              case p: PartialReducerPartitionSpec => p.reducerIndex
            }.distinct.length == leftSkewNum)
            assert(joins.head.right.collect {
              case r: CustomShuffleReaderExec => r
            }.head.partitionSpecs.collect {
              case p: PartialReducerPartitionSpec => p.reducerIndex
            }.distinct.length == rightSkewNum)
          }

          // skewed inner join optimization
          val (_, innerAdaptivePlan) = runAdaptiveAndVerifyResult(
            s"SELECT /*+ $joinHint(skewData1) */ * FROM skewData1 " +
              "JOIN skewData2 ON key1 = key2")
          val inner = getJoinNode(innerAdaptivePlan)
          checkSkewJoin(inner, 2, 1)

          // skewed left outer join optimization
          val (_, leftAdaptivePlan) = runAdaptiveAndVerifyResult(
            s"SELECT /*+ $joinHint(skewData2) */ * FROM skewData1 " +
              "LEFT OUTER JOIN skewData2 ON key1 = key2")
          val leftJoin = getJoinNode(leftAdaptivePlan)
          checkSkewJoin(leftJoin, 2, 0)

          // skewed right outer join optimization
          val (_, rightAdaptivePlan) = runAdaptiveAndVerifyResult(
            s"SELECT /*+ $joinHint(skewData1) */ * FROM skewData1 " +
              "RIGHT OUTER JOIN skewData2 ON key1 = key2")
          val rightJoin = getJoinNode(rightAdaptivePlan)
          checkSkewJoin(rightJoin, 0, 1)
        }
      }
    }
  }

  test("SPARK-30291: AQE should catch the exceptions when doing materialize") {
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
      withTable("bucketed_table") {
        val df1 =
          (0 until 50).map(i => (i % 5, i % 13, i.toString)).toDF("i", "j", "k").as("df1")
        df1.write.format("parquet").bucketBy(8, "i").saveAsTable("bucketed_table")
        val warehouseFilePath = new URI(spark.sessionState.conf.warehousePath).getPath
        val tableDir = new File(warehouseFilePath, "bucketed_table")
        Utils.deleteRecursively(tableDir)
        df1.write.parquet(tableDir.getAbsolutePath)

        val aggregated = spark.table("bucketed_table").groupBy("i").count()
        val error = intercept[Exception] {
          aggregated.count()
        }
        assert(error.toString contains "Invalid bucket file")
        assert(error.getSuppressed.size === 0)
      }
    }
  }

  test("SPARK-30403: AQE should handle InSubquery") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
      runAdaptiveAndVerifyResult("SELECT * FROM testData LEFT OUTER join testData2" +
        " ON key = a  AND key NOT IN (select a from testData3) where value = '1'"
      )
    }
  }

  test("force apply AQE") {
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.ADAPTIVE_EXECUTION_FORCE_APPLY.key -> "true") {
      val plan = sql("SELECT * FROM testData").queryExecution.executedPlan
      assert(plan.isInstanceOf[AdaptiveSparkPlanExec])
    }
  }

  test("SPARK-30719: do not log warning if intentionally skip AQE") {
    val testAppender = new LogAppender("aqe logging warning test when skip")
    withLogAppender(testAppender) {
      withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
        val plan = sql("SELECT * FROM testData").queryExecution.executedPlan
        assert(!plan.isInstanceOf[AdaptiveSparkPlanExec])
      }
    }
    assert(!testAppender.loggingEvents
      .exists(msg => msg.getRenderedMessage.contains(
        s"${SQLConf.ADAPTIVE_EXECUTION_ENABLED.key} is" +
        s" enabled but is not supported for")))
  }

  test("test log level") {
    def verifyLog(expectedLevel: Level): Unit = {
      val logAppender = new LogAppender("adaptive execution")
      withLogAppender(
        logAppender,
        loggerNames = Seq(AdaptiveSparkPlanExec.getClass.getName.dropRight(1)),
        level = Some(Level.TRACE)) {
        withSQLConf(
          SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
          SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80") {
          sql("SELECT * FROM testData join testData2 ON key = a where value = '1'").collect()
        }
      }
      Seq("Plan changed", "Final plan").foreach { msg =>
        assert(
          logAppender.loggingEvents.exists { event =>
            event.getRenderedMessage.contains(msg) && event.getLevel == expectedLevel
          })
      }
    }

    // Verify default log level
    verifyLog(Level.DEBUG)

    // Verify custom log level
    val levels = Seq(
      "TRACE" -> Level.TRACE,
      "trace" -> Level.TRACE,
      "DEBUG" -> Level.DEBUG,
      "debug" -> Level.DEBUG,
      "INFO" -> Level.INFO,
      "info" -> Level.INFO,
      "WARN" -> Level.WARN,
      "warn" -> Level.WARN,
      "ERROR" -> Level.ERROR,
      "error" -> Level.ERROR,
      "deBUG" -> Level.DEBUG)

    levels.foreach { level =>
      withSQLConf(SQLConf.ADAPTIVE_EXECUTION_LOG_LEVEL.key -> level._1) {
        verifyLog(level._2)
      }
    }
  }

  test("tree string output") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
      val df = sql("SELECT * FROM testData join testData2 ON key = a where value = '1'")
      val planBefore = df.queryExecution.executedPlan
      assert(!planBefore.toString.contains("== Current Plan =="))
      assert(!planBefore.toString.contains("== Initial Plan =="))
      df.collect()
      val planAfter = df.queryExecution.executedPlan
      assert(planAfter.toString.contains("== Final Plan =="))
      assert(planAfter.toString.contains("== Initial Plan =="))
    }
  }

  test("SPARK-31384: avoid NPE in OptimizeSkewedJoin when there's 0 partition plan") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1") {
      withTempView("t2") {
        // create DataFrame with 0 partition
        spark.createDataFrame(sparkContext.emptyRDD[Row], new StructType().add("b", IntegerType))
          .createOrReplaceTempView("t2")
        // should run successfully without NPE
        runAdaptiveAndVerifyResult("SELECT * FROM testData2 t1 left semi join t2 ON t1.a=t2.b")
      }
    }
  }

  test("SPARK-34682: CustomShuffleReaderExec operating on canonicalized plan") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
      val (_, adaptivePlan) = runAdaptiveAndVerifyResult(
        "SELECT key FROM testData GROUP BY key")
      val readers = collect(adaptivePlan) {
        case r: CustomShuffleReaderExec => r
      }
      assert(readers.length == 1)
      val reader = readers.head
      val c = reader.canonicalized.asInstanceOf[CustomShuffleReaderExec]
      // we can't just call execute() because that has separate checks for canonicalized plans
      val ex = intercept[IllegalStateException] {
        val doExecute = PrivateMethod[Unit](Symbol("doExecute"))
        c.invokePrivate(doExecute())
      }
      assert(ex.getMessage === "operating on canonicalized plan")
    }
  }

  test("metrics of the shuffle reader") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
      val (_, adaptivePlan) = runAdaptiveAndVerifyResult(
        "SELECT key FROM testData GROUP BY key")
      val readers = collect(adaptivePlan) {
        case r: CustomShuffleReaderExec => r
      }
      assert(readers.length == 1)
      val reader = readers.head
      assert(!reader.isLocalReader)
      assert(!reader.hasSkewedPartition)
      assert(reader.hasCoalescedPartition)
      assert(reader.metrics.keys.toSeq.sorted == Seq(
        "numPartitions", "partitionDataSize"))
      assert(reader.metrics("numPartitions").value == reader.partitionSpecs.length)
      assert(reader.metrics("partitionDataSize").value > 0)

      withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80") {
        val (_, adaptivePlan) = runAdaptiveAndVerifyResult(
          "SELECT * FROM testData join testData2 ON key = a where value = '1'")
        val join = collect(adaptivePlan) {
          case j: BroadcastHashJoinExec => j
        }.head
        assert(join.buildSide == BuildLeft)

        val readers = collect(join.right) {
          case r: CustomShuffleReaderExec => r
        }
        assert(readers.length == 1)
        val reader = readers.head
        assert(reader.isLocalReader)
        assert(reader.metrics.keys.toSeq == Seq("numPartitions"))
        assert(reader.metrics("numPartitions").value == reader.partitionSpecs.length)
      }

      withSQLConf(
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
        SQLConf.SHUFFLE_PARTITIONS.key -> "100",
        SQLConf.SKEW_JOIN_SKEWED_PARTITION_THRESHOLD.key -> "800",
        SQLConf.ADVISORY_PARTITION_SIZE_IN_BYTES.key -> "1000") {
        withTempView("skewData1", "skewData2") {
          spark
            .range(0, 1000, 1, 10)
            .select(
              when('id < 250, 249)
                .when('id >= 750, 1000)
                .otherwise('id).as("key1"),
              'id as "value1")
            .createOrReplaceTempView("skewData1")
          spark
            .range(0, 1000, 1, 10)
            .select(
              when('id < 250, 249)
                .otherwise('id).as("key2"),
              'id as "value2")
            .createOrReplaceTempView("skewData2")
          val (_, adaptivePlan) = runAdaptiveAndVerifyResult(
            "SELECT * FROM skewData1 join skewData2 ON key1 = key2")
          val readers = collect(adaptivePlan) {
            case r: CustomShuffleReaderExec => r
          }
          readers.foreach { reader =>
            assert(!reader.isLocalReader)
            assert(reader.hasCoalescedPartition)
            assert(reader.hasSkewedPartition)
            assert(reader.metrics.contains("numSkewedPartitions"))
          }
          assert(readers(0).metrics("numSkewedPartitions").value == 2)
          assert(readers(0).metrics("numSkewedSplits").value == 11)
          assert(readers(1).metrics("numSkewedPartitions").value == 1)
          assert(readers(1).metrics("numSkewedSplits").value == 9)
        }
      }
    }
  }

  test("control a plan explain mode in listeners via SQLConf") {

    def checkPlanDescription(mode: String, expected: Seq[String]): Unit = {
      var checkDone = false
      val listener = new SparkListener {
        override def onOtherEvent(event: SparkListenerEvent): Unit = {
          event match {
            case SparkListenerSQLAdaptiveExecutionUpdate(_, planDescription, _) =>
              assert(expected.forall(planDescription.contains))
              checkDone = true
            case _ => // ignore other events
          }
        }
      }
      spark.sparkContext.addSparkListener(listener)
      withSQLConf(SQLConf.UI_EXPLAIN_MODE.key -> mode,
          SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
          SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80") {
        val dfAdaptive = sql("SELECT * FROM testData JOIN testData2 ON key = a WHERE value = '1'")
        try {
          checkAnswer(dfAdaptive, Row(1, "1", 1, 1) :: Row(1, "1", 1, 2) :: Nil)
          spark.sparkContext.listenerBus.waitUntilEmpty()
          assert(checkDone)
        } finally {
          spark.sparkContext.removeSparkListener(listener)
        }
      }
    }

    Seq(("simple", Seq("== Physical Plan ==")),
        ("extended", Seq("== Parsed Logical Plan ==", "== Analyzed Logical Plan ==",
          "== Optimized Logical Plan ==", "== Physical Plan ==")),
        ("codegen", Seq("WholeStageCodegen subtrees")),
        ("cost", Seq("== Optimized Logical Plan ==", "Statistics(sizeInBytes")),
        ("formatted", Seq("== Physical Plan ==", "Output", "Arguments"))).foreach {
      case (mode, expected) =>
        checkPlanDescription(mode, expected)
    }
  }

  test("SPARK-30953: InsertAdaptiveSparkPlan should apply AQE on child plan of write commands") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.ADAPTIVE_EXECUTION_FORCE_APPLY.key -> "true") {
      withTable("t1") {
        val plan = sql("CREATE TABLE t1 USING parquet AS SELECT 1 col").queryExecution.executedPlan
        assert(plan.isInstanceOf[CommandResultExec])
        val commandResultExec = plan.asInstanceOf[CommandResultExec]
        assert(commandResultExec.commandPhysicalPlan.isInstanceOf[DataWritingCommandExec])
        assert(commandResultExec.commandPhysicalPlan.asInstanceOf[DataWritingCommandExec]
          .child.isInstanceOf[AdaptiveSparkPlanExec])
      }
    }
  }

  test("AQE should set active session during execution") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
      val df = spark.range(10).select(sum('id))
      assert(df.queryExecution.executedPlan.isInstanceOf[AdaptiveSparkPlanExec])
      SparkSession.setActiveSession(null)
      checkAnswer(df, Seq(Row(45)))
      SparkSession.setActiveSession(spark) // recover the active session.
    }
  }

  test("No deadlock in UI update") {
    object TestStrategy extends Strategy {
      def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
        case _: Aggregate =>
          withSQLConf(
            SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
            SQLConf.ADAPTIVE_EXECUTION_FORCE_APPLY.key -> "true") {
            spark.range(5).rdd
          }
          Nil
        case _ => Nil
      }
    }

    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.ADAPTIVE_EXECUTION_FORCE_APPLY.key -> "true") {
      try {
        spark.experimental.extraStrategies = TestStrategy :: Nil
        val df = spark.range(10).groupBy('id).count()
        df.collect()
      } finally {
        spark.experimental.extraStrategies = Nil
      }
    }
  }

  test("SPARK-31658: SQL UI should show write commands") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.ADAPTIVE_EXECUTION_FORCE_APPLY.key -> "true") {
      withTable("t1") {
        var checkDone = false
        val listener = new SparkListener {
          override def onOtherEvent(event: SparkListenerEvent): Unit = {
            event match {
              case SparkListenerSQLAdaptiveExecutionUpdate(_, _, planInfo) =>
                assert(planInfo.nodeName == "Execute CreateDataSourceTableAsSelectCommand")
                checkDone = true
              case _ => // ignore other events
            }
          }
        }
        spark.sparkContext.addSparkListener(listener)
        try {
          sql("CREATE TABLE t1 USING parquet AS SELECT 1 col").collect()
          spark.sparkContext.listenerBus.waitUntilEmpty()
          assert(checkDone)
        } finally {
          spark.sparkContext.removeSparkListener(listener)
        }
      }
    }
  }

  test("SPARK-31220, SPARK-32056: repartition by expression with AQE") {
    Seq(true, false).foreach { enableAQE =>
      withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> enableAQE.toString,
        SQLConf.COALESCE_PARTITIONS_ENABLED.key -> "true",
        SQLConf.COALESCE_PARTITIONS_INITIAL_PARTITION_NUM.key -> "10",
        SQLConf.SHUFFLE_PARTITIONS.key -> "10") {

        val df1 = spark.range(10).repartition($"id")
        val df2 = spark.range(10).repartition($"id" + 1)

        val partitionsNum1 = df1.rdd.collectPartitions().length
        val partitionsNum2 = df2.rdd.collectPartitions().length

        if (enableAQE) {
          assert(partitionsNum1 < 10)
          assert(partitionsNum2 < 10)

          checkInitialPartitionNum(df1, 10)
          checkInitialPartitionNum(df2, 10)
        } else {
          assert(partitionsNum1 === 10)
          assert(partitionsNum2 === 10)
        }


        // Don't coalesce partitions if the number of partitions is specified.
        val df3 = spark.range(10).repartition(10, $"id")
        val df4 = spark.range(10).repartition(10)
        assert(df3.rdd.collectPartitions().length == 10)
        assert(df4.rdd.collectPartitions().length == 10)
      }
    }
  }

  test("SPARK-31220, SPARK-32056: repartition by range with AQE") {
    Seq(true, false).foreach { enableAQE =>
      withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> enableAQE.toString,
        SQLConf.COALESCE_PARTITIONS_ENABLED.key -> "true",
        SQLConf.COALESCE_PARTITIONS_INITIAL_PARTITION_NUM.key -> "10",
        SQLConf.SHUFFLE_PARTITIONS.key -> "10") {

        val df1 = spark.range(10).toDF.repartitionByRange($"id".asc)
        val df2 = spark.range(10).toDF.repartitionByRange(($"id" + 1).asc)

        val partitionsNum1 = df1.rdd.collectPartitions().length
        val partitionsNum2 = df2.rdd.collectPartitions().length

        if (enableAQE) {
          assert(partitionsNum1 < 10)
          assert(partitionsNum2 < 10)

          checkInitialPartitionNum(df1, 10)
          checkInitialPartitionNum(df2, 10)
        } else {
          assert(partitionsNum1 === 10)
          assert(partitionsNum2 === 10)
        }

        // Don't coalesce partitions if the number of partitions is specified.
        val df3 = spark.range(10).repartitionByRange(10, $"id".asc)
        assert(df3.rdd.collectPartitions().length == 10)
      }
    }
  }

  test("SPARK-31220, SPARK-32056: repartition using sql and hint with AQE") {
    Seq(true, false).foreach { enableAQE =>
      withTempView("test") {
        withSQLConf(
          SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> enableAQE.toString,
          SQLConf.COALESCE_PARTITIONS_ENABLED.key -> "true",
          SQLConf.COALESCE_PARTITIONS_INITIAL_PARTITION_NUM.key -> "10",
          SQLConf.SHUFFLE_PARTITIONS.key -> "10") {

          spark.range(10).toDF.createTempView("test")

          val df1 = spark.sql("SELECT /*+ REPARTITION(id) */ * from test")
          val df2 = spark.sql("SELECT /*+ REPARTITION_BY_RANGE(id) */ * from test")
          val df3 = spark.sql("SELECT * from test DISTRIBUTE BY id")
          val df4 = spark.sql("SELECT * from test CLUSTER BY id")

          val partitionsNum1 = df1.rdd.collectPartitions().length
          val partitionsNum2 = df2.rdd.collectPartitions().length
          val partitionsNum3 = df3.rdd.collectPartitions().length
          val partitionsNum4 = df4.rdd.collectPartitions().length

          if (enableAQE) {
            assert(partitionsNum1 < 10)
            assert(partitionsNum2 < 10)
            assert(partitionsNum3 < 10)
            assert(partitionsNum4 < 10)

            checkInitialPartitionNum(df1, 10)
            checkInitialPartitionNum(df2, 10)
            checkInitialPartitionNum(df3, 10)
            checkInitialPartitionNum(df4, 10)
          } else {
            assert(partitionsNum1 === 10)
            assert(partitionsNum2 === 10)
            assert(partitionsNum3 === 10)
            assert(partitionsNum4 === 10)
          }

          // Don't coalesce partitions if the number of partitions is specified.
          val df5 = spark.sql("SELECT /*+ REPARTITION(10, id) */ * from test")
          val df6 = spark.sql("SELECT /*+ REPARTITION_BY_RANGE(10, id) */ * from test")
          assert(df5.rdd.collectPartitions().length == 10)
          assert(df6.rdd.collectPartitions().length == 10)
        }
      }
    }
  }

  test("SPARK-32573: Eliminate NAAJ when BuildSide is HashedRelationWithAllNullKeys") {
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> Long.MaxValue.toString) {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        "SELECT * FROM testData2 t1 WHERE t1.b NOT IN (SELECT b FROM testData3)")
      val bhj = findTopLevelBroadcastHashJoin(plan)
      assert(bhj.size == 1)
      val join = findTopLevelBaseJoin(adaptivePlan)
      assert(join.isEmpty)
      checkNumLocalShuffleReaders(adaptivePlan)
    }
  }

  test("SPARK-32717: AQEOptimizer should respect excludedRules configuration") {
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> Long.MaxValue.toString,
      // This test is a copy of test(SPARK-32573), in order to test the configuration
      // `spark.sql.adaptive.optimizer.excludedRules` works as expect.
      SQLConf.ADAPTIVE_OPTIMIZER_EXCLUDED_RULES.key -> AQEPropagateEmptyRelation.ruleName) {
      val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(
        "SELECT * FROM testData2 t1 WHERE t1.b NOT IN (SELECT b FROM testData3)")
      val bhj = findTopLevelBroadcastHashJoin(plan)
      assert(bhj.size == 1)
      val join = findTopLevelBaseJoin(adaptivePlan)
      // this is different compares to test(SPARK-32573) due to the rule
      // `EliminateUnnecessaryJoin` has been excluded.
      assert(join.nonEmpty)
      checkNumLocalShuffleReaders(adaptivePlan)
    }
  }

  test("SPARK-32649: Eliminate inner and semi join to empty relation") {
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80") {
      Seq(
        // inner join (small table at right side)
        "SELECT * FROM testData t1 join testData3 t2 ON t1.key = t2.a WHERE t2.b = 1",
        // inner join (small table at left side)
        "SELECT * FROM testData3 t1 join testData t2 ON t1.a = t2.key WHERE t1.b = 1",
        // left semi join
        "SELECT * FROM testData t1 left semi join testData3 t2 ON t1.key = t2.a AND t2.b = 1"
      ).foreach(query => {
        val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(query)
        val smj = findTopLevelSortMergeJoin(plan)
        assert(smj.size == 1)
        val join = findTopLevelBaseJoin(adaptivePlan)
        assert(join.isEmpty)
        checkNumLocalShuffleReaders(adaptivePlan)
      })
    }
  }

  test("SPARK-34533: Eliminate left anti join to empty relation") {
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
      Seq(
        // broadcast non-empty right side
        ("SELECT /*+ broadcast(testData3) */ * FROM testData LEFT ANTI JOIN testData3", true),
        // broadcast empty right side
        ("SELECT /*+ broadcast(emptyTestData) */ * FROM testData LEFT ANTI JOIN emptyTestData",
          true),
        // broadcast left side
        ("SELECT /*+ broadcast(testData) */ * FROM testData LEFT ANTI JOIN testData3", false)
      ).foreach { case (query, isEliminated) =>
        val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(query)
        assert(findTopLevelBaseJoin(plan).size == 1)
        assert(findTopLevelBaseJoin(adaptivePlan).isEmpty == isEliminated)
      }
    }
  }

  test("SPARK-34781: Eliminate left semi/anti join to its left side") {
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
      Seq(
        // left semi join and non-empty right side
        ("SELECT * FROM testData LEFT SEMI JOIN testData3", true),
        // left semi join, non-empty right side and non-empty join condition
        ("SELECT * FROM testData t1 LEFT SEMI JOIN testData3 t2 ON t1.key = t2.a", false),
        // left anti join and empty right side
        ("SELECT * FROM testData LEFT ANTI JOIN emptyTestData", true),
        // left anti join, empty right side and non-empty join condition
        ("SELECT * FROM testData t1 LEFT ANTI JOIN emptyTestData t2 ON t1.key = t2.key", true)
      ).foreach { case (query, isEliminated) =>
        val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(query)
        assert(findTopLevelBaseJoin(plan).size == 1)
        assert(findTopLevelBaseJoin(adaptivePlan).isEmpty == isEliminated)
      }
    }
  }

  test("SPARK-35455: Unify empty relation optimization between normal and AQE optimizer " +
    "- single join") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1") {
      Seq(
        // left semi join and empty left side
        ("SELECT * FROM (SELECT * FROM testData WHERE value = '0')t1 LEFT SEMI JOIN " +
          "testData2 t2 ON t1.key = t2.a", true),
        // left anti join and empty left side
        ("SELECT * FROM (SELECT * FROM testData WHERE value = '0')t1 LEFT ANTI JOIN " +
          "testData2 t2 ON t1.key = t2.a", true),
        // left outer join and empty left side
        ("SELECT * FROM (SELECT * FROM testData WHERE key = 0)t1 LEFT JOIN testData2 t2 ON " +
          "t1.key = t2.a", true),
        // left outer join and non-empty left side
        ("SELECT * FROM testData t1 LEFT JOIN testData2 t2 ON " +
          "t1.key = t2.a", false),
        // right outer join and empty right side
        ("SELECT * FROM testData t1 RIGHT JOIN (SELECT * FROM testData2 WHERE b = 0)t2 ON " +
          "t1.key = t2.a", true),
        // right outer join and non-empty right side
        ("SELECT * FROM testData t1 RIGHT JOIN testData2 t2 ON " +
          "t1.key = t2.a", false),
        // full outer join and both side empty
        ("SELECT * FROM (SELECT * FROM testData WHERE key = 0)t1 FULL JOIN " +
          "(SELECT * FROM testData2 WHERE b = 0)t2 ON t1.key = t2.a", true),
        // full outer join and left side empty right side non-empty
        ("SELECT * FROM (SELECT * FROM testData WHERE key = 0)t1 FULL JOIN " +
          "testData2 t2 ON t1.key = t2.a", true)
      ).foreach { case (query, isEliminated) =>
        val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(query)
        assert(findTopLevelBaseJoin(plan).size == 1)
        assert(findTopLevelBaseJoin(adaptivePlan).isEmpty == isEliminated, adaptivePlan)
      }
    }
  }

  test("SPARK-35455: Unify empty relation optimization between normal and AQE optimizer " +
    "- multi join") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1") {
      Seq(
        """
         |SELECT * FROM testData t1
         | JOIN (SELECT * FROM testData2 WHERE b = 0) t2 ON t1.key = t2.a
         | LEFT JOIN testData2 t3 ON t1.key = t3.a
         |""".stripMargin,
        """
         |SELECT * FROM (SELECT * FROM testData WHERE key = 0) t1
         | LEFT ANTI JOIN testData2 t2
         | FULL JOIN (SELECT * FROM testData2 WHERE b = 0) t3 ON t1.key = t3.a
         |""".stripMargin,
        """
         |SELECT * FROM testData t1
         | LEFT SEMI JOIN (SELECT * FROM testData2 WHERE b = 0)
         | RIGHT JOIN testData2 t3 on t1.key = t3.a
         |""".stripMargin
      ).foreach { query =>
        val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(query)
        assert(findTopLevelBaseJoin(plan).size == 2)
        assert(findTopLevelBaseJoin(adaptivePlan).isEmpty)
      }
    }
  }

  test("SPARK-35585: Support propagate empty relation through project/filter") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1") {
      val (plan1, adaptivePlan1) = runAdaptiveAndVerifyResult(
        "SELECT key FROM testData WHERE key = 0 ORDER BY key, value")
      assert(findTopLevelSort(plan1).size == 1)
      assert(stripAQEPlan(adaptivePlan1).isInstanceOf[LocalTableScanExec])

      val (plan2, adaptivePlan2) = runAdaptiveAndVerifyResult(
       "SELECT key FROM (SELECT * FROM testData WHERE value = 'no_match' ORDER BY key)" +
         " WHERE key > rand()")
      assert(findTopLevelSort(plan2).size == 1)
      assert(stripAQEPlan(adaptivePlan2).isInstanceOf[LocalTableScanExec])
    }
  }

  test("SPARK-32753: Only copy tags to node with no tags") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
      withTempView("v1") {
        spark.range(10).union(spark.range(10)).createOrReplaceTempView("v1")

        val (_, adaptivePlan) = runAdaptiveAndVerifyResult(
          "SELECT id FROM v1 GROUP BY id DISTRIBUTE BY id")
        assert(collect(adaptivePlan) {
          case s: ShuffleExchangeExec => s
        }.length == 1)
      }
    }
  }

  test("Logging plan changes for AQE") {
    val testAppender = new LogAppender("plan changes")
    withLogAppender(testAppender) {
      withSQLConf(
          SQLConf.PLAN_CHANGE_LOG_LEVEL.key -> "INFO",
          SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
          SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80") {
        sql("SELECT * FROM testData JOIN testData2 ON key = a " +
          "WHERE value = (SELECT max(a) FROM testData3)").collect()
      }
      Seq("=== Result of Batch AQE Preparations ===",
          "=== Result of Batch AQE Post Stage Creation ===",
          "=== Result of Batch AQE Replanning ===",
          "=== Result of Batch AQE Query Stage Optimization ===",
          "=== Result of Batch AQE Final Query Stage Optimization ===").foreach { expectedMsg =>
        assert(testAppender.loggingEvents.exists(_.getRenderedMessage.contains(expectedMsg)))
      }
    }
  }

  test("SPARK-32932: Do not use local shuffle reader at final stage on write command") {
    withSQLConf(SQLConf.PARTITION_OVERWRITE_MODE.key -> PartitionOverwriteMode.DYNAMIC.toString,
      SQLConf.SHUFFLE_PARTITIONS.key -> "5",
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
      val data = for (
        i <- 1L to 10L;
        j <- 1L to 3L
      ) yield (i, j)

      val df = data.toDF("i", "j").repartition($"j")
      var noLocalReader: Boolean = false
      val listener = new QueryExecutionListener {
        override def onSuccess(funcName: String, qe: QueryExecution, durationNs: Long): Unit = {
          qe.executedPlan match {
            case plan@(_: DataWritingCommandExec | _: V2TableWriteExec) =>
              assert(plan.asInstanceOf[UnaryExecNode].child.isInstanceOf[AdaptiveSparkPlanExec])
              noLocalReader = collect(plan) {
                case exec: CustomShuffleReaderExec if exec.isLocalReader => exec
              }.isEmpty
            case _ => // ignore other events
          }
        }
        override def onFailure(funcName: String, qe: QueryExecution,
          exception: Exception): Unit = {}
      }
      spark.listenerManager.register(listener)

      withTable("t") {
        df.write.partitionBy("j").saveAsTable("t")
        sparkContext.listenerBus.waitUntilEmpty()
        assert(noLocalReader)
        noLocalReader = false
      }

      // Test DataSource v2
      val format = classOf[NoopDataSource].getName
      df.write.format(format).mode("overwrite").save()
      sparkContext.listenerBus.waitUntilEmpty()
      assert(noLocalReader)
      noLocalReader = false

      spark.listenerManager.unregister(listener)
    }
  }

  test("SPARK-33494: Do not use local shuffle reader for repartition") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
      val df = spark.table("testData").repartition('key)
      df.collect()
      // local shuffle reader breaks partitioning and shouldn't be used for repartition operation
      // which is specified by users.
      checkNumLocalShuffleReaders(df.queryExecution.executedPlan, numShufflesWithoutLocalReader = 1)
    }
  }

  test("SPARK-33551: Do not use custom shuffle reader for repartition") {
    def hasRepartitionShuffle(plan: SparkPlan): Boolean = {
      find(plan) {
        case s: ShuffleExchangeLike =>
          s.shuffleOrigin == REPARTITION_BY_COL || s.shuffleOrigin == REPARTITION_BY_NUM
        case _ => false
      }.isDefined
    }

    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.SHUFFLE_PARTITIONS.key -> "5") {
      val df = sql(
        """
          |SELECT * FROM (
          |  SELECT * FROM testData WHERE key = 1
          |)
          |RIGHT OUTER JOIN testData2
          |ON value = b
        """.stripMargin)

      withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80") {
        // Repartition with no partition num specified.
        val dfRepartition = df.repartition('b)
        dfRepartition.collect()
        val plan = dfRepartition.queryExecution.executedPlan
        // The top shuffle from repartition is optimized out.
        assert(!hasRepartitionShuffle(plan))
        val bhj = findTopLevelBroadcastHashJoin(plan)
        assert(bhj.length == 1)
        checkNumLocalShuffleReaders(plan, 1)
        // Probe side is coalesced.
        val customReader = bhj.head.right.find(_.isInstanceOf[CustomShuffleReaderExec])
        assert(customReader.isDefined)
        assert(customReader.get.asInstanceOf[CustomShuffleReaderExec].hasCoalescedPartition)

        // Repartition with partition default num specified.
        val dfRepartitionWithNum = df.repartition(5, 'b)
        dfRepartitionWithNum.collect()
        val planWithNum = dfRepartitionWithNum.queryExecution.executedPlan
        // The top shuffle from repartition is not optimized out.
        assert(hasRepartitionShuffle(planWithNum))
        val bhjWithNum = findTopLevelBroadcastHashJoin(planWithNum)
        assert(bhjWithNum.length == 1)
        checkNumLocalShuffleReaders(planWithNum, 1)
        // Probe side is coalesced.
        assert(bhjWithNum.head.right.find(_.isInstanceOf[CustomShuffleReaderExec]).nonEmpty)

        // Repartition with partition non-default num specified.
        val dfRepartitionWithNum2 = df.repartition(3, 'b)
        dfRepartitionWithNum2.collect()
        val planWithNum2 = dfRepartitionWithNum2.queryExecution.executedPlan
        // The top shuffle from repartition is not optimized out, and this is the only shuffle that
        // does not have local shuffle reader.
        assert(hasRepartitionShuffle(planWithNum2))
        val bhjWithNum2 = findTopLevelBroadcastHashJoin(planWithNum2)
        assert(bhjWithNum2.length == 1)
        checkNumLocalShuffleReaders(planWithNum2, 1)
        val customReader2 = bhjWithNum2.head.right.find(_.isInstanceOf[CustomShuffleReaderExec])
        assert(customReader2.isDefined)
        assert(customReader2.get.asInstanceOf[CustomShuffleReaderExec].isLocalReader)
      }

      // Force skew join
      withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
        SQLConf.SKEW_JOIN_ENABLED.key -> "true",
        SQLConf.SKEW_JOIN_SKEWED_PARTITION_THRESHOLD.key -> "1",
        SQLConf.SKEW_JOIN_SKEWED_PARTITION_FACTOR.key -> "0",
        SQLConf.ADVISORY_PARTITION_SIZE_IN_BYTES.key -> "10") {
        // Repartition with no partition num specified.
        val dfRepartition = df.repartition('b)
        dfRepartition.collect()
        val plan = dfRepartition.queryExecution.executedPlan
        // The top shuffle from repartition is optimized out.
        assert(!hasRepartitionShuffle(plan))
        val smj = findTopLevelSortMergeJoin(plan)
        assert(smj.length == 1)
        // No skew join due to the repartition.
        assert(!smj.head.isSkewJoin)
        // Both sides are coalesced.
        val customReaders = collect(smj.head) {
          case c: CustomShuffleReaderExec if c.hasCoalescedPartition => c
        }
        assert(customReaders.length == 2)

        // Repartition with default partition num specified.
        val dfRepartitionWithNum = df.repartition(5, 'b)
        dfRepartitionWithNum.collect()
        val planWithNum = dfRepartitionWithNum.queryExecution.executedPlan
        // The top shuffle from repartition is not optimized out.
        assert(hasRepartitionShuffle(planWithNum))
        val smjWithNum = findTopLevelSortMergeJoin(planWithNum)
        assert(smjWithNum.length == 1)
        // Skew join can apply as the repartition is not optimized out.
        assert(smjWithNum.head.isSkewJoin)
        val customReadersWithNum = collect(smjWithNum.head) {
          case c: CustomShuffleReaderExec => c
        }
        assert(customReadersWithNum.nonEmpty)

        // Repartition with default non-partition num specified.
        val dfRepartitionWithNum2 = df.repartition(3, 'b)
        dfRepartitionWithNum2.collect()
        val planWithNum2 = dfRepartitionWithNum2.queryExecution.executedPlan
        // The top shuffle from repartition is not optimized out.
        assert(hasRepartitionShuffle(planWithNum2))
        val smjWithNum2 = findTopLevelSortMergeJoin(planWithNum2)
        assert(smjWithNum2.length == 1)
        // Skew join can apply as the repartition is not optimized out.
        assert(smjWithNum2.head.isSkewJoin)
      }
    }
  }

  test("SPARK-34091: Batch shuffle fetch in AQE partition coalescing") {
    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.SHUFFLE_PARTITIONS.key -> "10",
      SQLConf.FETCH_SHUFFLE_BLOCKS_IN_BATCH.key -> "true") {
      withTable("t1") {
        spark.range(100).selectExpr("id + 1 as a").write.format("parquet").saveAsTable("t1")
        val query = "SELECT SUM(a) FROM t1 GROUP BY a"
        val (_, adaptivePlan) = runAdaptiveAndVerifyResult(query)
        val metricName = SQLShuffleReadMetricsReporter.LOCAL_BLOCKS_FETCHED
        val blocksFetchedMetric = collectFirst(adaptivePlan) {
          case p if p.metrics.contains(metricName) => p.metrics(metricName)
        }
        assert(blocksFetchedMetric.isDefined)
        val blocksFetched = blocksFetchedMetric.get.value
        withSQLConf(SQLConf.FETCH_SHUFFLE_BLOCKS_IN_BATCH.key -> "false") {
          val (_, adaptivePlan2) = runAdaptiveAndVerifyResult(query)
          val blocksFetchedMetric2 = collectFirst(adaptivePlan2) {
            case p if p.metrics.contains(metricName) => p.metrics(metricName)
          }
          assert(blocksFetchedMetric2.isDefined)
          val blocksFetched2 = blocksFetchedMetric2.get.value
          assert(blocksFetched < blocksFetched2)
        }
      }
    }
  }

  test("SPARK-33933: Materialize BroadcastQueryStage first in AQE") {
    val testAppender = new LogAppender("aqe query stage materialization order test")
    val df = spark.range(1000).select($"id" % 26, $"id" % 10)
      .toDF("index", "pv")
    val dim = Range(0, 26).map(x => (x, ('a' + x).toChar.toString))
      .toDF("index", "name")
    val testDf = df.groupBy("index")
      .agg(sum($"pv").alias("pv"))
      .join(dim, Seq("index"))
    val loggerNames =
      Seq(classOf[BroadcastQueryStageExec].getName, classOf[ShuffleQueryStageExec].getName)
    withLogAppender(testAppender, loggerNames, level = Some(Level.DEBUG)) {
      withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
        val result = testDf.collect()
        assert(result.length == 26)
      }
    }
    val materializeLogs = testAppender.loggingEvents
      .map(_.getRenderedMessage)
      .filter(_.startsWith("Materialize query stage"))
      .toArray
    assert(materializeLogs(0).startsWith("Materialize query stage BroadcastQueryStageExec"))
    assert(materializeLogs(1).startsWith("Materialize query stage ShuffleQueryStageExec"))
  }

  test("SPARK-34899: Use origin plan if we can not coalesce shuffle partition") {
    def checkNoCoalescePartitions(ds: Dataset[Row], origin: ShuffleOrigin): Unit = {
      assert(collect(ds.queryExecution.executedPlan) {
        case s: ShuffleExchangeExec if s.shuffleOrigin == origin && s.numPartitions == 2 => s
      }.size == 1)
      ds.collect()
      val plan = ds.queryExecution.executedPlan
      assert(collect(plan) {
        case c: CustomShuffleReaderExec => c
      }.isEmpty)
      assert(collect(plan) {
        case s: ShuffleExchangeExec if s.shuffleOrigin == origin && s.numPartitions == 2 => s
      }.size == 1)
      checkAnswer(ds, testData)
    }

    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.COALESCE_PARTITIONS_ENABLED.key -> "true",
      SQLConf.ADVISORY_PARTITION_SIZE_IN_BYTES.key -> "2258",
      SQLConf.COALESCE_PARTITIONS_MIN_PARTITION_NUM.key -> "1",
      SQLConf.SHUFFLE_PARTITIONS.key -> "2") {
      val df = spark.sparkContext.parallelize(
        (1 to 100).map(i => TestData(i, i.toString)), 10).toDF()

      // partition size [1420, 1420]
      checkNoCoalescePartitions(df.repartition($"key"), REPARTITION_BY_COL)
      // partition size [1140, 1119]
      checkNoCoalescePartitions(df.sort($"key"), ENSURE_REQUIREMENTS)
    }
  }

  test("SPARK-35239: Coalesce shuffle partition should handle empty input RDD") {
    withTable("t") {
      withSQLConf(SQLConf.COALESCE_PARTITIONS_MIN_PARTITION_NUM.key -> "1",
        SQLConf.SHUFFLE_PARTITIONS.key -> "2") {
        spark.sql("CREATE TABLE t (c1 int) USING PARQUET")
        val (_, adaptive) = runAdaptiveAndVerifyResult("SELECT c1, count(*) FROM t GROUP BY c1")
        assert(
          collect(adaptive) {
            case c @ CustomShuffleReaderExec(_, partitionSpecs) if partitionSpecs.length == 1 =>
              assert(c.hasCoalescedPartition)
              c
          }.length == 1
        )
      }
    }
  }

  test("SPARK-35264: Support AQE side broadcastJoin threshold") {
    withTempView("t1", "t2") {
      def checkJoinStrategy(shouldBroadcast: Boolean): Unit = {
        withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1") {
          val (origin, adaptive) = runAdaptiveAndVerifyResult(
            "SELECT t1.c1, t2.c1 FROM t1 JOIN t2 ON t1.c1 = t2.c1")
          assert(findTopLevelSortMergeJoin(origin).size == 1)
          if (shouldBroadcast) {
            assert(findTopLevelBroadcastHashJoin(adaptive).size == 1)
          } else {
            assert(findTopLevelSortMergeJoin(adaptive).size == 1)
          }
        }
      }

      // t1: 1600 bytes
      // t2: 160 bytes
      spark.sparkContext.parallelize(
        (1 to 100).map(i => TestData(i, i.toString)), 10)
        .toDF("c1", "c2").createOrReplaceTempView("t1")
      spark.sparkContext.parallelize(
        (1 to 10).map(i => TestData(i, i.toString)), 5)
        .toDF("c1", "c2").createOrReplaceTempView("t2")

      checkJoinStrategy(false)
      withSQLConf(SQLConf.ADAPTIVE_AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1") {
        checkJoinStrategy(false)
      }

      withSQLConf(SQLConf.ADAPTIVE_AUTO_BROADCASTJOIN_THRESHOLD.key -> "160") {
        checkJoinStrategy(true)
      }
    }
  }

  test("SPARK-35264: Support AQE side shuffled hash join formula") {
    withTempView("t1", "t2") {
      def checkJoinStrategy(shouldShuffleHashJoin: Boolean): Unit = {
        Seq("100", "100000").foreach { size =>
          withSQLConf(SQLConf.ADVISORY_PARTITION_SIZE_IN_BYTES.key -> size) {
            val (origin1, adaptive1) = runAdaptiveAndVerifyResult(
              "SELECT t1.c1, t2.c1 FROM t1 JOIN t2 ON t1.c1 = t2.c1")
            assert(findTopLevelSortMergeJoin(origin1).size === 1)
            if (shouldShuffleHashJoin && size.toInt < 100000) {
              val shj = findTopLevelShuffledHashJoin(adaptive1)
              assert(shj.size === 1)
              assert(shj.head.buildSide == BuildRight)
            } else {
              assert(findTopLevelSortMergeJoin(adaptive1).size === 1)
            }
          }
        }
        // respect user specified join hint
        val (origin2, adaptive2) = runAdaptiveAndVerifyResult(
          "SELECT /*+ MERGE(t1) */ t1.c1, t2.c1 FROM t1 JOIN t2 ON t1.c1 = t2.c1")
        assert(findTopLevelSortMergeJoin(origin2).size === 1)
        assert(findTopLevelSortMergeJoin(adaptive2).size === 1)
      }

      spark.sparkContext.parallelize(
        (1 to 100).map(i => TestData(i, i.toString)), 10)
        .toDF("c1", "c2").createOrReplaceTempView("t1")
      spark.sparkContext.parallelize(
        (1 to 10).map(i => TestData(i, i.toString)), 5)
        .toDF("c1", "c2").createOrReplaceTempView("t2")

      // t1 partition size: [926, 729, 731]
      // t2 partition size: [318, 120, 0]
      withSQLConf(SQLConf.SHUFFLE_PARTITIONS.key -> "3",
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
        SQLConf.PREFER_SORTMERGEJOIN.key -> "true") {
        // check default value
        checkJoinStrategy(false)
        withSQLConf(SQLConf.ADAPTIVE_MAX_SHUFFLE_HASH_JOIN_LOCAL_MAP_THRESHOLD.key -> "400") {
          checkJoinStrategy(true)
        }
        withSQLConf(SQLConf.ADAPTIVE_MAX_SHUFFLE_HASH_JOIN_LOCAL_MAP_THRESHOLD.key -> "300") {
          checkJoinStrategy(false)
        }
        withSQLConf(SQLConf.ADAPTIVE_MAX_SHUFFLE_HASH_JOIN_LOCAL_MAP_THRESHOLD.key -> "1000") {
          checkJoinStrategy(true)
        }
      }
    }
  }

  test("SPARK-35650: Coalesce number of partitions by AEQ") {
    withSQLConf(SQLConf.COALESCE_PARTITIONS_MIN_PARTITION_NUM.key -> "1") {
      Seq("REPARTITION", "REBALANCE(key)")
        .foreach {repartition =>
          val query = s"SELECT /*+ $repartition */ * FROM testData"
          val (_, adaptivePlan) = runAdaptiveAndVerifyResult(query)
          collect(adaptivePlan) {
            case r: CustomShuffleReaderExec => r
          } match {
            case Seq(customShuffleReader) =>
              assert(customShuffleReader.partitionSpecs.size === 1)
              assert(!customShuffleReader.isLocalReader)
            case _ =>
              fail("There should be a CustomShuffleReaderExec")
          }
        }
    }
  }

  test("SPARK-35650: Use local shuffle reader if can not coalesce number of partitions") {
    withSQLConf(SQLConf.COALESCE_PARTITIONS_ENABLED.key -> "false") {
      val query = "SELECT /*+ REPARTITION */ * FROM testData"
      val (_, adaptivePlan) = runAdaptiveAndVerifyResult(query)
      collect(adaptivePlan) {
        case r: CustomShuffleReaderExec => r
      } match {
        case Seq(customShuffleReader) =>
          assert(customShuffleReader.partitionSpecs.size === 4)
          assert(customShuffleReader.isLocalReader)
        case _ =>
          fail("There should be a CustomShuffleReaderExec")
      }
    }
  }

  test("SPARK-35725: Support optimize skewed partitions in RebalancePartitions") {
    withTempView("v") {
      withSQLConf(
        SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
        SQLConf.COALESCE_PARTITIONS_ENABLED.key -> "true",
        SQLConf.ADAPTIVE_OPTIMIZE_SKEWS_IN_REBALANCE_PARTITIONS_ENABLED.key -> "true",
        SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
        SQLConf.SHUFFLE_PARTITIONS.key -> "5",
        SQLConf.COALESCE_PARTITIONS_MIN_PARTITION_NUM.key -> "1") {

        spark.sparkContext.parallelize(
          (1 to 10).map(i => TestData(if (i > 4) 5 else i, i.toString)), 3)
          .toDF("c1", "c2").createOrReplaceTempView("v")

        def checkPartitionNumber(
            query: String, skewedPartitionNumber: Int, totalNumber: Int): Unit = {
          val (_, adaptive) = runAdaptiveAndVerifyResult(query)
          val reader = collect(adaptive) {
            case reader: CustomShuffleReaderExec => reader
          }
          assert(reader.size == 1)
          assert(reader.head.partitionSpecs.count(_.isInstanceOf[PartialReducerPartitionSpec]) ==
            skewedPartitionNumber)
          assert(reader.head.partitionSpecs.size == totalNumber)
        }

        withSQLConf(SQLConf.ADVISORY_PARTITION_SIZE_IN_BYTES.key -> "150") {
          // partition size [0,258,72,72,72]
          checkPartitionNumber("SELECT /*+ REBALANCE(c1) */ * FROM v", 2, 4)
          // partition size [72,216,216,144,72]
          checkPartitionNumber("SELECT /*+ REBALANCE */ * FROM v", 4, 7)
        }

        // no skewed partition should be optimized
        withSQLConf(SQLConf.ADVISORY_PARTITION_SIZE_IN_BYTES.key -> "10000") {
          checkPartitionNumber("SELECT /*+ REBALANCE(c1) */ * FROM v", 0, 1)
        }
      }
    }
  }

  test("SPARK-35888: join with a 0-partition table") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.COALESCE_PARTITIONS_MIN_PARTITION_NUM.key -> "1",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
      SQLConf.ADAPTIVE_OPTIMIZER_EXCLUDED_RULES.key -> AQEPropagateEmptyRelation.ruleName) {
      withTempView("t2") {
        // create a temp view with 0 partition
        spark.createDataFrame(sparkContext.emptyRDD[Row], new StructType().add("b", IntegerType))
          .createOrReplaceTempView("t2")
        val (_, adaptive) =
          runAdaptiveAndVerifyResult("SELECT * FROM testData2 t1 left semi join t2 ON t1.a=t2.b")
        val customReaders = collect(adaptive) {
          case c: CustomShuffleReaderExec => c
        }
        assert(customReaders.length == 2)
        customReaders.foreach { c =>
          val stats = c.child.asInstanceOf[QueryStageExec].getRuntimeStatistics
          assert(stats.sizeInBytes >= 0)
          assert(stats.rowCount.get >= 0)
        }
      }
    }
  }

  test("SPARK-35968: AQE coalescing should not produce too small partitions by default") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
      val (_, adaptive) =
        runAdaptiveAndVerifyResult("SELECT sum(id) FROM RANGE(10) GROUP BY id % 3")
      val coalesceReader = collect(adaptive) {
        case r: CustomShuffleReaderExec if r.hasCoalescedPartition => r
      }
      assert(coalesceReader.length == 1)
      // RANGE(10) is a very small dataset and AQE coalescing should produce one partition.
      assert(coalesceReader.head.partitionSpecs.length == 1)
    }
  }

  test("SPARK-35794: Allow custom plugin for cost evaluator") {
    CostEvaluator.instantiate(
      classOf[SimpleShuffleSortCostEvaluator].getCanonicalName, spark.sparkContext.getConf)
    intercept[IllegalArgumentException] {
      CostEvaluator.instantiate(
        classOf[InvalidCostEvaluator].getCanonicalName, spark.sparkContext.getConf)
    }

    withSQLConf(
      SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
      SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "80") {
      val query = "SELECT * FROM testData join testData2 ON key = a where value = '1'"

      withSQLConf(SQLConf.ADAPTIVE_CUSTOM_COST_EVALUATOR_CLASS.key ->
        "org.apache.spark.sql.execution.adaptive.SimpleShuffleSortCostEvaluator") {
        val (plan, adaptivePlan) = runAdaptiveAndVerifyResult(query)
        val smj = findTopLevelSortMergeJoin(plan)
        assert(smj.size == 1)
        val bhj = findTopLevelBroadcastHashJoin(adaptivePlan)
        assert(bhj.size == 1)
        checkNumLocalShuffleReaders(adaptivePlan)
      }

      withSQLConf(SQLConf.ADAPTIVE_CUSTOM_COST_EVALUATOR_CLASS.key ->
        "org.apache.spark.sql.execution.adaptive.InvalidCostEvaluator") {
        intercept[IllegalArgumentException] {
          runAdaptiveAndVerifyResult(query)
        }
      }
    }
  }

  test("SPARK-36020: Check logical link in remove redundant projects") {
    withTempView("t") {
      spark.range(10).selectExpr("id % 10 as key", "cast(id * 2 as int) as a",
        "cast(id * 3 as int) as b", "array(id, id + 1, id + 3) as c").createOrReplaceTempView("t")
      withSQLConf(SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
        SQLConf.ADAPTIVE_AUTO_BROADCASTJOIN_THRESHOLD.key -> "800") {
        val query =
          """
            |WITH tt AS (
            | SELECT key, a, b, explode(c) AS c FROM t
            |)
            |SELECT t1.key, t1.c, t2.key, t2.c
            |FROM (SELECT a, b, c, key FROM tt WHERE a > 1) t1
            |JOIN (SELECT a, b, c, key FROM tt) t2
            |  ON t1.key = t2.key
            |""".stripMargin
        val (origin, adaptive) = runAdaptiveAndVerifyResult(query)
        assert(findTopLevelSortMergeJoin(origin).size == 1)
        assert(findTopLevelBroadcastHashJoin(adaptive).size == 1)
      }
    }
  }

  test("SPARK-35874: AQE Shuffle should wait for its subqueries to finish before materializing") {
    withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true") {
      val query = "SELECT b FROM testData2 DISTRIBUTE BY (b, (SELECT max(key) FROM testData))"
      runAdaptiveAndVerifyResult(query)
    }
  }

  test("SPARK-36032: Use inputPlan instead of currentPhysicalPlan to initialize logical link") {
    withTempView("v") {
      spark.sparkContext.parallelize(
        (1 to 10).map(i => TestData(i, i.toString)), 2)
        .toDF("c1", "c2").createOrReplaceTempView("v")

      Seq("-1", "10000").foreach { aqeBhj =>
        withSQLConf(SQLConf.ADAPTIVE_EXECUTION_ENABLED.key -> "true",
          SQLConf.AUTO_BROADCASTJOIN_THRESHOLD.key -> "-1",
          SQLConf.ADAPTIVE_AUTO_BROADCASTJOIN_THRESHOLD.key -> aqeBhj,
          SQLConf.SHUFFLE_PARTITIONS.key -> "1") {
          val (origin, adaptive) = runAdaptiveAndVerifyResult(
            """
              |SELECT * FROM v t1 JOIN (
              | SELECT c1 + 1 as c3 FROM v
              |)t2 ON t1.c1 = t2.c3
              |SORT BY c1
          """.stripMargin)
          if (aqeBhj.toInt < 0) {
            // 1 sort since spark plan has no shuffle for SMJ
            assert(findTopLevelSort(origin).size == 1)
            // 2 sorts in SMJ
            assert(findTopLevelSort(adaptive).size == 2)
          } else {
            assert(findTopLevelSort(origin).size == 1)
            // 1 sort at top node and BHJ has no sort
            assert(findTopLevelSort(adaptive).size == 1)
          }
        }
      }
    }
  }
}

/**
 * Invalid implementation class for [[CostEvaluator]].
 */
private class InvalidCostEvaluator() {}

/**
 * A simple [[CostEvaluator]] to count number of [[ShuffleExchangeLike]] and [[SortExec]].
 */
private case class SimpleShuffleSortCostEvaluator() extends CostEvaluator {
  override def evaluateCost(plan: SparkPlan): Cost = {
    val cost = plan.collect {
      case s: ShuffleExchangeLike => s
      case s: SortExec => s
    }.size
    SimpleCost(cost)
  }
}
