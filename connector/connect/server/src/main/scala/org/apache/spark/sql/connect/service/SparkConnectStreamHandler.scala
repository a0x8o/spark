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

package org.apache.spark.sql.connect.service

import scala.collection.JavaConverters._

import com.google.protobuf.ByteString
import io.grpc.stub.StreamObserver

import org.apache.spark.SparkEnv
import org.apache.spark.connect.proto
import org.apache.spark.connect.proto.{ExecutePlanRequest, ExecutePlanResponse}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{DataFrame, Dataset, SparkSession}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.connect.config.Connect.CONNECT_GRPC_ARROW_MAX_BATCH_SIZE
import org.apache.spark.sql.connect.planner.SparkConnectPlanner
import org.apache.spark.sql.execution.{SparkPlan, SQLExecution}
import org.apache.spark.sql.execution.adaptive.{AdaptiveSparkPlanExec, AdaptiveSparkPlanHelper, QueryStageExec}
import org.apache.spark.sql.execution.arrow.ArrowConverters
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.ThreadUtils

class SparkConnectStreamHandler(responseObserver: StreamObserver[ExecutePlanResponse])
    extends Logging {

  def handle(v: ExecutePlanRequest): Unit = {
    val session =
      SparkConnectService
        .getOrCreateIsolatedSession(v.getUserContext.getUserId, v.getClientId)
        .session
    v.getPlan.getOpTypeCase match {
      case proto.Plan.OpTypeCase.COMMAND => handleCommand(session, v)
      case proto.Plan.OpTypeCase.ROOT => handlePlan(session, v)
      case _ =>
        throw new UnsupportedOperationException(s"${v.getPlan.getOpTypeCase} not supported.")
    }
  }

  private def handlePlan(session: SparkSession, request: ExecutePlanRequest): Unit = {
    // Extract the plan from the request and convert it to a logical plan
    val planner = new SparkConnectPlanner(session)
    val dataframe = Dataset.ofRows(session, planner.transformRelation(request.getPlan.getRoot))
    processAsArrowBatches(request.getClientId, dataframe)
  }

  private def processAsArrowBatches(clientId: String, dataframe: DataFrame): Unit = {
    val spark = dataframe.sparkSession
    val schema = dataframe.schema
    val maxRecordsPerBatch = spark.sessionState.conf.arrowMaxRecordsPerBatch
    val timeZoneId = spark.sessionState.conf.sessionLocalTimeZone
    // Conservatively sets it 70% because the size is not accurate but estimated.
    val maxBatchSize = (SparkEnv.get.conf.get(CONNECT_GRPC_ARROW_MAX_BATCH_SIZE) * 0.7).toLong

    SQLExecution.withNewExecutionId(dataframe.queryExecution, Some("collectArrow")) {
      val rows = dataframe.queryExecution.executedPlan.execute()
      val numPartitions = rows.getNumPartitions
      var numSent = 0

      if (numPartitions > 0) {
        type Batch = (Array[Byte], Long)

        val batches = rows.mapPartitionsInternal(
          SparkConnectStreamHandler
            .rowToArrowConverter(schema, maxRecordsPerBatch, maxBatchSize, timeZoneId))

        val signal = new Object
        val partitions = new Array[Array[Batch]](numPartitions)
        var error: Option[Throwable] = None

        // This callback is executed by the DAGScheduler thread.
        // After fetching a partition, it inserts the partition into the Map, and then
        // wakes up the main thread.
        val resultHandler = (partitionId: Int, partition: Array[Batch]) => {
          signal.synchronized {
            partitions(partitionId) = partition
            signal.notify()
          }
          ()
        }

        val future = spark.sparkContext.submitJob(
          rdd = batches,
          processPartition = (iter: Iterator[Batch]) => iter.toArray,
          partitions = Seq.range(0, numPartitions),
          resultHandler = resultHandler,
          resultFunc = () => ())

        // Collect errors and propagate them to the main thread.
        future.onComplete { result =>
          result.failed.foreach { throwable =>
            signal.synchronized {
              error = Some(throwable)
              signal.notify()
            }
          }
        }(ThreadUtils.sameThread)

        // The main thread will wait until 0-th partition is available,
        // then send it to client and wait for the next partition.
        // Different from the implementation of [[Dataset#collectAsArrowToPython]], it sends
        // the arrow batches in main thread to avoid DAGScheduler thread been blocked for
        // tasks not related to scheduling. This is particularly important if there are
        // multiple users or clients running code at the same time.
        var currentPartitionId = 0
        while (currentPartitionId < numPartitions) {
          val partition = signal.synchronized {
            var part = partitions(currentPartitionId)
            while (part == null && error.isEmpty) {
              signal.wait()
              part = partitions(currentPartitionId)
            }
            partitions(currentPartitionId) = null

            error.foreach { case other =>
              throw other
            }
            part
          }

          partition.foreach { case (bytes, count) =>
            val response = proto.ExecutePlanResponse.newBuilder().setClientId(clientId)
            val batch = proto.ExecutePlanResponse.ArrowBatch
              .newBuilder()
              .setRowCount(count)
              .setData(ByteString.copyFrom(bytes))
              .build()
            response.setArrowBatch(batch)
            responseObserver.onNext(response.build())
            numSent += 1
          }

          currentPartitionId += 1
        }
      }

      // Make sure at least 1 batch will be sent.
      if (numSent == 0) {
        val bytes = ArrowConverters.createEmptyArrowBatch(schema, timeZoneId)
        val response = proto.ExecutePlanResponse.newBuilder().setClientId(clientId)
        val batch = proto.ExecutePlanResponse.ArrowBatch
          .newBuilder()
          .setRowCount(0L)
          .setData(ByteString.copyFrom(bytes))
          .build()
        response.setArrowBatch(batch)
        responseObserver.onNext(response.build())
      }

      responseObserver.onNext(sendMetricsToResponse(clientId, dataframe))
      responseObserver.onCompleted()
    }
  }

  private def sendMetricsToResponse(clientId: String, rows: DataFrame): ExecutePlanResponse = {
    // Send a last batch with the metrics
    ExecutePlanResponse
      .newBuilder()
      .setClientId(clientId)
      .setMetrics(MetricGenerator.buildMetrics(rows.queryExecution.executedPlan))
      .build()
  }

  private def handleCommand(session: SparkSession, request: ExecutePlanRequest): Unit = {
    val command = request.getPlan.getCommand
    val planner = new SparkConnectPlanner(session)
    planner.process(command)
    responseObserver.onCompleted()
  }
}

