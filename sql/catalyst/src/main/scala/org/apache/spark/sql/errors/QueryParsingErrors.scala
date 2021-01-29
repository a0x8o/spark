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

package org.apache.spark.sql.errors

import org.antlr.v4.runtime.ParserRuleContext

import org.apache.spark.sql.catalyst.parser.ParseException
import org.apache.spark.sql.catalyst.parser.SqlBaseParser._
import org.apache.spark.sql.catalyst.trees.Origin

/**
 * Object for grouping all error messages of the query parsing.
 * Currently it includes all ParseException.
 */
object QueryParsingErrors {

  def invalidInsertIntoError(ctx: InsertIntoContext): Throwable = {
    new ParseException("Invalid InsertIntoContext", ctx)
  }

  def insertOverwriteDirectoryUnsupportedError(ctx: InsertIntoContext): Throwable = {
    new ParseException("INSERT OVERWRITE DIRECTORY is not supported", ctx)
  }

  def columnAliasInOperationNotAllowedError(op: String, ctx: TableAliasContext): Throwable = {
    new ParseException(s"Columns aliases are not allowed in $op.", ctx.identifierList())
  }

  def emptySourceForMergeError(ctx: MergeIntoTableContext): Throwable = {
    new ParseException("Empty source for merge: you should specify a source" +
      " table/subquery in merge.", ctx.source)
  }

  def unrecognizedMatchedActionError(ctx: MatchedClauseContext): Throwable = {
    new ParseException(s"Unrecognized matched action: ${ctx.matchedAction().getText}",
      ctx.matchedAction())
  }

  def insertedValueNumberNotMatchFieldNumberError(ctx: NotMatchedClauseContext): Throwable = {
    new ParseException("The number of inserted values cannot match the fields.",
      ctx.notMatchedAction())
  }

  def unrecognizedNotMatchedActionError(ctx: NotMatchedClauseContext): Throwable = {
    new ParseException(s"Unrecognized not matched action: ${ctx.notMatchedAction().getText}",
      ctx.notMatchedAction())
  }

  def mergeStatementWithoutWhenClauseError(ctx: MergeIntoTableContext): Throwable = {
    new ParseException("There must be at least one WHEN clause in a MERGE statement", ctx)
  }

  def nonLastMatchedClauseOmitConditionError(ctx: MergeIntoTableContext): Throwable = {
    new ParseException("When there are more than one MATCHED clauses in a MERGE " +
      "statement, only the last MATCHED clause can omit the condition.", ctx)
  }

  def nonLastNotMatchedClauseOmitConditionError(ctx: MergeIntoTableContext): Throwable = {
    new ParseException("When there are more than one NOT MATCHED clauses in a MERGE " +
      "statement, only the last NOT MATCHED clause can omit the condition.", ctx)
  }

  def emptyPartitionKeyError(key: String, ctx: PartitionSpecContext): Throwable = {
    new ParseException(s"Found an empty partition key '$key'.", ctx)
  }

  def combinationQueryResultClausesUnsupportedError(ctx: QueryOrganizationContext): Throwable = {
    new ParseException(
      "Combination of ORDER BY/SORT BY/DISTRIBUTE BY/CLUSTER BY is not supported", ctx)
  }

  def distributeByUnsupportedError(ctx: QueryOrganizationContext): Throwable = {
    new ParseException("DISTRIBUTE BY is not supported", ctx)
  }

  def transformWithSerdeUnsupportedError(ctx: ParserRuleContext): Throwable = {
    new ParseException("TRANSFORM with serde is only supported in hive mode", ctx)
  }

  def lateralWithPivotInFromClauseNotAllowedError(ctx: FromClauseContext): Throwable = {
    new ParseException("LATERAL cannot be used together with PIVOT in FROM clause", ctx)
  }

  def repetitiveWindowDefinitionError(name: String, ctx: WindowClauseContext): Throwable = {
    new ParseException(s"The definition of window '$name' is repetitive", ctx)
  }

  def invalidWindowReferenceError(name: String, ctx: WindowClauseContext): Throwable = {
    new ParseException(s"Window reference '$name' is not a window specification", ctx)
  }

  def cannotResolveWindowReferenceError(name: String, ctx: WindowClauseContext): Throwable = {
    new ParseException(s"Cannot resolve window reference '$name'", ctx)
  }

  def joinCriteriaUnimplementedError(join: JoinCriteriaContext, ctx: RelationContext): Throwable = {
    new ParseException(s"Unimplemented joinCriteria: $join", ctx)
  }

