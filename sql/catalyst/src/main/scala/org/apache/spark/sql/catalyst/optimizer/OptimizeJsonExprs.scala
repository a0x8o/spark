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

package org.apache.spark.sql.catalyst.optimizer

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.Rule

/**
 * Simplify redundant json related expressions.
 */
object OptimizeJsonExprs extends Rule[LogicalPlan] {
  override def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case p => p.transformExpressions {
      case jsonToStructs @ JsonToStructs(_, options1,
        StructsToJson(options2, child, timeZoneId2), timeZoneId1)
          if options1.isEmpty && options2.isEmpty && timeZoneId1 == timeZoneId2 &&
            jsonToStructs.dataType == child.dataType =>
        // `StructsToJson` only fails when `JacksonGenerator` encounters data types it
        // cannot convert to JSON. But `StructsToJson.checkInputDataTypes` already
        // verifies its child's data types is convertible to JSON. But in
        // `StructsToJson(JsonToStructs(...))` case, we cannot verify input json string
        // so `JsonToStructs` might throw error in runtime. Thus we cannot optimize
        // this case similarly.
        child
    }
  }
}
