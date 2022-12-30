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

package org.apache.spark.sql.catalyst.util

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.analysis.Analyzer
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.optimizer.ConstantFolding
import org.apache.spark.sql.catalyst.parser.{CatalystSqlParser, ParseException}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._

/**
 * This object contains fields to help process DEFAULT columns.
 */
object ResolveDefaultColumns {
  // This column metadata indicates the default value associated with a particular table column that
  // is in effect at any given time. Its value begins at the time of the initial CREATE/REPLACE
  // TABLE statement with DEFAULT column definition(s), if any. It then changes whenever an ALTER
  // TABLE statement SETs the DEFAULT. The intent is for this "current default" to be used by
  // UPDATE, INSERT and MERGE, which evaluate each default expression for each row.
  val CURRENT_DEFAULT_COLUMN_METADATA_KEY = "CURRENT_DEFAULT"
  // This column metadata represents the default value for all existing rows in a table after a
  // column has been added. This value is determined at time of CREATE TABLE, REPLACE TABLE, or
  // ALTER TABLE ADD COLUMN, and never changes thereafter. The intent is for this "exist default" to
  // be used by any scan when the columns in the source row are missing data. For example, consider
  // the following sequence:
  // CREATE TABLE t (c1 INT)
  // INSERT INTO t VALUES (42)
  // ALTER TABLE t ADD COLUMNS (c2 INT DEFAULT 43)
  // SELECT c1, c2 FROM t
  // In this case, the final query is expected to return 42, 43. The ALTER TABLE ADD COLUMNS command
  // executed after there was already data in the table, so in order to enforce this invariant, we
  // need either (1) an expensive backfill of value 43 at column c2 into all previous rows, or (2)
  // indicate to each data source that selected columns missing data are to generate the
  // corresponding DEFAULT value instead. We choose option (2) for efficiency, and represent this
  // value as the text representation of a folded constant in the "EXISTS_DEFAULT" column metadata.
  val EXISTS_DEFAULT_COLUMN_METADATA_KEY = "EXISTS_DEFAULT"
  // Name of attributes representing explicit references to the value stored in the above
  // CURRENT_DEFAULT_COLUMN_METADATA.
  val CURRENT_DEFAULT_COLUMN_NAME = "DEFAULT"
  // Return a more descriptive error message if the user tries to nest the DEFAULT column reference
  // inside some other expression, such as DEFAULT + 1 (this is not allowed).
  val DEFAULTS_IN_EXPRESSIONS_ERROR = "Failed to execute INSERT INTO command because the " +
    "VALUES list contains a DEFAULT column reference as part of another expression; this is " +
    "not allowed"

  /**
   * Finds "current default" expressions in CREATE/REPLACE TABLE columns and constant-folds them.
   *
   * The results are stored in the "exists default" metadata of the same columns. For example, in
   * the event of this statement:
   *
   * CREATE TABLE T(a INT, b INT DEFAULT 5 + 5)
   *
   * This method constant-folds the "current default" value, stored in the CURRENT_DEFAULT metadata
   * of the "b" column, to "10", storing the result in the "exists default" value within the
   * EXISTS_DEFAULT metadata of that same column. Meanwhile the "current default" metadata of this
   * "b" column retains its original value of "5 + 5".
   *
   * The reason for constant-folding the EXISTS_DEFAULT is to make the end-user visible behavior the
   * same, after executing an ALTER TABLE ADD COLUMNS command with DEFAULT value, as if the system
   * had performed an exhaustive backfill of the provided value to all previously existing rows in
   * the table instead. We choose to avoid doing such a backfill because it would be a
   * time-consuming and costly operation. Instead, we elect to store the EXISTS_DEFAULT in the
   * column metadata for future reference when querying data out of the data source. In turn, each
   * data source then takes responsibility to provide the constant-folded value in the
   * EXISTS_DEFAULT metadata for such columns where the value is not present in storage.
   *
   * @param analyzer      used for analyzing the result of parsing the expression stored as text.
   * @param tableSchema   represents the names and types of the columns of the statement to process.
   * @param statementType name of the statement being processed, such as INSERT; useful for errors.
   * @return a copy of `tableSchema` with field metadata updated with the constant-folded values.
   */
  def constantFoldCurrentDefaultsToExistDefaults(
      analyzer: Analyzer,
      tableSchema: StructType,
      statementType: String): StructType = {
    if (SQLConf.get.enableDefaultColumns) {
      val newFields: Seq[StructField] = tableSchema.fields.map { field =>
        if (field.metadata.contains(CURRENT_DEFAULT_COLUMN_METADATA_KEY)) {
          val analyzed: Expression = analyze(analyzer, field, statementType)
          val newMetadata: Metadata = new MetadataBuilder().withMetadata(field.metadata)
            .putString(EXISTS_DEFAULT_COLUMN_METADATA_KEY, analyzed.sql).build()
          field.copy(metadata = newMetadata)
        } else {
          field
        }
      }
      StructType(newFields)
    } else {
      tableSchema
    }
  }

  /**
   * Parses and analyzes the DEFAULT column text in `field`, returning an error upon failure.
   *
   * @param field         represents the DEFAULT column value whose "default" metadata to parse
   *                      and analyze.
   * @param statementType which type of statement we are running, such as INSERT; useful for errors.
   * @return Result of the analysis and constant-folding operation.
   */
  def analyze(
      analyzer: Analyzer,
      field: StructField,
      statementType: String): Expression = {
    // Parse the expression.
    val colText: String = field.metadata.getString(CURRENT_DEFAULT_COLUMN_METADATA_KEY)
    lazy val parser = new CatalystSqlParser()
    val parsed: Expression = try {
      parser.parseExpression(colText)
    } catch {
      case ex: ParseException =>
        throw new AnalysisException(
          s"Failed to execute $statementType command because the destination table column " +
            s"${field.name} has a DEFAULT value of $colText which fails to parse as a valid " +
            s"expression: ${ex.getMessage}")
    }
    // Analyze the parse result.
    val plan = try {
      val analyzed = analyzer.execute(Project(Seq(Alias(parsed, field.name)()), OneRowRelation()))
      analyzer.checkAnalysis(analyzed)
      ConstantFolding(analyzed)
    } catch {
      case ex: AnalysisException =>
        throw new AnalysisException(
          s"Failed to execute $statementType command because the destination table column " +
            s"${field.name} has a DEFAULT value of $colText which fails to resolve as a valid " +
            s"expression: ${ex.getMessage}")
    }
    val analyzed: Expression = plan.collectFirst {
      case Project(Seq(a: Alias), OneRowRelation()) => a.child
    }.get
    // Perform implicit coercion from the provided expression type to the required column type.
    if (field.dataType == analyzed.dataType) {
      analyzed
    } else if (Cast.canUpCast(analyzed.dataType, field.dataType)) {
      Cast(analyzed, field.dataType)
    } else {
      throw new AnalysisException(
        s"Failed to execute $statementType command because the destination table column " +
          s"${field.name} has a DEFAULT value with type ${field.dataType}, but the " +
          s"statement provided a value of incompatible type ${analyzed.dataType}")
    }
  }
}
