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

import org.apache.spark.internal.Logging
import org.apache.spark.sql.Encoder
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.execution.streaming.TransformWithStateKeyValueRowSchema.{COMPOSITE_KEY_ROW_SCHEMA, VALUE_ROW_SCHEMA_WITH_TTL}
import org.apache.spark.sql.execution.streaming.state.{PrefixKeyScanStateEncoderSpec, StateStore, StateStoreErrors}
import org.apache.spark.sql.streaming.{MapState, TTLConfig}
import org.apache.spark.util.NextIterator

/**
 * Class that provides a concrete implementation for map state associated with state
 * variables (with ttl expiration support) used in the streaming transformWithState operator.
 * @param store - reference to the StateStore instance to be used for storing state
 * @param stateName  - name of the state variable
 * @param keyExprEnc - Spark SQL encoder for key
 * @param userKeyEnc  - Spark SQL encoder for the map key
 * @param valEncoder - SQL encoder for state variable
 * @param ttlConfig  - the ttl configuration (time to live duration etc.)
 * @param batchTimestampMs - current batch processing timestamp.
 * @tparam K - type of key for map state variable
 * @tparam V - type of value for map state variable
 * @return - instance of MapState of type [K,V] that can be used to store state persistently
 */
class MapStateImplWithTTL[K, V](
    store: StateStore,
    stateName: String,
    keyExprEnc: ExpressionEncoder[Any],
    userKeyEnc: Encoder[K],
    valEncoder: Encoder[V],
    ttlConfig: TTLConfig,
    batchTimestampMs: Long) extends CompositeKeyTTLStateImpl(stateName, store, batchTimestampMs)
  with MapState[K, V] with Logging {

  private val keySerializer = keyExprEnc.createSerializer()
  private val stateTypesEncoder = new CompositeKeyStateEncoder(
    keySerializer, userKeyEnc, valEncoder, COMPOSITE_KEY_ROW_SCHEMA, stateName, hasTtl = true)

  private val ttlExpirationMs =
    StateTTL.calculateExpirationTimeForDuration(ttlConfig.ttlDuration, batchTimestampMs)

  initialize()

  private def initialize(): Unit = {
    store.createColFamilyIfAbsent(stateName, COMPOSITE_KEY_ROW_SCHEMA, VALUE_ROW_SCHEMA_WITH_TTL,
      PrefixKeyScanStateEncoderSpec(COMPOSITE_KEY_ROW_SCHEMA, 1))
  }

  /** Whether state exists or not. */
  override def exists(): Boolean = {
    iterator().nonEmpty
  }

  /** Get the state value if it exists */
  override def getValue(key: K): V = {
    StateStoreErrors.requireNonNullStateValue(key, stateName)
    val encodedCompositeKey = stateTypesEncoder.encodeCompositeKey(key)
    val retRow = store.get(encodedCompositeKey, stateName)

    if (retRow != null) {
      if (!stateTypesEncoder.isExpired(retRow, batchTimestampMs)) {
        stateTypesEncoder.decodeValue(retRow)
      } else {
        null.asInstanceOf[V]
      }
    } else {
      null.asInstanceOf[V]
    }
  }

  /** Check if the user key is contained in the map */
  override def containsKey(key: K): Boolean = {
    StateStoreErrors.requireNonNullStateValue(key, stateName)
    getValue(key) != null
  }

  /** Update value for given user key */
  override def updateValue(key: K, value: V): Unit = {
    StateStoreErrors.requireNonNullStateValue(key, stateName)
    StateStoreErrors.requireNonNullStateValue(value, stateName)

    val serializedGroupingKey = stateTypesEncoder.serializeGroupingKey()
    val serializedUserKey = stateTypesEncoder.serializeUserKey(key)

    val encodedValue = stateTypesEncoder.encodeValue(value, ttlExpirationMs)
    val encodedCompositeKey = stateTypesEncoder.encodeCompositeKey(
      serializedGroupingKey, serializedUserKey)
    store.put(encodedCompositeKey, encodedValue, stateName)

    upsertTTLForStateKey(ttlExpirationMs, serializedGroupingKey, serializedUserKey)
  }

  /** Get the map associated with grouping key */
  override def iterator(): Iterator[(K, V)] = {
    val encodedGroupingKey = stateTypesEncoder.encodeGroupingKey()
    val unsafeRowPairIterator = store.prefixScan(encodedGroupingKey, stateName)
    new NextIterator[(K, V)] {
      override protected def getNext(): (K, V) = {
        val iter = unsafeRowPairIterator.dropWhile { rowPair =>
          stateTypesEncoder.isExpired(rowPair.value, batchTimestampMs)
        }
        if (iter.hasNext) {
          val currentRowPair = iter.next()
          val key = stateTypesEncoder.decodeCompositeKey(currentRowPair.key)
          val value = stateTypesEncoder.decodeValue(currentRowPair.value)
          (key, value)
        } else {
          finished = true
          null.asInstanceOf[(K, V)]
        }
      }

      override protected def close(): Unit = {}
    }
  }

  /** Get the list of keys present in map associated with grouping key */
  override def keys(): Iterator[K] = {
    iterator().map(_._1)
  }

  /** Get the list of values present in map associated with grouping key */
  override def values(): Iterator[V] = {
    iterator().map(_._2)
  }

  /** Remove user key from map state */
  override def removeKey(key: K): Unit = {
    StateStoreErrors.requireNonNullStateValue(key, stateName)
    val compositeKey = stateTypesEncoder.encodeCompositeKey(key)
    store.remove(compositeKey, stateName)
  }

  /** Remove this state. */
  override def clear(): Unit = {
    keys().foreach { itr =>
      removeKey(itr)
    }
    clearTTLState()
  }

  /**
   * Clears the user state associated with this grouping key
   * if it has expired. This function is called by Spark to perform
   * cleanup at the end of transformWithState processing.
   *
   * Spark uses a secondary index to determine if the user state for
   * this grouping key has expired. However, its possible that the user
   * has updated the TTL and secondary index is out of date. Implementations
   * must validate that the user State has actually expired before cleanup based
   * on their own State data.
   *
   * @param groupingKey grouping key for which cleanup should be performed.
   * @param userKey     user key for which cleanup should be performed.
   */
  override def clearIfExpired(groupingKey: Array[Byte], userKey: Array[Byte]): Long = {
    val encodedCompositeKey = stateTypesEncoder.encodeCompositeKey(groupingKey, userKey)
    val retRow = store.get(encodedCompositeKey, stateName)
    var numRemovedElements = 0L
    if (retRow != null) {
      if (stateTypesEncoder.isExpired(retRow, batchTimestampMs)) {
        store.remove(encodedCompositeKey, stateName)
        numRemovedElements += 1
      }
    }
    numRemovedElements
  }

  /*
   * Internal methods to probe state for testing. The below methods exist for unit tests
   * to read the state ttl values, and ensure that values are persisted correctly in
   * the underlying state store.
   */

  /**
   * Retrieves the value from State even if its expired. This method is used
   * in tests to read the state store value, and ensure if its cleaned up at the
   * end of the micro-batch.
   */
  private[sql] def getWithoutEnforcingTTL(userKey: K): Option[V] = {
    val encodedCompositeKey = stateTypesEncoder.encodeCompositeKey(userKey)
    val retRow = store.get(encodedCompositeKey, stateName)

    if (retRow != null) {
      val resState = stateTypesEncoder.decodeValue(retRow)
      Some(resState)
    } else {
      None
    }
  }

  /**
   * Read the ttl value associated with the grouping and user key.
   */
  private[sql] def getTTLValue(userKey: K): Option[(V, Long)] = {
    val encodedCompositeKey = stateTypesEncoder.encodeCompositeKey(userKey)
    val retRow = store.get(encodedCompositeKey, stateName)

    // if the returned row is not null, we want to return the value associated with the
    // ttlExpiration
    Option(retRow).flatMap { row =>
      val ttlExpiration = stateTypesEncoder.decodeTtlExpirationMs(row)
      ttlExpiration.map(expiration => (stateTypesEncoder.decodeValue(row), expiration))
    }
  }

  /**
   * Get all ttl values stored in ttl state for current implicit
   * grouping key.
   */
  private[sql] def getKeyValuesInTTLState(): Iterator[(K, Long)] = {
    val ttlIterator = ttlIndexIterator()
    val implicitGroupingKey = stateTypesEncoder.serializeGroupingKey()
    var nextValue: Option[(K, Long)] = None

    new Iterator[(K, Long)] {
      override def hasNext: Boolean = {
        while (nextValue.isEmpty && ttlIterator.hasNext) {
          val nextTtlValue = ttlIterator.next()
          val groupingKey = nextTtlValue.groupingKey
          if (groupingKey sameElements implicitGroupingKey) {
            val userKey = stateTypesEncoder.decodeUserKeyFromTTLRow(nextTtlValue)
            nextValue = Some(userKey, nextTtlValue.expirationMs)
          }
        }
        nextValue.isDefined
      }

      override def next(): (K, Long) = {
        val result = nextValue.get
        nextValue = None
        result
      }
    }
  }
}
