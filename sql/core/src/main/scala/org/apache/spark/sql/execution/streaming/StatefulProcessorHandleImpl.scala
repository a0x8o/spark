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
package org.apache.spark.sql.execution.streaming

import java.util
import java.util.UUID

import org.apache.spark.TaskContext
import org.apache.spark.internal.Logging
import org.apache.spark.sql.Encoder
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.execution.metric.SQLMetric
import org.apache.spark.sql.execution.streaming.state._
import org.apache.spark.sql.streaming.{ListState, MapState, QueryInfo, StatefulProcessorHandle, TimeMode, TTLConfig, ValueState}
import org.apache.spark.util.Utils

/**
 * Object used to assign/retrieve/remove grouping key passed implicitly for various state
 * manipulation actions using the store handle.
 */
object ImplicitGroupingKeyTracker {
  val implicitKey: InheritableThreadLocal[Any] = new InheritableThreadLocal[Any]

  def getImplicitKeyOption: Option[Any] = Option(implicitKey.get())

  def setImplicitKey(key: Any): Unit = implicitKey.set(key)

  def removeImplicitKey(): Unit = implicitKey.remove()
}

/**
 * Enum used to track valid states for the StatefulProcessorHandle
 */
object StatefulProcessorHandleState extends Enumeration {
  type StatefulProcessorHandleState = Value
  val CREATED, INITIALIZED, DATA_PROCESSED, TIMER_PROCESSED, CLOSED = Value
}

class QueryInfoImpl(
    val queryId: UUID,
    val runId: UUID,
    val batchId: Long) extends QueryInfo {

  override def getQueryId: UUID = queryId

  override def getRunId: UUID = runId

  override def getBatchId: Long = batchId

  override def toString: String = {
    s"QueryInfo(queryId=$queryId, runId=$runId, batchId=$batchId)"
  }
}

/**
 * Class that provides a concrete implementation of a StatefulProcessorHandle. Note that we keep
 * track of valid transitions as various functions are invoked to track object lifecycle.
 * @param store - instance of state store
 * @param runId - unique id for the current run
 * @param keyEncoder - encoder for the key
 * @param isStreaming - defines whether the query is streaming or batch
 * @param batchTimestampMs - timestamp for the current batch if available
 * @param metrics - metrics to be updated as part of stateful processing
 */
