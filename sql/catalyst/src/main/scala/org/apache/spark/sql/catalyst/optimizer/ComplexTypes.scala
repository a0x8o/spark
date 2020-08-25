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
import org.apache.spark.sql.catalyst.plans.logical.{Aggregate, LogicalPlan}
import org.apache.spark.sql.catalyst.rules.Rule

/**
 * Simplify redundant [[CreateNamedStruct]], [[CreateArray]] and [[CreateMap]] expressions.
 */
object SimplifyExtractValueOps extends Rule[LogicalPlan] {
  override def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    // One place where this optimization is invalid is an aggregation where the select
    // list expression is a function of a grouping expression:
    //
    // SELECT struct(a,b).a FROM tbl GROUP BY struct(a,b)
    //
    // cannot be simplified to SELECT a FROM tbl GROUP BY struct(a,b). So just skip this
    // optimization for Aggregates (although this misses some cases where the optimization
    // can be made).
    case a: Aggregate => a
    case p => p.transformExpressionsUp {
      // Remove redundant field extraction.
      case GetStructField(createNamedStruct: CreateNamedStruct, ordinal, _) =>
        createNamedStruct.valExprs(ordinal)
      case GetStructField(w @ WithFields(struct, names, valExprs), ordinal, maybeName) =>
        val name = w.dataType(ordinal).name
        val matches = names.zip(valExprs).filter(_._1 == name)
        if (matches.nonEmpty) {
          // return last matching element as that is the final value for the field being extracted.
          // For example, if a user submits a query like this:
          // `$"struct_col".withField("b", lit(1)).withField("b", lit(2)).getField("b")`
          // we want to return `lit(2)` (and not `lit(1)`).
          val expr = matches.last._2
          If(IsNull(struct), Literal(null, expr.dataType), expr)
        } else {
          GetStructField(struct, ordinal, maybeName)
        }
      // Remove redundant array indexing.
      case GetArrayStructFields(CreateArray(elems, useStringTypeWhenEmpty), field, ordinal, _, _) =>
        // Instead of selecting the field on the entire array, select it from each member
        // of the array. Pushing down the operation this way may open other optimizations
        // opportunities (i.e. struct(...,x,...).x)
        CreateArray(elems.map(GetStructField(_, ordinal, Some(field.name))), useStringTypeWhenEmpty)

      // Remove redundant map lookup.
      case ga @ GetArrayItem(CreateArray(elems, _), IntegerLiteral(idx)) =>
        // Instead of creating the array and then selecting one row, remove array creation
        // altogether.
        if (idx >= 0 && idx < elems.size) {
          // valid index
          elems(idx)
        } else {
          // out of bounds, mimic the runtime behavior and return null
          Literal(null, ga.dataType)
        }
      case GetMapValue(CreateMap(elems, _), key) => CaseKeyWhen(key, elems)
    }
  }
}
