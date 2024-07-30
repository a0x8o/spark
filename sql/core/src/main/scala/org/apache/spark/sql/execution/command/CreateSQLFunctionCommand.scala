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

package org.apache.spark.sql.execution.command

import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.catalyst.FunctionIdentifier
import org.apache.spark.sql.catalyst.catalog.SQLFunction

/**
 * The DDL command that creates a SQL function.
 * For example:
 * {{{
 *    CREATE [OR REPLACE] [TEMPORARY] FUNCTION [IF NOT EXISTS] [db_name.]function_name
 *    ([param_name param_type [COMMENT param_comment], ...])
 *    RETURNS {ret_type | TABLE (ret_name ret_type [COMMENT ret_comment], ...])}
 *    [function_properties] function_body;
 *
 *    function_properties:
 *      [NOT] DETERMINISTIC | COMMENT function_comment | [ CONTAINS SQL | READS SQL DATA ]
 *
 *    function_body:
 *      RETURN {expression | TABLE ( query )}
 * }}}
 */
case class CreateSQLFunctionCommand(
    name: FunctionIdentifier,
    inputParamText: Option[String],
    returnTypeText: String,
    exprText: Option[String],
    queryText: Option[String],
    comment: Option[String],
    isDeterministic: Option[Boolean],
    containsSQL: Option[Boolean],
    isTableFunc: Boolean,
    isTemp: Boolean,
    ignoreIfExists: Boolean,
    replace: Boolean)
    extends CreateUserDefinedFunctionCommand {

  override def run(sparkSession: SparkSession): Seq[Row] = {
    import SQLFunction._

    val parser = sparkSession.sessionState.sqlParser

    val inputParam = inputParamText.map(parser.parseTableSchema)
    val returnType = parseReturnTypeText(returnTypeText, isTableFunc, parser)

    val function = SQLFunction(
      name,
      inputParam,
      returnType.getOrElse(if (isTableFunc) Right(null) else Left(null)),
      exprText,
      queryText,
      comment,
      isDeterministic,
      containsSQL,
      isTableFunc,
      Map.empty)

    // TODO: Implement the rest of the method.

    Seq.empty
  }
}