  def naturalCrossJoinUnsupportedError(ctx: RelationContext): Throwable = {
    new ParseException("NATURAL CROSS JOIN is not supported", ctx)
  }

  def emptyInputForTableSampleError(ctx: ParserRuleContext): Throwable = {
    new ParseException("TABLESAMPLE does not accept empty inputs.", ctx)
  }

  def tableSampleByBytesUnsupportedError(msg: String, ctx: SampleMethodContext): Throwable = {
    new ParseException(s"TABLESAMPLE($msg) is not supported", ctx)
  }

  def invalidByteLengthLiteralError(bytesStr: String, ctx: SampleByBytesContext): Throwable = {
    new ParseException(s"$bytesStr is not a valid byte length literal, " +
        "expected syntax: DIGIT+ ('B' | 'K' | 'M' | 'G')", ctx)
  }

  def invalidEscapeStringError(ctx: PredicateContext): Throwable = {
    new ParseException("Invalid escape string. Escape string must contain only one character.", ctx)
  }

  def trimOptionUnsupportedError(trimOption: Int, ctx: TrimContext): Throwable = {
    new ParseException("Function trim doesn't support with " +
      s"type $trimOption. Please use BOTH, LEADING or TRAILING as trim type", ctx)
  }

  def functionNameUnsupportedError(functionName: String, ctx: ParserRuleContext): Throwable = {
    new ParseException(s"Unsupported function name '$functionName'", ctx)
  }

  def cannotParseValueTypeError(
      valueType: String, value: String, ctx: TypeConstructorContext): Throwable = {
    new ParseException(s"Cannot parse the $valueType value: $value", ctx)
  }

  def cannotParseIntervalValueError(value: String, ctx: TypeConstructorContext): Throwable = {
    new ParseException(s"Cannot parse the INTERVAL value: $value", ctx)
  }

  def literalValueTypeUnsupportedError(
      valueType: String, ctx: TypeConstructorContext): Throwable = {
    new ParseException(s"Literals of type '$valueType' are currently not supported.", ctx)
  }

  def parsingValueTypeError(
      e: IllegalArgumentException, valueType: String, ctx: TypeConstructorContext): Throwable = {
    val message = Option(e.getMessage).getOrElse(s"Exception parsing $valueType")
    new ParseException(message, ctx)
  }

  def invalidNumericLiteralRangeError(rawStrippedQualifier: String, minValue: BigDecimal,
      maxValue: BigDecimal, typeName: String, ctx: NumberContext): Throwable = {
    new ParseException(s"Numeric literal $rawStrippedQualifier does not " +
      s"fit in range [$minValue, $maxValue] for type $typeName", ctx)
  }

  def moreThanOneFromToUnitInIntervalLiteralError(ctx: ParserRuleContext): Throwable = {
    new ParseException("Can only have a single from-to unit in the interval literal syntax", ctx)
  }

  def invalidIntervalLiteralError(ctx: IntervalContext): Throwable = {
    new ParseException("at least one time unit should be given for interval literal", ctx)
  }

  def invalidIntervalFormError(value: String, ctx: MultiUnitsIntervalContext): Throwable = {
    new ParseException("Can only use numbers in the interval value part for" +
      s" multiple unit value pairs interval form, but got invalid value: $value", ctx)
  }

  def invalidFromToUnitValueError(ctx: IntervalValueContext): Throwable = {
    new ParseException("The value of from-to unit must be a string", ctx)
  }

  def fromToIntervalUnsupportedError(
      from: String, to: String, ctx: UnitToUnitIntervalContext): Throwable = {
    new ParseException(s"Intervals FROM $from TO $to are not supported.", ctx)
  }

  def dataTypeUnsupportedError(dataType: String, ctx: PrimitiveDataTypeContext): Throwable = {
    new ParseException(s"DataType $dataType is not supported.", ctx)
  }

  def partitionTransformNotExpectedError(
      name: String, describe: String, ctx: ApplyTransformContext): Throwable = {
    new ParseException(s"Expected a column reference for transform $name: $describe", ctx)
  }

  def tooManyArgumentsForTransformError(name: String, ctx: ApplyTransformContext): Throwable = {
    new ParseException(s"Too many arguments for transform $name", ctx)
  }

  def notEnoughArgumentsForTransformError(name: String, ctx: ApplyTransformContext): Throwable = {
    new ParseException(s"Not enough arguments for transform $name", ctx)
  }