class StatefulProcessorHandleImpl(
    store: StateStore,
    runId: UUID,
    keyEncoder: ExpressionEncoder[Any],
    timeMode: TimeMode,
    isStreaming: Boolean = true,
    batchTimestampMs: Option[Long] = None,
    metrics: Map[String, SQLMetric] = Map.empty)
  extends StatefulProcessorHandle with Logging {
  import StatefulProcessorHandleState._

  /**
   * Stores all the active ttl states, and is used to cleanup expired values
   * in [[doTtlCleanup()]] function.
   */
  private[sql] val ttlStates: util.List[TTLState] = new util.ArrayList[TTLState]()

  private val BATCH_QUERY_ID = "00000000-0000-0000-0000-000000000000"

  private def buildQueryInfo(): QueryInfo = {
    val taskCtxOpt = Option(TaskContext.get())
    val (queryId, batchId) = if (!isStreaming) {
      (BATCH_QUERY_ID, 0L)
    } else if (taskCtxOpt.isDefined) {
      (taskCtxOpt.get.getLocalProperty(StreamExecution.QUERY_ID_KEY),
        taskCtxOpt.get.getLocalProperty(MicroBatchExecution.BATCH_ID_KEY).toLong)
    } else {
      assert(Utils.isTesting, "Failed to find query id/batch Id in task context")
      (UUID.randomUUID().toString, 0L)
    }

    new QueryInfoImpl(UUID.fromString(queryId), runId, batchId)
  }

  private lazy val currQueryInfo: QueryInfo = buildQueryInfo()

  private var currState: StatefulProcessorHandleState = CREATED

  private def incrementMetric(metricName: String): Unit = {
    metrics.get(metricName).foreach(_.add(1))
  }

  def setHandleState(newState: StatefulProcessorHandleState): Unit = {
    currState = newState
  }

  def getHandleState: StatefulProcessorHandleState = currState

  override def getValueState[T](
      stateName: String,
      valEncoder: Encoder[T]): ValueState[T] = {
    verifyStateVarOperations("get_value_state")
    incrementMetric("numValueStateVars")
    val resultState = new ValueStateImpl[T](store, stateName, keyEncoder, valEncoder)
    resultState
  }

  override def getValueState[T](
      stateName: String,
      valEncoder: Encoder[T],
      ttlConfig: TTLConfig): ValueState[T] = {
    verifyStateVarOperations("get_value_state")
    validateTTLConfig(ttlConfig, stateName)

    assert(batchTimestampMs.isDefined)
    val valueStateWithTTL = new ValueStateImplWithTTL[T](store, stateName,
      keyEncoder, valEncoder, ttlConfig, batchTimestampMs.get)
    incrementMetric("numValueStateWithTTLVars")
    ttlStates.add(valueStateWithTTL)
    valueStateWithTTL
  }

  override def getQueryInfo(): QueryInfo = currQueryInfo

  private lazy val timerState = new TimerStateImpl(store, timeMode, keyEncoder)

  private def verifyStateVarOperations(operationType: String): Unit = {
    if (currState != CREATED) {
      throw StateStoreErrors.cannotPerformOperationWithInvalidHandleState(operationType,
        currState.toString)
    }
  }

  private def verifyTimerOperations(operationType: String): Unit = {
    if (timeMode == NoTime) {
      throw StateStoreErrors.cannotPerformOperationWithInvalidTimeMode(operationType,
        timeMode.toString)
    }

    if (currState < INITIALIZED || currState >= TIMER_PROCESSED) {
      throw StateStoreErrors.cannotPerformOperationWithInvalidHandleState(operationType,
        currState.toString)
    }
  }

  /**
   * Function to register a timer for the given expiryTimestampMs
   * @param expiryTimestampMs - timestamp in milliseconds for the timer to expire
   */
  override def registerTimer(expiryTimestampMs: Long): Unit = {
    verifyTimerOperations("register_timer")
    incrementMetric("numRegisteredTimers")
    timerState.registerTimer(expiryTimestampMs)
  }

  /**
   * Function to delete a timer for the given expiryTimestampMs
   * @param expiryTimestampMs - timestamp in milliseconds for the timer to delete
   */
  override def deleteTimer(expiryTimestampMs: Long): Unit = {
    verifyTimerOperations("delete_timer")
    incrementMetric("numDeletedTimers")
    timerState.deleteTimer(expiryTimestampMs)
  }

  /**
   * Function to retrieve all expired registered timers for all grouping keys
   * @param expiryTimestampMs Threshold for expired timestamp in milliseconds, this function
   *                          will return all timers that have timestamp less than passed threshold
   * @return - iterator of registered timers for all grouping keys
   */
  def getExpiredTimers(expiryTimestampMs: Long): Iterator[(Any, Long)] = {
    verifyTimerOperations("get_expired_timers")
    timerState.getExpiredTimers(expiryTimestampMs)
  }

  /**
   * Function to list all the registered timers for given implicit key
   * Note: calling listTimers() within the `handleInputRows` method of the StatefulProcessor
   * will return all the unprocessed registered timers, including the one being fired within the
   * invocation of `handleInputRows`.
   * @return - iterator of all the registered timers for given implicit key
   */
  def listTimers(): Iterator[Long] = {
    verifyTimerOperations("list_timers")
    timerState.listTimers()
  }

  /**
   * Performs the user state cleanup based on assigned TTl values. Any state
   * which is expired will be cleaned up from StateStore.
   */
  def doTtlCleanup(): Unit = {
    val numValuesRemovedDueToTTLExpiry = metrics.get("numValuesRemovedDueToTTLExpiry").get
    ttlStates.forEach { s =>
      numValuesRemovedDueToTTLExpiry += s.clearExpiredState()
    }
  }

  /**
   * Function to delete and purge state variable if defined previously
   *
   * @param stateName - name of the state variable
   */
  override def deleteIfExists(stateName: String): Unit = {
    verifyStateVarOperations("delete_if_exists")
    if (store.removeColFamilyIfExists(stateName)) {
      incrementMetric("numDeletedStateVars")
    }
  }

  override def getListState[T](stateName: String, valEncoder: Encoder[T]): ListState[T] = {
    verifyStateVarOperations("get_list_state")
    incrementMetric("numListStateVars")
    val resultState = new ListStateImpl[T](store, stateName, keyEncoder, valEncoder)
    resultState
  }

  /**
   * Function to create new or return existing list state variable of given type
   * with ttl. State values will not be returned past ttlDuration, and will be eventually removed
   * from the state store. Any values in listState which have expired after ttlDuration will not
   * returned on get() and will be eventually removed from the state.
   *
   * The user must ensure to call this function only within the `init()` method of the
   * StatefulProcessor.
   *
   * @param stateName  - name of the state variable
   * @param valEncoder - SQL encoder for state variable
   * @param ttlConfig  - the ttl configuration (time to live duration etc.)
   * @tparam T - type of state variable
   * @return - instance of ListState of type T that can be used to store state persistently
   */
  override def getListState[T](
      stateName: String,
      valEncoder: Encoder[T],
      ttlConfig: TTLConfig): ListState[T] = {

    verifyStateVarOperations("get_list_state")
    validateTTLConfig(ttlConfig, stateName)

    assert(batchTimestampMs.isDefined)
    val listStateWithTTL = new ListStateImplWithTTL[T](store, stateName,
      keyEncoder, valEncoder, ttlConfig, batchTimestampMs.get)
    incrementMetric("numListStateWithTTLVars")
    ttlStates.add(listStateWithTTL)

    listStateWithTTL
  }

  override def getMapState[K, V](
      stateName: String,
      userKeyEnc: Encoder[K],
      valEncoder: Encoder[V]): MapState[K, V] = {
    verifyStateVarOperations("get_map_state")
    incrementMetric("numMapStateVars")
    val resultState = new MapStateImpl[K, V](store, stateName, keyEncoder, userKeyEnc, valEncoder)
    resultState
  }

  override def getMapState[K, V](
      stateName: String,
      userKeyEnc: Encoder[K],
      valEncoder: Encoder[V],
      ttlConfig: TTLConfig): MapState[K, V] = {
    verifyStateVarOperations("get_map_state")
    validateTTLConfig(ttlConfig, stateName)

    assert(batchTimestampMs.isDefined)
    val mapStateWithTTL = new MapStateImplWithTTL[K, V](store, stateName, keyEncoder, userKeyEnc,
      valEncoder, ttlConfig, batchTimestampMs.get)
    incrementMetric("numMapStateWithTTLVars")
    ttlStates.add(mapStateWithTTL)

    mapStateWithTTL
  }

  private def validateTTLConfig(ttlConfig: TTLConfig, stateName: String): Unit = {
    val ttlDuration = ttlConfig.ttlDuration
    if (timeMode != TimeMode.ProcessingTime()) {
      throw StateStoreErrors.cannotProvideTTLConfigForTimeMode(stateName, timeMode.toString)
    } else if (ttlDuration == null || ttlDuration.isNegative || ttlDuration.isZero) {
      throw StateStoreErrors.ttlMustBePositive("update", stateName)
    }
  }
}
