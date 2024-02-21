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
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.sql.execution.streaming.StateKeyValueRowSchema.{KEY_ROW_SCHEMA, VALUE_ROW_SCHEMA}
import org.apache.spark.sql.execution.streaming.state.StateStore
import org.apache.spark.sql.streaming.ValueState

/**
 * Class that provides a concrete implementation for a single value state associated with state
 * variables used in the streaming transformWithState operator.
 * @param store - reference to the StateStore instance to be used for storing state
 * @param stateName - name of logical state partition
 * @param keyEnc - Spark SQL encoder for key
 * @tparam S - data type of object that will be stored
 */
class ValueStateImpl[S](
    store: StateStore,
    stateName: String,
    keyExprEnc: ExpressionEncoder[Any]) extends ValueState[S] with Logging {

  private val keySerializer = keyExprEnc.createSerializer()

  private val stateTypesEncoder = StateTypesEncoder(keySerializer, stateName)

  store.createColFamilyIfAbsent(stateName, KEY_ROW_SCHEMA, numColsPrefixKey = 0,
    VALUE_ROW_SCHEMA)

  /** Function to check if state exists. Returns true if present and false otherwise */
  override def exists(): Boolean = {
    getImpl() != null
  }

  /** Function to return Option of value if exists and None otherwise */
  override def getOption(): Option[S] = {
    Option(get())
  }

  /** Function to return associated value with key if exists and null otherwise */
  override def get(): S = {
    val retRow = getImpl()
    if (retRow != null) {
      stateTypesEncoder.decodeValue[S](retRow)
    } else {
      null.asInstanceOf[S]
    }
  }

  private def getImpl(): UnsafeRow = {
    store.get(stateTypesEncoder.encodeGroupingKey(), stateName)
  }

  /** Function to update and overwrite state associated with given key */
  override def update(newState: S): Unit = {
    store.put(stateTypesEncoder.encodeGroupingKey(),
      stateTypesEncoder.encodeValue(newState), stateName)
  }

  /** Function to remove state for given key */
  override def clear(): Unit = {
    store.remove(stateTypesEncoder.encodeGroupingKey(), stateName)
  }
}