  def invalidBucketsNumberError(describe: String, ctx: ApplyTransformContext): Throwable = {
    new ParseException(s"Invalid number of buckets: $describe", ctx)
  }

  def invalidTransformArgumentError(ctx: TransformArgumentContext): Throwable = {
    new ParseException("Invalid transform argument", ctx)
  }

  def cannotCleanReservedNamespacePropertyError(
      property: String, ctx: ParserRuleContext, msg: String): Throwable = {
    new ParseException(s"$property is a reserved namespace property, $msg.", ctx)
  }

  def propertiesAndDbPropertiesBothSpecifiedError(ctx: CreateNamespaceContext): Throwable = {
    new ParseException("Either PROPERTIES or DBPROPERTIES is allowed.", ctx)
  }

  def fromOrInNotAllowedInShowDatabasesError(ctx: ShowNamespacesContext): Throwable = {
    new ParseException(s"FROM/IN operator is not allowed in SHOW DATABASES", ctx)
  }

  def cannotCleanReservedTablePropertyError(
      property: String, ctx: ParserRuleContext, msg: String): Throwable = {
    new ParseException(s"$property is a reserved table property, $msg.", ctx)
  }

  def duplicatedTablePathsFoundError(
      pathOne: String, pathTwo: String, ctx: ParserRuleContext): Throwable = {
    new ParseException(s"Duplicated table paths found: '$pathOne' and '$pathTwo'. LOCATION" +
      s" and the case insensitive key 'path' in OPTIONS are all used to indicate the custom" +
      s" table path, you can only specify one of them.", ctx)
  }

  def storedAsAndStoredByBothSpecifiedError(ctx: CreateFileFormatContext): Throwable = {
    new ParseException("Expected either STORED AS or STORED BY, not both", ctx)
  }

  def operationInHiveStyleCommandUnsupportedError(operation: String,
      command: String, ctx: StatementContext, msgOpt: Option[String] = None): Throwable = {
    val basicError = s"$operation is not supported in Hive-style $command"
    val msg = if (msgOpt.isDefined) {
      s"$basicError, ${msgOpt.get}."
    } else {
      basicError
    }
    new ParseException(msg, ctx)
  }

  def operationNotAllowedError(message: String, ctx: ParserRuleContext): Throwable = {
    new ParseException(s"Operation not allowed: $message", ctx)
  }

  def descColumnForPartitionUnsupportedError(ctx: DescribeRelationContext): Throwable = {
    new ParseException("DESC TABLE COLUMN for a specific partition is not supported", ctx)
  }

  def incompletePartitionSpecificationError(
      key: String, ctx: DescribeRelationContext): Throwable = {
    new ParseException(s"PARTITION specification is incomplete: `$key`", ctx)
  }

  def computeStatisticsNotExpectedError(ctx: IdentifierContext): Throwable = {
    new ParseException(s"Expected `NOSCAN` instead of `${ctx.getText}`", ctx)
  }

  def addCatalogInCacheTableAsSelectNotAllowedError(
      quoted: String, ctx: CacheTableContext): Throwable = {
    new ParseException(s"It is not allowed to add catalog/namespace prefix $quoted to " +
      "the table name in CACHE TABLE AS SELECT", ctx)
  }

  def showFunctionsUnsupportedError(identifier: String, ctx: IdentifierContext): Throwable = {
    new ParseException(s"SHOW $identifier FUNCTIONS not supported", ctx)
  }

  def duplicateCteDefinitionNamesError(duplicateNames: String, ctx: CtesContext): Throwable = {
    new ParseException(s"CTE definition can't have duplicate names: $duplicateNames.", ctx)
  }

  def sqlStatementUnsupportedError(sqlText: String, position: Origin): Throwable = {
    new ParseException(Option(sqlText), "Unsupported SQL statement", position, position)
  }

  def unquotedIdentifierError(ident: String, ctx: ErrorIdentContext): Throwable = {
    new ParseException(s"Possibly unquoted identifier $ident detected. " +
      s"Please consider quoting it with back-quotes as `$ident`", ctx)
  }

  def duplicateClausesError(clauseName: String, ctx: ParserRuleContext): Throwable = {
    new ParseException(s"Found duplicate clauses: $clauseName", ctx)
  }

  def duplicateKeysError(key: String, ctx: ParserRuleContext): Throwable = {
    new ParseException(s"Found duplicate keys '$key'.", ctx)
  }

}