object SparkConnectStreamHandler {
  type Batch = (Array[Byte], Long)

  private def rowToArrowConverter(
      schema: StructType,
      maxRecordsPerBatch: Int,
      maxBatchSize: Long,
      timeZoneId: String): Iterator[InternalRow] => Iterator[Batch] = { rows =>
    val batches = ArrowConverters.toBatchWithSchemaIterator(
      rows,
      schema,
      maxRecordsPerBatch,
      maxBatchSize,
      timeZoneId)
    batches.map(b => b -> batches.rowCountInLastBatch)
  }
}

object MetricGenerator extends AdaptiveSparkPlanHelper {
  def buildMetrics(p: SparkPlan): ExecutePlanResponse.Metrics = {
    val b = ExecutePlanResponse.Metrics.newBuilder
    b.addAllMetrics(transformPlan(p, p.id).asJava)
    b.build()
  }

  private def transformChildren(p: SparkPlan): Seq[ExecutePlanResponse.Metrics.MetricObject] = {
    allChildren(p).flatMap(c => transformPlan(c, p.id))
  }

  private def allChildren(p: SparkPlan): Seq[SparkPlan] = p match {
    case a: AdaptiveSparkPlanExec => Seq(a.executedPlan)
    case s: QueryStageExec => Seq(s.plan)
    case _ => p.children
  }

  private def transformPlan(
      p: SparkPlan,
      parentId: Int): Seq[ExecutePlanResponse.Metrics.MetricObject] = {
    val mv = p.metrics.map(m =>
      m._1 -> ExecutePlanResponse.Metrics.MetricValue.newBuilder
        .setName(m._2.name.getOrElse(""))
        .setValue(m._2.value)
        .setMetricType(m._2.metricType)
        .build())
    val mo = ExecutePlanResponse.Metrics.MetricObject
      .newBuilder()
      .setName(p.nodeName)
      .setPlanId(p.id)
      .putAllExecutionMetrics(mv.asJava)
      .build()
    Seq(mo) ++ transformChildren(p)
  }

}
