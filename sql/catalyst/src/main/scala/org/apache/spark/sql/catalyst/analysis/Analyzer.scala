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

package org.apache.spark.sql.catalyst.analysis

import java.lang.reflect.{Method, Modifier}
import java.util
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst._
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.catalyst.encoders.OuterScopes
import org.apache.spark.sql.catalyst.expressions.{Expression, FrameLessOffsetWindowFunction, _}
import org.apache.spark.sql.catalyst.expressions.SubExprUtils._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.expressions.objects._
import org.apache.spark.sql.catalyst.optimizer.OptimizeUpdateFields
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules._
import org.apache.spark.sql.catalyst.streaming.StreamingRelationV2
import org.apache.spark.sql.catalyst.trees.{AlwaysProcess, CurrentOrigin}
import org.apache.spark.sql.catalyst.trees.TreePattern._
import org.apache.spark.sql.catalyst.util.{toPrettySQL, CharVarcharUtils}
import org.apache.spark.sql.connector.catalog._
import org.apache.spark.sql.connector.catalog.CatalogV2Implicits._
import org.apache.spark.sql.connector.catalog.TableChange.{AddColumn, After, ColumnChange, ColumnPosition, DeleteColumn, RenameColumn, UpdateColumnComment, UpdateColumnNullability, UpdateColumnPosition, UpdateColumnType}
import org.apache.spark.sql.connector.catalog.functions.{AggregateFunction => V2AggregateFunction, BoundFunction, ScalarFunction}
import org.apache.spark.sql.connector.catalog.functions.ScalarFunction.MAGIC_METHOD_NAME
import org.apache.spark.sql.connector.expressions.{FieldReference, IdentityTransform, Transform}
import org.apache.spark.sql.errors.{QueryCompilationErrors, QueryExecutionErrors}
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf.{PartitionOverwriteMode, StoreAssignmentPolicy}
import org.apache.spark.sql.types._
import org.apache.spark.sql.util.{CaseInsensitiveStringMap, SchemaUtils}
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.util.Utils

/**
 * A trivial [[Analyzer]] with a dummy [[SessionCatalog]], [[EmptyFunctionRegistry]] and
 * [[EmptyTableFunctionRegistry]]. Used for testing when all relations are already filled
 * in and the analyzer needs only to resolve attribute references.
 */
object SimpleAnalyzer extends Analyzer(
  new CatalogManager(
    FakeV2SessionCatalog,
    new SessionCatalog(
      new InMemoryCatalog,
      EmptyFunctionRegistry,
      EmptyTableFunctionRegistry) {
      override def createDatabase(dbDefinition: CatalogDatabase, ignoreIfExists: Boolean): Unit = {}
    })) {
  override def resolver: Resolver = caseSensitiveResolution
}

object FakeV2SessionCatalog extends TableCatalog {
  private def fail() = throw new UnsupportedOperationException
  override def listTables(namespace: Array[String]): Array[Identifier] = fail()
  override def loadTable(ident: Identifier): Table = {
    throw new NoSuchTableException(ident.toString)
  }
  override def createTable(
      ident: Identifier,
      schema: StructType,
      partitions: Array[Transform],
      properties: util.Map[String, String]): Table = fail()
  override def alterTable(ident: Identifier, changes: TableChange*): Table = fail()
  override def dropTable(ident: Identifier): Boolean = fail()
  override def renameTable(oldIdent: Identifier, newIdent: Identifier): Unit = fail()
  override def initialize(name: String, options: CaseInsensitiveStringMap): Unit = fail()
  override def name(): String = CatalogManager.SESSION_CATALOG_NAME
}

/**
 * Provides a way to keep state during the analysis, mostly for resolving views. This enables us to
 * decouple the concerns of analysis environment from the catalog.
 * The state that is kept here is per-query.
 *
 * Note this is thread local.
 *
 * @param catalogAndNamespace The catalog and namespace used in the view resolution. This overrides
 *                            the current catalog and namespace when resolving relations inside
 *                            views.
 * @param nestedViewDepth The nested depth in the view resolution, this enables us to limit the
 *                        depth of nested views.
 * @param maxNestedViewDepth The maximum allowed depth of nested view resolution.
 * @param relationCache A mapping from qualified table names to resolved relations. This can ensure
 *                      that the table is resolved only once if a table is used multiple times
 *                      in a query.
 * @param referredTempViewNames All the temp view names referred by the current view we are
 *                              resolving. It's used to make sure the relation resolution is
 *                              consistent between view creation and view resolution. For example,
 *                              if `t` was a permanent table when the current view was created, it
 *                              should still be a permanent table when resolving the current view,
 *                              even if a temp view `t` has been created.
 */
case class AnalysisContext(
    catalogAndNamespace: Seq[String] = Nil,
    nestedViewDepth: Int = 0,
    maxNestedViewDepth: Int = -1,
    relationCache: mutable.Map[Seq[String], LogicalPlan] = mutable.Map.empty,
    referredTempViewNames: Seq[Seq[String]] = Seq.empty,
    referredTempFunctionNames: Seq[String] = Seq.empty)

object AnalysisContext {
  private val value = new ThreadLocal[AnalysisContext]() {
    override def initialValue: AnalysisContext = AnalysisContext()
  }

  def get: AnalysisContext = value.get()
  def reset(): Unit = value.remove()

  private def set(context: AnalysisContext): Unit = value.set(context)

  def withAnalysisContext[A](viewDesc: CatalogTable)(f: => A): A = {
    val originContext = value.get()
    val maxNestedViewDepth = if (originContext.maxNestedViewDepth == -1) {
      // Here we start to resolve views, get `maxNestedViewDepth` from configs.
      SQLConf.get.maxNestedViewDepth
    } else {
      originContext.maxNestedViewDepth
    }
    val context = AnalysisContext(
      viewDesc.viewCatalogAndNamespace,
      originContext.nestedViewDepth + 1,
      maxNestedViewDepth,
      originContext.relationCache,
      viewDesc.viewReferredTempViewNames,
      viewDesc.viewReferredTempFunctionNames)
    set(context)
    try f finally { set(originContext) }
  }
}

/**
 * Provides a logical query plan analyzer, which translates [[UnresolvedAttribute]]s and
 * [[UnresolvedRelation]]s into fully typed objects using information in a [[SessionCatalog]].
 */
class Analyzer(override val catalogManager: CatalogManager)
  extends RuleExecutor[LogicalPlan] with CheckAnalysis with SQLConfHelper {

  private val v1SessionCatalog: SessionCatalog = catalogManager.v1SessionCatalog

  override protected def isPlanIntegral(plan: LogicalPlan): Boolean = {
    !Utils.isTesting || LogicalPlanIntegrity.checkIfExprIdsAreGloballyUnique(plan)
  }

  override def isView(nameParts: Seq[String]): Boolean = v1SessionCatalog.isView(nameParts)

  // Only for tests.
  def this(catalog: SessionCatalog) = {
    this(new CatalogManager(FakeV2SessionCatalog, catalog))
  }

  def executeAndCheck(plan: LogicalPlan, tracker: QueryPlanningTracker): LogicalPlan = {
    if (plan.analyzed) return plan
    AnalysisHelper.markInAnalyzer {
      val analyzed = executeAndTrack(plan, tracker)
      try {
        checkAnalysis(analyzed)
        analyzed
      } catch {
        case e: AnalysisException =>
          val ae = new AnalysisException(e.message, e.line, e.startPosition, Option(analyzed))
          ae.setStackTrace(e.getStackTrace)
          throw ae
      }
    }
  }

  override def execute(plan: LogicalPlan): LogicalPlan = {
    AnalysisContext.reset()
    try {
      executeSameContext(plan)
    } finally {
      AnalysisContext.reset()
    }
  }

  private def executeSameContext(plan: LogicalPlan): LogicalPlan = super.execute(plan)

  def resolver: Resolver = conf.resolver

  /**
   * If the plan cannot be resolved within maxIterations, analyzer will throw exception to inform
   * user to increase the value of SQLConf.ANALYZER_MAX_ITERATIONS.
   */
  protected def fixedPoint =
    FixedPoint(
      conf.analyzerMaxIterations,
      errorOnExceed = true,
      maxIterationsSetting = SQLConf.ANALYZER_MAX_ITERATIONS.key)

  /**
   * Override to provide additional rules for the "Resolution" batch.
   */
  val extendedResolutionRules: Seq[Rule[LogicalPlan]] = Nil

  /**
   * Override to provide rules to do post-hoc resolution. Note that these rules will be executed
   * in an individual batch. This batch is to run right after the normal resolution batch and
   * execute its rules in one pass.
   */
  val postHocResolutionRules: Seq[Rule[LogicalPlan]] = Nil

  private def typeCoercionRules(): List[Rule[LogicalPlan]] = if (conf.ansiEnabled) {
    AnsiTypeCoercion.typeCoercionRules
  } else {
    TypeCoercion.typeCoercionRules
  }

  override def batches: Seq[Batch] = Seq(
    Batch("Substitution", fixedPoint,
      // This rule optimizes `UpdateFields` expression chains so looks more like optimization rule.
      // However, when manipulating deeply nested schema, `UpdateFields` expression tree could be
      // very complex and make analysis impossible. Thus we need to optimize `UpdateFields` early
      // at the beginning of analysis.
      OptimizeUpdateFields,
      CTESubstitution,
      WindowsSubstitution,
      EliminateUnions,
      SubstituteUnresolvedOrdinals),
    Batch("Disable Hints", Once,
      new ResolveHints.DisableHints),
    Batch("Hints", fixedPoint,
      ResolveHints.ResolveJoinStrategyHints,
      ResolveHints.ResolveCoalesceHints),
    Batch("Simple Sanity Check", Once,
      LookupFunctions),
    Batch("Resolution", fixedPoint,
      ResolveTableValuedFunctions(v1SessionCatalog) ::
      ResolveNamespace(catalogManager) ::
      new ResolveCatalogs(catalogManager) ::
      ResolveUserSpecifiedColumns ::
      ResolveInsertInto ::
      ResolveRelations ::
      ResolveTables ::
      ResolvePartitionSpec ::
      AddMetadataColumns ::
      DeduplicateRelations ::
      ResolveReferences ::
      ResolveCreateNamedStruct ::
      ResolveDeserializer ::
      ResolveNewInstance ::
      ResolveUpCast ::
      ResolveGroupingAnalytics ::
      ResolvePivot ::
      ResolveOrdinalInOrderByAndGroupBy ::
      ResolveAggAliasInGroupBy ::
      ResolveMissingReferences ::
      ExtractGenerator ::
      ResolveGenerate ::
      ResolveFunctions ::
      ResolveAliases ::
      ResolveSubquery ::
      ResolveSubqueryColumnAliases ::
      ResolveWindowOrder ::
      ResolveWindowFrame ::
      ResolveNaturalAndUsingJoin ::
      ResolveOutputRelation ::
      ExtractWindowExpressions ::
      GlobalAggregates ::
      ResolveAggregateFunctions ::
      TimeWindowing ::
      ResolveInlineTables ::
      ResolveHigherOrderFunctions(catalogManager) ::
      ResolveLambdaVariables ::
      ResolveTimeZone ::
      ResolveRandomSeed ::
      ResolveBinaryArithmetic ::
      ResolveUnion ::
      typeCoercionRules ++
      extendedResolutionRules : _*),
    Batch("Remove TempResolvedColumn", Once, RemoveTempResolvedColumn),
    Batch("Apply Char Padding", Once,
      ApplyCharTypePadding),
    Batch("Post-Hoc Resolution", Once,
      Seq(ResolveCommandsWithIfExists) ++
      postHocResolutionRules: _*),
    Batch("Normalize Alter Table", Once, ResolveAlterTableChanges),
    Batch("Remove Unresolved Hints", Once,
      new ResolveHints.RemoveAllHints),
    Batch("Nondeterministic", Once,
      PullOutNondeterministic),
    Batch("UDF", Once,
      HandleNullInputsForUDF,
      ResolveEncodersInUDF),
    Batch("UpdateNullability", Once,
      UpdateAttributeNullability),
    Batch("Subquery", Once,
      UpdateOuterReferences),
    Batch("Cleanup", fixedPoint,
      CleanupAliases),
    Batch("HandleAnalysisOnlyCommand", Once,
      HandleAnalysisOnlyCommand)
  )

  /**
   * For [[Add]]:
   * 1. if both side are interval, stays the same;
   * 2. else if one side is date and the other is interval,
   *    turns it to [[DateAddInterval]];
   * 3. else if one side is interval, turns it to [[TimeAdd]];
   * 4. else if one side is date, turns it to [[DateAdd]] ;
   * 5. else stays the same.
   *
   * For [[Subtract]]:
   * 1. if both side are interval, stays the same;
   * 2. else if the left side is date and the right side is interval,
   *    turns it to [[DateAddInterval(l, -r)]];
   * 3. else if the right side is an interval, turns it to [[TimeAdd(l, -r)]];
   * 4. else if one side is timestamp, turns it to [[SubtractTimestamps]];
   * 5. else if the right side is date, turns it to [[DateDiff]]/[[SubtractDates]];
   * 6. else if the left side is date, turns it to [[DateSub]];
   * 7. else turns it to stays the same.
   *
   * For [[Multiply]]:
   * 1. If one side is interval, turns it to [[MultiplyInterval]];
   * 2. otherwise, stays the same.
   *
   * For [[Divide]]:
   * 1. If the left side is interval, turns it to [[DivideInterval]];
   * 2. otherwise, stays the same.
   */
  object ResolveBinaryArithmetic extends Rule[LogicalPlan] {
    override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsPattern(BINARY_ARITHMETIC), ruleId) {
      case p: LogicalPlan => p.transformExpressionsUpWithPruning(
        _.containsPattern(BINARY_ARITHMETIC), ruleId) {
        case a @ Add(l, r, f) if a.childrenResolved => (l.dataType, r.dataType) match {
          case (DateType, _: DayTimeIntervalType) => TimeAdd(Cast(l, TimestampType), r)
          case (_: DayTimeIntervalType, DateType) => TimeAdd(Cast(r, TimestampType), l)
          case (DateType, _: YearMonthIntervalType) => DateAddYMInterval(l, r)
          case (_: YearMonthIntervalType, DateType) => DateAddYMInterval(r, l)
          case (TimestampType, _: YearMonthIntervalType) => TimestampAddYMInterval(l, r)
          case (_: YearMonthIntervalType, TimestampType) => TimestampAddYMInterval(r, l)
          case (CalendarIntervalType, CalendarIntervalType) |
               (_: DayTimeIntervalType, _: DayTimeIntervalType) => a
          case (DateType, CalendarIntervalType) => DateAddInterval(l, r, ansiEnabled = f)
          case (_, CalendarIntervalType | _: DayTimeIntervalType) => Cast(TimeAdd(l, r), l.dataType)
          case (CalendarIntervalType, DateType) => DateAddInterval(r, l, ansiEnabled = f)
          case (CalendarIntervalType | _: DayTimeIntervalType, _) => Cast(TimeAdd(r, l), r.dataType)
          case (DateType, dt) if dt != StringType => DateAdd(l, r)
          case (dt, DateType) if dt != StringType => DateAdd(r, l)
          case _ => a
        }
        case s @ Subtract(l, r, f) if s.childrenResolved => (l.dataType, r.dataType) match {
          case (DateType, _: DayTimeIntervalType) =>
            DatetimeSub(l, r, TimeAdd(Cast(l, TimestampType), UnaryMinus(r, f)))
          case (DateType, _: YearMonthIntervalType) =>
            DatetimeSub(l, r, DateAddYMInterval(l, UnaryMinus(r, f)))
          case (TimestampType, _: YearMonthIntervalType) =>
            DatetimeSub(l, r, TimestampAddYMInterval(l, UnaryMinus(r, f)))
          case (CalendarIntervalType, CalendarIntervalType) |
               (_: DayTimeIntervalType, _: DayTimeIntervalType) => s
          case (DateType, CalendarIntervalType) =>
            DatetimeSub(l, r, DateAddInterval(l, UnaryMinus(r, f), ansiEnabled = f))
          case (_, CalendarIntervalType | _: DayTimeIntervalType) =>
            Cast(DatetimeSub(l, r, TimeAdd(l, UnaryMinus(r, f))), l.dataType)
          case (TimestampType, _) => SubtractTimestamps(l, r)
          case (_, TimestampType) => SubtractTimestamps(l, r)
          case (_, DateType) => SubtractDates(l, r)
          case (DateType, dt) if dt != StringType => DateSub(l, r)
          case _ => s
        }
        case m @ Multiply(l, r, f) if m.childrenResolved => (l.dataType, r.dataType) match {
          case (CalendarIntervalType, _) => MultiplyInterval(l, r, f)
          case (_, CalendarIntervalType) => MultiplyInterval(r, l, f)
          case (_: YearMonthIntervalType, _) => MultiplyYMInterval(l, r)
          case (_, _: YearMonthIntervalType) => MultiplyYMInterval(r, l)
          case (_: DayTimeIntervalType, _) => MultiplyDTInterval(l, r)
          case (_, _: DayTimeIntervalType) => MultiplyDTInterval(r, l)
          case _ => m
        }
        case d @ Divide(l, r, f) if d.childrenResolved => (l.dataType, r.dataType) match {
          case (CalendarIntervalType, _) => DivideInterval(l, r, f)
          case (_: YearMonthIntervalType, _) => DivideYMInterval(l, r)
          case (_: DayTimeIntervalType, _) => DivideDTInterval(l, r)
          case _ => d
        }
      }
    }
  }

  /**
   * Substitute child plan with WindowSpecDefinitions.
   */
  object WindowsSubstitution extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsPattern(WITH_WINDOW_DEFINITION), ruleId) {
      // Lookup WindowSpecDefinitions. This rule works with unresolved children.
      case WithWindowDefinition(windowDefinitions, child) => child.resolveExpressions {
        case UnresolvedWindowExpression(c, WindowSpecReference(windowName)) =>
          val windowSpecDefinition = windowDefinitions.getOrElse(windowName,
            throw QueryCompilationErrors.windowSpecificationNotDefinedError(windowName))
          WindowExpression(c, windowSpecDefinition)
      }
    }
  }

  /**
   * Replaces [[UnresolvedAlias]]s with concrete aliases.
   */
  object ResolveAliases extends Rule[LogicalPlan] {
    private def assignAliases(exprs: Seq[NamedExpression]) = {
      exprs.map(_.transformUpWithPruning(_.containsPattern(UNRESOLVED_ALIAS)) {
          case u @ UnresolvedAlias(child, optGenAliasFunc) =>
          child match {
            case ne: NamedExpression => ne
            case go @ GeneratorOuter(g: Generator) if g.resolved => MultiAlias(go, Nil)
            case e if !e.resolved => u
            case g: Generator => MultiAlias(g, Nil)
            case c @ Cast(ne: NamedExpression, _, _) => Alias(c, ne.name)()
            case e: ExtractValue => Alias(e, toPrettySQL(e))()
            case e if optGenAliasFunc.isDefined =>
              Alias(child, optGenAliasFunc.get.apply(e))()
            case e => Alias(e, toPrettySQL(e))()
          }
        }
      ).asInstanceOf[Seq[NamedExpression]]
    }

    private def hasUnresolvedAlias(exprs: Seq[NamedExpression]) =
      exprs.exists(_.find(_.isInstanceOf[UnresolvedAlias]).isDefined)

    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsPattern(UNRESOLVED_ALIAS), ruleId) {
      case Aggregate(groups, aggs, child) if child.resolved && hasUnresolvedAlias(aggs) =>
        Aggregate(groups, assignAliases(aggs), child)

      case Pivot(groupByOpt, pivotColumn, pivotValues, aggregates, child)
        if child.resolved && groupByOpt.isDefined && hasUnresolvedAlias(groupByOpt.get) =>
        Pivot(Some(assignAliases(groupByOpt.get)), pivotColumn, pivotValues, aggregates, child)

      case Project(projectList, child) if child.resolved && hasUnresolvedAlias(projectList) =>
        Project(assignAliases(projectList), child)

      case c: CollectMetrics if c.child.resolved && hasUnresolvedAlias(c.metrics) =>
        c.copy(metrics = assignAliases(c.metrics))
    }
  }

  object ResolveGroupingAnalytics extends Rule[LogicalPlan] {
    private[analysis] def hasGroupingFunction(e: Expression): Boolean = {
      e.collectFirst {
        case g: Grouping => g
        case g: GroupingID => g
      }.isDefined
    }

    private def replaceGroupingFunc(
        expr: Expression,
        groupByExprs: Seq[Expression],
        gid: Expression): Expression = {
      expr transform {
        case e: GroupingID =>
          if (e.groupByExprs.isEmpty ||
              e.groupByExprs.map(_.canonicalized) == groupByExprs.map(_.canonicalized)) {
            Alias(gid, toPrettySQL(e))()
          } else {
            throw QueryCompilationErrors.groupingIDMismatchError(e, groupByExprs)
          }
        case e @ Grouping(col: Expression) =>
          val idx = groupByExprs.indexWhere(_.semanticEquals(col))
          if (idx >= 0) {
            Alias(Cast(BitwiseAnd(ShiftRight(gid, Literal(groupByExprs.length - 1 - idx)),
              Literal(1L)), ByteType), toPrettySQL(e))()
          } else {
            throw QueryCompilationErrors.groupingColInvalidError(col, groupByExprs)
          }
      }
    }

    /*
     * Create new alias for all group by expressions for `Expand` operator.
     */
    private def constructGroupByAlias(groupByExprs: Seq[Expression]): Seq[Alias] = {
      groupByExprs.map {
        case e: NamedExpression => Alias(e, e.name)(qualifier = e.qualifier)
        case other => Alias(other, other.toString)()
      }
    }

    /*
     * Construct [[Expand]] operator with grouping sets.
     */
    private def constructExpand(
        selectedGroupByExprs: Seq[Seq[Expression]],
        child: LogicalPlan,
        groupByAliases: Seq[Alias],
        gid: Attribute): LogicalPlan = {
      // Change the nullability of group by aliases if necessary. For example, if we have
      // GROUPING SETS ((a,b), a), we do not need to change the nullability of a, but we
      // should change the nullability of b to be TRUE.
      // TODO: For Cube/Rollup just set nullability to be `true`.
      val expandedAttributes = groupByAliases.map { alias =>
        if (selectedGroupByExprs.exists(!_.contains(alias.child))) {
          alias.toAttribute.withNullability(true)
        } else {
          alias.toAttribute
        }
      }

      val groupingSetsAttributes = selectedGroupByExprs.map { groupingSetExprs =>
        groupingSetExprs.map { expr =>
          val alias = groupByAliases.find(_.child.semanticEquals(expr)).getOrElse(
            throw QueryCompilationErrors.selectExprNotInGroupByError(expr, groupByAliases))
          // Map alias to expanded attribute.
          expandedAttributes.find(_.semanticEquals(alias.toAttribute)).getOrElse(
            alias.toAttribute)
        }
      }

      Expand(groupingSetsAttributes, groupByAliases, expandedAttributes, gid, child)
    }

    /*
     * Construct new aggregate expressions by replacing grouping functions.
     */
    private def constructAggregateExprs(
        groupByExprs: Seq[Expression],
        aggregations: Seq[NamedExpression],
        groupByAliases: Seq[Alias],
        groupingAttrs: Seq[Expression],
        gid: Attribute): Seq[NamedExpression] = aggregations.map {
      // collect all the found AggregateExpression, so we can check an expression is part of
      // any AggregateExpression or not.
      val aggsBuffer = ArrayBuffer[Expression]()
      // Returns whether the expression belongs to any expressions in `aggsBuffer` or not.
      def isPartOfAggregation(e: Expression): Boolean = {
        aggsBuffer.exists(a => a.find(_ eq e).isDefined)
      }
      replaceGroupingFunc(_, groupByExprs, gid).transformDown {
        // AggregateExpression should be computed on the unmodified value of its argument
        // expressions, so we should not replace any references to grouping expression
        // inside it.
        case e: AggregateExpression =>
          aggsBuffer += e
          e
        case e if isPartOfAggregation(e) => e
        case e =>
          // Replace expression by expand output attribute.
          val index = groupByAliases.indexWhere(_.child.semanticEquals(e))
          if (index == -1) {
            e
          } else {
            groupingAttrs(index)
          }
      }.asInstanceOf[NamedExpression]
    }

    /*
     * Construct [[Aggregate]] operator from Cube/Rollup/GroupingSets.
     */
    private def constructAggregate(
        selectedGroupByExprs: Seq[Seq[Expression]],
        groupByExprs: Seq[Expression],
        aggregationExprs: Seq[NamedExpression],
        child: LogicalPlan): LogicalPlan = {

      if (groupByExprs.size > GroupingID.dataType.defaultSize * 8) {
        throw QueryCompilationErrors.groupingSizeTooLargeError(GroupingID.dataType.defaultSize * 8)
      }

      // Expand works by setting grouping expressions to null as determined by the
      // `selectedGroupByExprs`. To prevent these null values from being used in an aggregate
      // instead of the original value we need to create new aliases for all group by expressions
      // that will only be used for the intended purpose.
      val groupByAliases = constructGroupByAlias(groupByExprs)

      val gid = AttributeReference(VirtualColumn.groupingIdName, GroupingID.dataType, false)()
      val expand = constructExpand(selectedGroupByExprs, child, groupByAliases, gid)
      val groupingAttrs = expand.output.drop(child.output.length)

      val aggregations = constructAggregateExprs(
        groupByExprs, aggregationExprs, groupByAliases, groupingAttrs, gid)

      Aggregate(groupingAttrs, aggregations, expand)
    }

    private def findGroupingExprs(plan: LogicalPlan): Seq[Expression] = {
      plan.collectFirst {
        case a: Aggregate =>
          // this Aggregate should have grouping id as the last grouping key.
          val gid = a.groupingExpressions.last
          if (!gid.isInstanceOf[AttributeReference]
            || gid.asInstanceOf[AttributeReference].name != VirtualColumn.groupingIdName) {
            throw QueryCompilationErrors.groupingMustWithGroupingSetsOrCubeOrRollupError()
          }
          a.groupingExpressions.take(a.groupingExpressions.length - 1)
      }.getOrElse {
        throw QueryCompilationErrors.groupingMustWithGroupingSetsOrCubeOrRollupError()
      }
    }

    private def tryResolveHavingCondition(
        h: UnresolvedHaving,
        aggregate: Aggregate,
        selectedGroupByExprs: Seq[Seq[Expression]],
        groupByExprs: Seq[Expression]): LogicalPlan = {
      // For CUBE/ROLLUP expressions, to avoid resolving repeatedly, here we delete them from
      // groupingExpressions for condition resolving.
      val aggForResolving = aggregate.copy(groupingExpressions = groupByExprs)
      // Try resolving the condition of the filter as though it is in the aggregate clause
      val (extraAggExprs, Seq(resolvedHavingCond)) =
        ResolveAggregateFunctions.resolveExprsWithAggregate(Seq(h.havingCondition), aggForResolving)

      // Push the aggregate expressions into the aggregate (if any).
      val newChild = constructAggregate(selectedGroupByExprs, groupByExprs,
        aggregate.aggregateExpressions ++ extraAggExprs, aggregate.child)

      // Since the output exprId will be changed in the constructed aggregate, here we build an
      // attrMap to resolve the condition again.
      val attrMap = AttributeMap((aggForResolving.output ++ extraAggExprs.map(_.toAttribute))
        .zip(newChild.output))
      val newCond = resolvedHavingCond.transform {
        case a: AttributeReference => attrMap.getOrElse(a, a)
      }

      if (extraAggExprs.isEmpty) {
        Filter(newCond, newChild)
      } else {
        Project(newChild.output.dropRight(extraAggExprs.length),
          Filter(newCond, newChild))
      }
    }

    // This require transformDown to resolve having condition when generating aggregate node for
    // CUBE/ROLLUP/GROUPING SETS. This also replace grouping()/grouping_id() in resolved
    // Filter/Sort.
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsDownWithPruning(
      _.containsPattern(GROUPING_ANALYTICS), ruleId) {
      case h @ UnresolvedHaving(_, agg @ Aggregate(
        GroupingAnalytics(selectedGroupByExprs, groupByExprs), aggExprs, _))
        if agg.childrenResolved && aggExprs.forall(_.resolved) =>
        tryResolveHavingCondition(h, agg, selectedGroupByExprs, groupByExprs)

      case a if !a.childrenResolved => a // be sure all of the children are resolved.

      // Ensure group by expressions and aggregate expressions have been resolved.
      case Aggregate(GroupingAnalytics(selectedGroupByExprs, groupByExprs), aggExprs, child)
        if aggExprs.forall(_.resolved) =>
        constructAggregate(selectedGroupByExprs, groupByExprs, aggExprs, child)

      // We should make sure all expressions in condition have been resolved.
      case f @ Filter(cond, child) if hasGroupingFunction(cond) && cond.resolved =>
        val groupingExprs = findGroupingExprs(child)
        // The unresolved grouping id will be resolved by ResolveMissingReferences
        val newCond = replaceGroupingFunc(cond, groupingExprs, VirtualColumn.groupingIdAttribute)
        f.copy(condition = newCond)

      // We should make sure all [[SortOrder]]s have been resolved.
      case s @ Sort(order, _, child)
        if order.exists(hasGroupingFunction) && order.forall(_.resolved) =>
        val groupingExprs = findGroupingExprs(child)
        val gid = VirtualColumn.groupingIdAttribute
        // The unresolved grouping id will be resolved by ResolveMissingReferences
        val newOrder = order.map(replaceGroupingFunc(_, groupingExprs, gid).asInstanceOf[SortOrder])
        s.copy(order = newOrder)
    }
  }

  object ResolvePivot extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsWithPruning(
      _.containsPattern(PIVOT), ruleId) {
      case p: Pivot if !p.childrenResolved || !p.aggregates.forall(_.resolved)
        || (p.groupByExprsOpt.isDefined && !p.groupByExprsOpt.get.forall(_.resolved))
        || !p.pivotColumn.resolved || !p.pivotValues.forall(_.resolved) => p
      case Pivot(groupByExprsOpt, pivotColumn, pivotValues, aggregates, child) =>
        if (!RowOrdering.isOrderable(pivotColumn.dataType)) {
          throw QueryCompilationErrors.unorderablePivotColError(pivotColumn)
        }
        // Check all aggregate expressions.
        aggregates.foreach(checkValidAggregateExpression)
        // Check all pivot values are literal and match pivot column data type.
        val evalPivotValues = pivotValues.map { value =>
          val foldable = value match {
            case Alias(v, _) => v.foldable
            case _ => value.foldable
          }
          if (!foldable) {
            throw QueryCompilationErrors.nonLiteralPivotValError(value)
          }
          if (!Cast.canCast(value.dataType, pivotColumn.dataType)) {
            throw QueryCompilationErrors.pivotValDataTypeMismatchError(value, pivotColumn)
          }
          Cast(value, pivotColumn.dataType, Some(conf.sessionLocalTimeZone)).eval(EmptyRow)
        }
        // Group-by expressions coming from SQL are implicit and need to be deduced.
        val groupByExprs = groupByExprsOpt.getOrElse {
          val pivotColAndAggRefs = pivotColumn.references ++ AttributeSet(aggregates)
          child.output.filterNot(pivotColAndAggRefs.contains)
        }
        val singleAgg = aggregates.size == 1
        def outputName(value: Expression, aggregate: Expression): String = {
          val stringValue = value match {
            case n: NamedExpression => n.name
            case _ =>
              val utf8Value =
                Cast(value, StringType, Some(conf.sessionLocalTimeZone)).eval(EmptyRow)
              Option(utf8Value).map(_.toString).getOrElse("null")
          }
          if (singleAgg) {
            stringValue
          } else {
            val suffix = aggregate match {
              case n: NamedExpression => n.name
              case _ => toPrettySQL(aggregate)
            }
            stringValue + "_" + suffix
          }
        }
        if (aggregates.forall(a => PivotFirst.supportsDataType(a.dataType))) {
          // Since evaluating |pivotValues| if statements for each input row can get slow this is an
          // alternate plan that instead uses two steps of aggregation.
          val namedAggExps: Seq[NamedExpression] = aggregates.map(a => Alias(a, a.sql)())
          val namedPivotCol = pivotColumn match {
            case n: NamedExpression => n
            case _ => Alias(pivotColumn, "__pivot_col")()
          }
          val bigGroup = groupByExprs :+ namedPivotCol
          val firstAgg = Aggregate(bigGroup, bigGroup ++ namedAggExps, child)
          val pivotAggs = namedAggExps.map { a =>
            Alias(PivotFirst(namedPivotCol.toAttribute, a.toAttribute, evalPivotValues)
              .toAggregateExpression()
            , "__pivot_" + a.sql)()
          }
          val groupByExprsAttr = groupByExprs.map(_.toAttribute)
          val secondAgg = Aggregate(groupByExprsAttr, groupByExprsAttr ++ pivotAggs, firstAgg)
          val pivotAggAttribute = pivotAggs.map(_.toAttribute)
          val pivotOutputs = pivotValues.zipWithIndex.flatMap { case (value, i) =>
            aggregates.zip(pivotAggAttribute).map { case (aggregate, pivotAtt) =>
              Alias(ExtractValue(pivotAtt, Literal(i), resolver), outputName(value, aggregate))()
            }
          }
          Project(groupByExprsAttr ++ pivotOutputs, secondAgg)
        } else {
          val pivotAggregates: Seq[NamedExpression] = pivotValues.flatMap { value =>
            def ifExpr(e: Expression) = {
              If(
                EqualNullSafe(
                  pivotColumn,
                  Cast(value, pivotColumn.dataType, Some(conf.sessionLocalTimeZone))),
                e, Literal(null))
            }
            aggregates.map { aggregate =>
              val filteredAggregate = aggregate.transformDown {
                // Assumption is the aggregate function ignores nulls. This is true for all current
                // AggregateFunction's with the exception of First and Last in their default mode
                // (which we handle) and possibly some Hive UDAF's.
                case First(expr, _) =>
                  First(ifExpr(expr), true)
                case Last(expr, _) =>
                  Last(ifExpr(expr), true)
                case a: ApproximatePercentile =>
                  // ApproximatePercentile takes two literals for accuracy and percentage which
                  // should not be wrapped by if-else.
                  a.withNewChildren(ifExpr(a.first) :: a.second :: a.third :: Nil)
                case a: AggregateFunction =>
                  a.withNewChildren(a.children.map(ifExpr))
              }.transform {
                // We are duplicating aggregates that are now computing a different value for each
                // pivot value.
                // TODO: Don't construct the physical container until after analysis.
                case ae: AggregateExpression => ae.copy(resultId = NamedExpression.newExprId)
              }
              Alias(filteredAggregate, outputName(value, aggregate))()
            }
          }
          Aggregate(groupByExprs, groupByExprs ++ pivotAggregates, child)
        }
    }

    // Support any aggregate expression that can appear in an Aggregate plan except Pandas UDF.
    // TODO: Support Pandas UDF.
    private def checkValidAggregateExpression(expr: Expression): Unit = expr match {
      case _: AggregateExpression => // OK and leave the argument check to CheckAnalysis.
      case expr: PythonUDF if PythonUDF.isGroupedAggPandasUDF(expr) =>
        throw QueryCompilationErrors.pandasUDFAggregateNotSupportedInPivotError()
      case e: Attribute =>
        throw QueryCompilationErrors.aggregateExpressionRequiredForPivotError(e.sql)
      case e => e.children.foreach(checkValidAggregateExpression)
    }
  }

  case class ResolveNamespace(catalogManager: CatalogManager)
    extends Rule[LogicalPlan] with LookupCatalog {
    def apply(plan: LogicalPlan): LogicalPlan = plan resolveOperators {
      case s @ ShowTables(UnresolvedNamespace(Seq()), _, _) =>
        s.copy(namespace = ResolvedNamespace(currentCatalog, catalogManager.currentNamespace))
      case s @ ShowTableExtended(UnresolvedNamespace(Seq()), _, _, _) =>
        s.copy(namespace = ResolvedNamespace(currentCatalog, catalogManager.currentNamespace))
      case s @ ShowViews(UnresolvedNamespace(Seq()), _, _) =>
        s.copy(namespace = ResolvedNamespace(currentCatalog, catalogManager.currentNamespace))
      case a @ AnalyzeTables(UnresolvedNamespace(Seq()), _) =>
        a.copy(namespace = ResolvedNamespace(currentCatalog, catalogManager.currentNamespace))
      case UnresolvedNamespace(Seq()) =>
        ResolvedNamespace(currentCatalog, Seq.empty[String])
      case UnresolvedNamespace(CatalogAndNamespace(catalog, ns)) =>
        ResolvedNamespace(catalog, ns)
    }
  }

  private def isResolvingView: Boolean = AnalysisContext.get.catalogAndNamespace.nonEmpty
  private def isReferredTempViewName(nameParts: Seq[String]): Boolean = {
    AnalysisContext.get.referredTempViewNames.exists { n =>
      (n.length == nameParts.length) && n.zip(nameParts).forall {
        case (a, b) => resolver(a, b)
      }
    }
  }

  private def unwrapRelationPlan(plan: LogicalPlan): LogicalPlan = {
    EliminateSubqueryAliases(plan) match {
      case v: View if v.isTempViewStoringAnalyzedPlan => v.child
      case other => other
    }
  }

  /**
   * Resolve relations to temp views. This is not an actual rule, and is called by
   * [[ResolveTables]] and [[ResolveRelations]].
   */
  object ResolveTempViews extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      AlwaysProcess.fn, ruleId) {
      case u @ UnresolvedRelation(ident, _, isStreaming) =>
        lookupAndResolveTempView(ident, isStreaming).getOrElse(u)
      case i @ InsertIntoStatement(UnresolvedRelation(ident, _, false), _, _, _, _, _) =>
        lookupAndResolveTempView(ident)
          .map(view => i.copy(table = view))
          .getOrElse(i)
      // TODO (SPARK-27484): handle streaming write commands when we have them.
      case write: V2WriteCommand =>
        write.table match {
          case UnresolvedRelation(ident, _, false) =>
            lookupAndResolveTempView(ident).map(unwrapRelationPlan).map {
              case r: DataSourceV2Relation => write.withNewTable(r)
              case _ => throw QueryCompilationErrors.writeIntoTempViewNotAllowedError(ident.quoted)
            }.getOrElse(write)
          case _ => write
        }
      case u @ UnresolvedTable(ident, cmd, _) =>
        lookupTempView(ident).foreach { _ =>
          throw QueryCompilationErrors.expectTableNotViewError(
            ResolvedView(ident.asIdentifier, isTemp = true), cmd, u.relationTypeMismatchHint, u)
        }
        u
      case u @ UnresolvedView(ident, cmd, allowTemp, _) =>
        lookupTempView(ident).map { _ =>
          if (!allowTemp) {
            u.failAnalysis(s"${ident.quoted} is a temp view. '$cmd' expects a permanent view.")
          }
          ResolvedView(ident.asIdentifier, isTemp = true)
        }
        .getOrElse(u)
      case u @ UnresolvedTableOrView(ident, cmd, allowTempView) =>
        lookupTempView(ident)
          .map { _ =>
            if (!allowTempView) {
              throw QueryCompilationErrors.expectTableOrPermanentViewNotTempViewError(
                ident.quoted, cmd, u)
            }
            ResolvedView(ident.asIdentifier, isTemp = true)
          }
          .getOrElse(u)
    }

    private def lookupTempView(
        identifier: Seq[String],
        isStreaming: Boolean = false): Option[LogicalPlan] = {
      // Permanent View can't refer to temp views, no need to lookup at all.
      if (isResolvingView && !isReferredTempViewName(identifier)) return None

      val tmpView = identifier match {
        case Seq(part1) => v1SessionCatalog.lookupTempView(part1)
        case Seq(part1, part2) => v1SessionCatalog.lookupGlobalTempView(part1, part2)
        case _ => None
      }

      if (isStreaming && tmpView.nonEmpty && !tmpView.get.isStreaming) {
        throw QueryCompilationErrors.readNonStreamingTempViewError(identifier.quoted)
      }
      tmpView
    }

    private def lookupAndResolveTempView(
        identifier: Seq[String],
        isStreaming: Boolean = false): Option[LogicalPlan] = {
      lookupTempView(identifier, isStreaming).map(ResolveRelations.resolveViews)
    }
  }

  // If we are resolving database objects (relations, functions, etc.) insides views, we may need to
  // expand single or multi-part identifiers with the current catalog and namespace of when the
  // view was created.
  private def expandIdentifier(nameParts: Seq[String]): Seq[String] = {
    if (!isResolvingView || isReferredTempViewName(nameParts)) return nameParts

    if (nameParts.length == 1) {
      AnalysisContext.get.catalogAndNamespace :+ nameParts.head
    } else if (catalogManager.isCatalogRegistered(nameParts.head)) {
      nameParts
    } else {
      AnalysisContext.get.catalogAndNamespace.head +: nameParts
    }
  }

  /**
   * Adds metadata columns to output for child relations when nodes are missing resolved attributes.
   *
   * References to metadata columns are resolved using columns from [[LogicalPlan.metadataOutput]],
   * but the relation's output does not include the metadata columns until the relation is replaced.
   * Unless this rule adds metadata to the relation's output, the analyzer will detect that nothing
   * produces the columns.
   *
   * This rule only adds metadata columns when a node is resolved but is missing input from its
   * children. This ensures that metadata columns are not added to the plan unless they are used. By
   * checking only resolved nodes, this ensures that * expansion is already done so that metadata
   * columns are not accidentally selected by *. This rule resolves operators downwards to avoid
   * projecting away metadata columns prematurely.
   */
  object AddMetadataColumns extends Rule[LogicalPlan] {

    import org.apache.spark.sql.catalyst.util._

    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsDownWithPruning(
      AlwaysProcess.fn, ruleId) {
      // Add metadata output to all node types
      case node if node.children.nonEmpty && node.resolved && hasMetadataCol(node) =>
        val inputAttrs = AttributeSet(node.children.flatMap(_.output))
        val metaCols = getMetadataAttributes(node).filterNot(inputAttrs.contains)
        if (metaCols.isEmpty) {
          node
        } else {
          val newNode = addMetadataCol(node)
          // We should not change the output schema of the plan. We should project away the extra
          // metadata columns if necessary.
          if (newNode.sameOutput(node)) {
            newNode
          } else {
            Project(node.output, newNode)
          }
        }
    }

    private def getMetadataAttributes(plan: LogicalPlan): Seq[Attribute] = {
      plan.expressions.flatMap(_.collect {
        case a: Attribute if a.isMetadataCol => a
        case a: Attribute
          if plan.children.exists(c => c.metadataOutput.exists(_.exprId == a.exprId)) =>
          plan.children.collectFirst {
            case c if c.metadataOutput.exists(_.exprId == a.exprId) =>
              c.metadataOutput.find(_.exprId == a.exprId).get
          }.get
      })
    }

    private def hasMetadataCol(plan: LogicalPlan): Boolean = {
      plan.expressions.exists(_.find {
        case a: Attribute =>
          // If an attribute is resolved before being labeled as metadata
          // (i.e. from the originating Dataset), we check with expression ID
          a.isMetadataCol ||
            plan.children.exists(c => c.metadataOutput.exists(_.exprId == a.exprId))
        case _ => false
      }.isDefined)
    }

    private def addMetadataCol(plan: LogicalPlan): LogicalPlan = plan match {
      case r: DataSourceV2Relation => r.withMetadataColumns()
      case p: Project =>
        p.copy(
          projectList = p.metadataOutput ++ p.projectList,
          child = addMetadataCol(p.child))
      case _ => plan.withNewChildren(plan.children.map(addMetadataCol))
    }
  }

  /**
   * Resolve table relations with concrete relations from v2 catalog.
   *
   * [[ResolveRelations]] still resolves v1 tables.
   */
  object ResolveTables extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = ResolveTempViews(plan).
      resolveOperatorsUpWithPruning(AlwaysProcess.fn, ruleId) {
      case u: UnresolvedRelation =>
        lookupV2Relation(u.multipartIdentifier, u.options, u.isStreaming)
          .map { relation =>
            val (catalog, ident) = relation match {
              case ds: DataSourceV2Relation => (ds.catalog, ds.identifier.get)
              case s: StreamingRelationV2 => (s.catalog, s.identifier.get)
            }
            SubqueryAlias(catalog.get.name +: ident.namespace :+ ident.name, relation)
          }.getOrElse(u)

      case u @ UnresolvedTable(NonSessionCatalogAndIdentifier(catalog, ident), _, _) =>
        CatalogV2Util.loadTable(catalog, ident)
          .map(table => ResolvedTable.create(catalog.asTableCatalog, ident, table))
          .getOrElse(u)

      case u @ UnresolvedTableOrView(NonSessionCatalogAndIdentifier(catalog, ident), _, _) =>
        CatalogV2Util.loadTable(catalog, ident)
          .map(table => ResolvedTable.create(catalog.asTableCatalog, ident, table))
          .getOrElse(u)

      case i @ InsertIntoStatement(u @ UnresolvedRelation(_, _, false), _, _, _, _, _)
          if i.query.resolved =>
        lookupV2Relation(u.multipartIdentifier, u.options, false)
          .map(v2Relation => i.copy(table = v2Relation))
          .getOrElse(i)

      // TODO (SPARK-27484): handle streaming write commands when we have them.
      case write: V2WriteCommand =>
        write.table match {
          case u: UnresolvedRelation if !u.isStreaming =>
            lookupV2Relation(u.multipartIdentifier, u.options, false).map {
              case r: DataSourceV2Relation => write.withNewTable(r)
              case other => throw new IllegalStateException(
                "[BUG] unexpected plan returned by `lookupV2Relation`: " + other)
            }.getOrElse(write)
          case _ => write
        }

      case alter @ AlterTable(_, _, u: UnresolvedV2Relation, _) =>
        CatalogV2Util.loadRelation(u.catalog, u.tableName)
          .map(rel => alter.copy(table = rel))
          .getOrElse(alter)

      case u: UnresolvedV2Relation =>
        CatalogV2Util.loadRelation(u.catalog, u.tableName).getOrElse(u)
    }

    /**
     * Performs the lookup of DataSourceV2 Tables from v2 catalog.
     */
    private def lookupV2Relation(
        identifier: Seq[String],
        options: CaseInsensitiveStringMap,
        isStreaming: Boolean): Option[LogicalPlan] =
      expandIdentifier(identifier) match {
        case NonSessionCatalogAndIdentifier(catalog, ident) =>
          CatalogV2Util.loadTable(catalog, ident) match {
            case Some(table) =>
              if (isStreaming) {
                Some(StreamingRelationV2(None, table.name, table, options,
                  table.schema.toAttributes, Some(catalog), Some(ident), None))
              } else {
                Some(DataSourceV2Relation.create(table, Some(catalog), Some(ident), options))
              }
            case None => None
          }
        case _ => None
      }
  }

  /**
   * Replaces [[UnresolvedRelation]]s with concrete relations from the catalog.
   */
  object ResolveRelations extends Rule[LogicalPlan] {
    // The current catalog and namespace may be different from when the view was created, we must
    // resolve the view logical plan here, with the catalog and namespace stored in view metadata.
    // This is done by keeping the catalog and namespace in `AnalysisContext`, and analyzer will
    // look at `AnalysisContext.catalogAndNamespace` when resolving relations with single-part name.
    // If `AnalysisContext.catalogAndNamespace` is non-empty, analyzer will expand single-part names
    // with it, instead of current catalog and namespace.
    def resolveViews(plan: LogicalPlan): LogicalPlan = plan match {
      // The view's child should be a logical plan parsed from the `desc.viewText`, the variable
      // `viewText` should be defined, or else we throw an error on the generation of the View
      // operator.
      case view @ View(desc, isTempView, child) if !child.resolved =>
        // Resolve all the UnresolvedRelations and Views in the child.
        val newChild = AnalysisContext.withAnalysisContext(desc) {
          val nestedViewDepth = AnalysisContext.get.nestedViewDepth
          val maxNestedViewDepth = AnalysisContext.get.maxNestedViewDepth
          if (nestedViewDepth > maxNestedViewDepth) {
            throw QueryCompilationErrors.viewDepthExceedsMaxResolutionDepthError(
              desc.identifier, maxNestedViewDepth, view)
          }
          SQLConf.withExistingConf(View.effectiveSQLConf(desc.viewSQLConfigs, isTempView)) {
            executeSameContext(child)
          }
        }
        // Fail the analysis eagerly because outside AnalysisContext, the unresolved operators
        // inside a view maybe resolved incorrectly.
        checkAnalysis(newChild)
        view.copy(child = newChild)
      case p @ SubqueryAlias(_, view: View) =>
        p.copy(child = resolveViews(view))
      case _ => plan
    }

    def apply(plan: LogicalPlan): LogicalPlan = ResolveTempViews(plan).
      resolveOperatorsUpWithPruning(AlwaysProcess.fn, ruleId) {
      case i @ InsertIntoStatement(table, _, _, _, _, _) if i.query.resolved =>
        val relation = table match {
          case u @ UnresolvedRelation(_, _, false) =>
            lookupRelation(u.multipartIdentifier, u.options, false).getOrElse(u)
          case other => other
        }

        // Inserting into a file-based temporary view is allowed.
        // (e.g., spark.read.parquet("path").createOrReplaceTempView("t").
        // Thus, we need to look at the raw plan if `relation` is a temporary view.
        unwrapRelationPlan(relation) match {
          case v: View =>
            throw QueryCompilationErrors.insertIntoViewNotAllowedError(v.desc.identifier, table)
          case other => i.copy(table = other)
        }

      // TODO (SPARK-27484): handle streaming write commands when we have them.
      case write: V2WriteCommand =>
        write.table match {
          case u: UnresolvedRelation if !u.isStreaming =>
            lookupRelation(u.multipartIdentifier, u.options, false)
              .map(EliminateSubqueryAliases(_))
              .map {
                case v: View => throw QueryCompilationErrors.writeIntoViewNotAllowedError(
                  v.desc.identifier, write)
                case u: UnresolvedCatalogRelation =>
                  throw QueryCompilationErrors.writeIntoV1TableNotAllowedError(
                    u.tableMeta.identifier, write)
                case r: DataSourceV2Relation => write.withNewTable(r)
                case other => throw new IllegalStateException(
                  "[BUG] unexpected plan returned by `lookupRelation`: " + other)
              }.getOrElse(write)
          case _ => write
        }

      case u: UnresolvedRelation =>
        lookupRelation(u.multipartIdentifier, u.options, u.isStreaming)
          .map(resolveViews).getOrElse(u)

      case u @ UnresolvedTable(identifier, cmd, relationTypeMismatchHint) =>
        lookupTableOrView(identifier).map {
          case v: ResolvedView =>
            throw QueryCompilationErrors.expectTableNotViewError(
              v, cmd, relationTypeMismatchHint, u)
          case table => table
        }.getOrElse(u)

      case u @ UnresolvedView(identifier, cmd, _, relationTypeMismatchHint) =>
        lookupTableOrView(identifier).map {
          case t: ResolvedTable =>
            throw QueryCompilationErrors.expectViewNotTableError(
              t, cmd, relationTypeMismatchHint, u)
          case view => view
        }.getOrElse(u)

      case u @ UnresolvedTableOrView(identifier, _, _) =>
        lookupTableOrView(identifier).getOrElse(u)
    }

    private def lookupTableOrView(identifier: Seq[String]): Option[LogicalPlan] = {
      expandIdentifier(identifier) match {
        case SessionCatalogAndIdentifier(catalog, ident) =>
          CatalogV2Util.loadTable(catalog, ident).map {
            case v1Table: V1Table if v1Table.v1Table.tableType == CatalogTableType.VIEW =>
              ResolvedView(ident, isTemp = false)
            case table =>
              ResolvedTable.create(catalog.asTableCatalog, ident, table)
          }
        case _ => None
      }
    }

    // Look up a relation from the session catalog with the following logic:
    // 1) If the resolved catalog is not session catalog, return None.
    // 2) If a relation is not found in the catalog, return None.
    // 3) If a v1 table is found, create a v1 relation. Otherwise, create a v2 relation.
    private def lookupRelation(
        identifier: Seq[String],
        options: CaseInsensitiveStringMap,
        isStreaming: Boolean): Option[LogicalPlan] = {
      expandIdentifier(identifier) match {
        case SessionCatalogAndIdentifier(catalog, ident) =>
          lazy val loaded = CatalogV2Util.loadTable(catalog, ident).map {
            case v1Table: V1Table =>
              if (isStreaming) {
                if (v1Table.v1Table.tableType == CatalogTableType.VIEW) {
                  throw QueryCompilationErrors.permanentViewNotSupportedByStreamingReadingAPIError(
                    identifier.quoted)
                }
                SubqueryAlias(
                  catalog.name +: ident.asMultipartIdentifier,
                  UnresolvedCatalogRelation(v1Table.v1Table, options, isStreaming = true))
              } else {
                v1SessionCatalog.getRelation(v1Table.v1Table, options)
              }
            case table =>
              if (isStreaming) {
                val v1Fallback = table match {
                  case withFallback: V2TableWithV1Fallback =>
                    Some(UnresolvedCatalogRelation(withFallback.v1Table, isStreaming = true))
                  case _ => None
                }
                SubqueryAlias(
                  catalog.name +: ident.asMultipartIdentifier,
                  StreamingRelationV2(None, table.name, table, options, table.schema.toAttributes,
                    Some(catalog), Some(ident), v1Fallback))
              } else {
                SubqueryAlias(
                  catalog.name +: ident.asMultipartIdentifier,
                  DataSourceV2Relation.create(table, Some(catalog), Some(ident), options))
              }
          }
          val key = catalog.name +: ident.namespace :+ ident.name
          AnalysisContext.get.relationCache.get(key).map(_.transform {
            case multi: MultiInstanceRelation =>
              val newRelation = multi.newInstance()
              newRelation.copyTagsFrom(multi)
              newRelation
          }).orElse {
            loaded.foreach(AnalysisContext.get.relationCache.update(key, _))
            loaded
          }
        case _ => None
      }
    }
  }

  object ResolveInsertInto extends Rule[LogicalPlan] {
    override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsWithPruning(
      AlwaysProcess.fn, ruleId) {
      case i @ InsertIntoStatement(r: DataSourceV2Relation, _, _, _, _, _)
          if i.query.resolved && i.userSpecifiedCols.isEmpty =>
        // ifPartitionNotExists is append with validation, but validation is not supported
        if (i.ifPartitionNotExists) {
          throw QueryCompilationErrors.unsupportedIfNotExistsError(r.table.name)
        }

        val partCols = partitionColumnNames(r.table)
        validatePartitionSpec(partCols, i.partitionSpec)

        val staticPartitions = i.partitionSpec.filter(_._2.isDefined).mapValues(_.get).toMap
        val query = addStaticPartitionColumns(r, i.query, staticPartitions)

        if (!i.overwrite) {
          AppendData.byPosition(r, query)
        } else if (conf.partitionOverwriteMode == PartitionOverwriteMode.DYNAMIC) {
          OverwritePartitionsDynamic.byPosition(r, query)
        } else {
          OverwriteByExpression.byPosition(r, query, staticDeleteExpression(r, staticPartitions))
        }
    }

    private def partitionColumnNames(table: Table): Seq[String] = {
      // get partition column names. in v2, partition columns are columns that are stored using an
      // identity partition transform because the partition values and the column values are
      // identical. otherwise, partition values are produced by transforming one or more source
      // columns and cannot be set directly in a query's PARTITION clause.
      table.partitioning.flatMap {
        case IdentityTransform(FieldReference(Seq(name))) => Some(name)
        case _ => None
      }
    }

    private def validatePartitionSpec(
        partitionColumnNames: Seq[String],
        partitionSpec: Map[String, Option[String]]): Unit = {
      // check that each partition name is a partition column. otherwise, it is not valid
      partitionSpec.keySet.foreach { partitionName =>
        partitionColumnNames.find(name => conf.resolver(name, partitionName)) match {
          case Some(_) =>
          case None =>
            throw QueryCompilationErrors.nonPartitionColError(partitionName)
        }
      }
    }

    private def addStaticPartitionColumns(
        relation: DataSourceV2Relation,
        query: LogicalPlan,
        staticPartitions: Map[String, String]): LogicalPlan = {

      if (staticPartitions.isEmpty) {
        query

      } else {
        // add any static value as a literal column
        val withStaticPartitionValues = {
          // for each static name, find the column name it will replace and check for unknowns.
          val outputNameToStaticName = staticPartitions.keySet.map(staticName =>
            relation.output.find(col => conf.resolver(col.name, staticName)) match {
              case Some(attr) =>
                attr.name -> staticName
              case _ =>
                throw QueryCompilationErrors.addStaticValToUnknownColError(staticName)
            }).toMap

          val queryColumns = query.output.iterator

          // for each output column, add the static value as a literal, or use the next input
          // column. this does not fail if input columns are exhausted and adds remaining columns
          // at the end. both cases will be caught by ResolveOutputRelation and will fail the
          // query with a helpful error message.
          relation.output.flatMap { col =>
            outputNameToStaticName.get(col.name).flatMap(staticPartitions.get) match {
              case Some(staticValue) =>
                // SPARK-30844: try our best to follow StoreAssignmentPolicy for static partition
                // values but not completely follow because we can't do static type checking due to
                // the reason that the parser has erased the type info of static partition values
                // and converted them to string.
                Some(Alias(AnsiCast(Literal(staticValue), col.dataType), col.name)())
              case _ if queryColumns.hasNext =>
                Some(queryColumns.next)
              case _ =>
                None
            }
          } ++ queryColumns
        }

        Project(withStaticPartitionValues, query)
      }
    }

    private def staticDeleteExpression(
        relation: DataSourceV2Relation,
        staticPartitions: Map[String, String]): Expression = {
      if (staticPartitions.isEmpty) {
        Literal(true)
      } else {
        staticPartitions.map { case (name, value) =>
          relation.output.find(col => conf.resolver(col.name, name)) match {
            case Some(attr) =>
              // the delete expression must reference the table's column names, but these attributes
              // are not available when CheckAnalysis runs because the relation is not a child of
              // the logical operation. instead, expressions are resolved after
              // ResolveOutputRelation runs, using the query's column names that will match the
              // table names at that point. because resolution happens after a future rule, create
              // an UnresolvedAttribute.
              EqualNullSafe(
                UnresolvedAttribute.quoted(attr.name),
                Cast(Literal(value), attr.dataType))
            case None =>
              throw QueryCompilationErrors.unknownStaticPartitionColError(name)
          }
        }.reduce(And)
      }
    }
  }

  /**
   * Replaces [[UnresolvedAttribute]]s with concrete [[AttributeReference]]s from
   * a logical plan node's children.
   */
  object ResolveReferences extends Rule[LogicalPlan] {

    /** Return true if there're conflicting attributes among children's outputs of a plan */
    def hasConflictingAttrs(p: LogicalPlan): Boolean = {
      p.children.length > 1 && {
        // Note that duplicated attributes are allowed within a single node,
        // e.g., df.select($"a", $"a"), so we should only check conflicting
        // attributes between nodes.
        val uniqueAttrs = mutable.HashSet[ExprId]()
        p.children.head.outputSet.foreach(a => uniqueAttrs.add(a.exprId))
        p.children.tail.exists { child =>
          val uniqueSize = uniqueAttrs.size
          val childSize = child.outputSet.size
          child.outputSet.foreach(a => uniqueAttrs.add(a.exprId))
          uniqueSize + childSize > uniqueAttrs.size
        }
      }
    }

    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      AlwaysProcess.fn, ruleId) {
      case p: LogicalPlan if !p.childrenResolved => p

      // Wait for the rule `DeduplicateRelations` to resolve conflicting attrs first.
      case p: LogicalPlan if hasConflictingAttrs(p) => p

      // If the projection list contains Stars, expand it.
      case p: Project if containsStar(p.projectList) =>
        p.copy(projectList = buildExpandedProjectList(p.projectList, p.child))
      // If the aggregate function argument contains Stars, expand it.
      case a: Aggregate if containsStar(a.aggregateExpressions) =>
        if (a.groupingExpressions.exists(_.isInstanceOf[UnresolvedOrdinal])) {
          throw QueryCompilationErrors.starNotAllowedWhenGroupByOrdinalPositionUsedError()
        } else {
          a.copy(aggregateExpressions = buildExpandedProjectList(a.aggregateExpressions, a.child))
        }
      case g: Generate if containsStar(g.generator.children) =>
        throw QueryCompilationErrors.invalidStarUsageError("explode/json_tuple/UDTF")

      // When resolve `SortOrder`s in Sort based on child, don't report errors as
      // we still have chance to resolve it based on its descendants
      case s @ Sort(ordering, global, child) if child.resolved && !s.resolved =>
        val newOrdering =
          ordering.map(order => resolveExpressionByPlanOutput(order, child).asInstanceOf[SortOrder])
        Sort(newOrdering, global, child)

      // A special case for Generate, because the output of Generate should not be resolved by
      // ResolveReferences. Attributes in the output will be resolved by ResolveGenerate.
      case g @ Generate(generator, _, _, _, _, _) if generator.resolved => g

      case g @ Generate(generator, join, outer, qualifier, output, child) =>
        val newG = resolveExpressionByPlanOutput(generator, child, throws = true)
        if (newG.fastEquals(generator)) {
          g
        } else {
          Generate(newG.asInstanceOf[Generator], join, outer, qualifier, output, child)
        }

      // Skips plan which contains deserializer expressions, as they should be resolved by another
      // rule: ResolveDeserializer.
      case plan if containsDeserializer(plan.expressions) => plan

      // SPARK-31670: Resolve Struct field in groupByExpressions and aggregateExpressions
      // with CUBE/ROLLUP will be wrapped with alias like Alias(GetStructField, name) with
      // different ExprId. This cause aggregateExpressions can't be replaced by expanded
      // groupByExpressions in `ResolveGroupingAnalytics.constructAggregateExprs()`, we trim
      // unnecessary alias of GetStructField here.
      case a: Aggregate =>
        val planForResolve = a.child match {
          // SPARK-25942: Resolves aggregate expressions with `AppendColumns`'s children, instead of
          // `AppendColumns`, because `AppendColumns`'s serializer might produce conflict attribute
          // names leading to ambiguous references exception.
          case appendColumns: AppendColumns => appendColumns
          case _ => a
        }

        val resolvedGroupingExprs = a.groupingExpressions
          .map(resolveExpressionByPlanChildren(_, planForResolve))
          .map(trimTopLevelGetStructFieldAlias)

        val resolvedAggExprs = a.aggregateExpressions
          .map(resolveExpressionByPlanChildren(_, planForResolve))
            .map(_.asInstanceOf[NamedExpression])

        a.copy(resolvedGroupingExprs, resolvedAggExprs, a.child)

      case o: OverwriteByExpression if o.table.resolved =>
        // The delete condition of `OverwriteByExpression` will be passed to the table
        // implementation and should be resolved based on the table schema.
        o.copy(deleteExpr = resolveExpressionByPlanOutput(o.deleteExpr, o.table))

      case m @ MergeIntoTable(targetTable, sourceTable, _, _, _)
        if !m.resolved && targetTable.resolved && sourceTable.resolved =>

        EliminateSubqueryAliases(targetTable) match {
          case r: NamedRelation if r.skipSchemaResolution =>
            // Do not resolve the expression if the target table accepts any schema.
            // This allows data sources to customize their own resolution logic using
            // custom resolution rules.
            m

          case _ =>
            val newMatchedActions = m.matchedActions.map {
              case DeleteAction(deleteCondition) =>
                val resolvedDeleteCondition = deleteCondition.map(
                  resolveExpressionByPlanChildren(_, m))
                DeleteAction(resolvedDeleteCondition)
              case UpdateAction(updateCondition, assignments) =>
                val resolvedUpdateCondition = updateCondition.map(
                  resolveExpressionByPlanChildren(_, m))
                UpdateAction(
                  resolvedUpdateCondition,
                  // The update value can access columns from both target and source tables.
                  resolveAssignments(assignments, m, resolveValuesWithSourceOnly = false))
              case UpdateStarAction(updateCondition) =>
                val assignments = targetTable.output.map { attr =>
                  Assignment(attr, UnresolvedAttribute(Seq(attr.name)))
                }
                UpdateAction(
                  updateCondition.map(resolveExpressionByPlanChildren(_, m)),
                  // For UPDATE *, the value must from source table.
                  resolveAssignments(assignments, m, resolveValuesWithSourceOnly = true))
              case o => o
            }
            val newNotMatchedActions = m.notMatchedActions.map {
              case InsertAction(insertCondition, assignments) =>
                // The insert action is used when not matched, so its condition and value can only
                // access columns from the source table.
                val resolvedInsertCondition = insertCondition.map(
                  resolveExpressionByPlanChildren(_, Project(Nil, m.sourceTable)))
                InsertAction(
                  resolvedInsertCondition,
                  resolveAssignments(assignments, m, resolveValuesWithSourceOnly = true))
              case InsertStarAction(insertCondition) =>
                // The insert action is used when not matched, so its condition and value can only
                // access columns from the source table.
                val resolvedInsertCondition = insertCondition.map(
                  resolveExpressionByPlanChildren(_, Project(Nil, m.sourceTable)))
                val assignments = targetTable.output.map { attr =>
                  Assignment(attr, UnresolvedAttribute(Seq(attr.name)))
                }
                InsertAction(
                  resolvedInsertCondition,
                  resolveAssignments(assignments, m, resolveValuesWithSourceOnly = true))
              case o => o
            }
            val resolvedMergeCondition = resolveExpressionByPlanChildren(m.mergeCondition, m)
            m.copy(mergeCondition = resolvedMergeCondition,
              matchedActions = newMatchedActions,
              notMatchedActions = newNotMatchedActions)
        }

      // Skip the having clause here, this will be handled in ResolveAggregateFunctions.
      case h: UnresolvedHaving => h

      case q: LogicalPlan =>
        logTrace(s"Attempting to resolve ${q.simpleString(conf.maxToStringFields)}")
        q.mapExpressions(resolveExpressionByPlanChildren(_, q))
    }

    def resolveAssignments(
        assignments: Seq[Assignment],
        mergeInto: MergeIntoTable,
        resolveValuesWithSourceOnly: Boolean): Seq[Assignment] = {
      assignments.map { assign =>
        val resolvedKey = assign.key match {
          case c if !c.resolved =>
            resolveMergeExprOrFail(c, Project(Nil, mergeInto.targetTable))
          case o => o
        }
        val resolvedValue = assign.value match {
          // The update values may contain target and/or source references.
          case c if !c.resolved =>
            if (resolveValuesWithSourceOnly) {
              resolveMergeExprOrFail(c, Project(Nil, mergeInto.sourceTable))
            } else {
              resolveMergeExprOrFail(c, mergeInto)
            }
          case o => o
        }
        Assignment(resolvedKey, resolvedValue)
      }
    }

    private def resolveMergeExprOrFail(e: Expression, p: LogicalPlan): Expression = {
      val resolved = resolveExpressionByPlanChildren(e, p)
      resolved.references.filter(!_.resolved).foreach { a =>
        // Note: This will throw error only on unresolved attribute issues,
        // not other resolution errors like mismatched data types.
        val cols = p.inputSet.toSeq.map(_.sql).mkString(", ")
        a.failAnalysis(s"cannot resolve ${a.sql} in MERGE command given columns [$cols]")
      }
      resolved
    }

    // This method is used to trim groupByExpressions/selectedGroupByExpressions's top-level
    // GetStructField Alias. Since these expression are not NamedExpression originally,
    // we are safe to trim top-level GetStructField Alias.
    def trimTopLevelGetStructFieldAlias(e: Expression): Expression = {
      e match {
        case Alias(s: GetStructField, _) => s
        case other => other
      }
    }

    /**
     * Build a project list for Project/Aggregate and expand the star if possible
     */
    private def buildExpandedProjectList(
      exprs: Seq[NamedExpression],
      child: LogicalPlan): Seq[NamedExpression] = {
      exprs.flatMap {
        // Using Dataframe/Dataset API: testData2.groupBy($"a", $"b").agg($"*")
        case s: Star => s.expand(child, resolver)
        // Using SQL API without running ResolveAlias: SELECT * FROM testData2 group by a, b
        case UnresolvedAlias(s: Star, _) => s.expand(child, resolver)
        case o if containsStar(o :: Nil) => expandStarExpression(o, child) :: Nil
        case o => o :: Nil
      }.map(_.asInstanceOf[NamedExpression])
    }

    /**
     * Returns true if `exprs` contains a [[Star]].
     */
    def containsStar(exprs: Seq[Expression]): Boolean =
      exprs.exists(_.collect { case _: Star => true }.nonEmpty)

    /**
     * Expands the matching attribute.*'s in `child`'s output.
     */
    def expandStarExpression(expr: Expression, child: LogicalPlan): Expression = {
      expr.transformUp {
        case f1: UnresolvedFunction if containsStar(f1.arguments) =>
          // SPECIAL CASE: We want to block count(tblName.*) because in spark, count(tblName.*) will
          // be expanded while count(*) will be converted to count(1). They will produce different
          // results and confuse users if there is any null values. For count(t1.*, t2.*), it is
          // still allowed, since it's well-defined in spark.
          if (!conf.allowStarWithSingleTableIdentifierInCount &&
              f1.nameParts == Seq("count") &&
              f1.arguments.length == 1) {
            f1.arguments.foreach {
              case u: UnresolvedStar if u.isQualifiedByTable(child, resolver) =>
                throw QueryCompilationErrors
                  .singleTableStarInCountNotAllowedError(u.target.get.mkString("."))
              case _ => // do nothing
            }
          }
          f1.copy(arguments = f1.arguments.flatMap {
            case s: Star => s.expand(child, resolver)
            case o => o :: Nil
          })
        case c: CreateNamedStruct if containsStar(c.valExprs) =>
          val newChildren = c.children.grouped(2).flatMap {
            case Seq(k, s : Star) => CreateStruct(s.expand(child, resolver)).children
            case kv => kv
          }
          c.copy(children = newChildren.toList )
        case c: CreateArray if containsStar(c.children) =>
          c.copy(children = c.children.flatMap {
            case s: Star => s.expand(child, resolver)
            case o => o :: Nil
          })
        case p: Murmur3Hash if containsStar(p.children) =>
          p.copy(children = p.children.flatMap {
            case s: Star => s.expand(child, resolver)
            case o => o :: Nil
          })
        case p: XxHash64 if containsStar(p.children) =>
          p.copy(children = p.children.flatMap {
            case s: Star => s.expand(child, resolver)
            case o => o :: Nil
          })
        // count(*) has been replaced by count(1)
        case o if containsStar(o.children) =>
          throw QueryCompilationErrors.invalidStarUsageError(s"expression '${o.prettyName}'")
      }
    }
  }

  private def containsDeserializer(exprs: Seq[Expression]): Boolean = {
    exprs.exists(_.find(_.isInstanceOf[UnresolvedDeserializer]).isDefined)
  }

  // support CURRENT_DATE, CURRENT_TIMESTAMP, and grouping__id
  private val literalFunctions: Seq[(String, () => Expression, Expression => String)] = Seq(
    (CurrentDate().prettyName, () => CurrentDate(), toPrettySQL(_)),
    (CurrentTimestamp().prettyName, () => CurrentTimestamp(), toPrettySQL(_)),
    (CurrentUser().prettyName, () => CurrentUser(), toPrettySQL),
    (VirtualColumn.hiveGroupingIdName, () => GroupingID(Nil), _ => VirtualColumn.hiveGroupingIdName)
  )

  /**
   * Literal functions do not require the user to specify braces when calling them
   * When an attributes is not resolvable, we try to resolve it as a literal function.
   */
  private def resolveLiteralFunction(nameParts: Seq[String]): Option[NamedExpression] = {
    if (nameParts.length != 1) return None
    val name = nameParts.head
    literalFunctions.find(func => caseInsensitiveResolution(func._1, name)).map {
      case (_, getFuncExpr, getAliasName) =>
        val funcExpr = getFuncExpr()
        Alias(funcExpr, getAliasName(funcExpr))()
    }
  }

  /**
   * Resolves `UnresolvedAttribute`, `GetColumnByOrdinal` and extract value expressions(s) by
   * traversing the input expression in top-down manner. It must be top-down because we need to
   * skip over unbound lambda function expression. The lambda expressions are resolved in a
   * different place [[ResolveLambdaVariables]].
   *
   * Example :
   * SELECT transform(array(1, 2, 3), (x, i) -> x + i)"
   *
   * In the case above, x and i are resolved as lambda variables in [[ResolveLambdaVariables]].
   */
  private def resolveExpression(
      expr: Expression,
      resolveColumnByName: Seq[String] => Option[Expression],
      getAttrCandidates: () => Seq[Attribute],
      throws: Boolean): Expression = {
    def innerResolve(e: Expression, isTopLevel: Boolean): Expression = {
      if (e.resolved) return e
      e match {
        case f: LambdaFunction if !f.bound => f

        case GetColumnByOrdinal(ordinal, _) =>
          val attrCandidates = getAttrCandidates()
          assert(ordinal >= 0 && ordinal < attrCandidates.length)
          attrCandidates(ordinal)

        case GetViewColumnByNameAndOrdinal(viewName, colName, ordinal, expectedNumCandidates) =>
          val attrCandidates = getAttrCandidates()
          val matched = attrCandidates.filter(a => resolver(a.name, colName))
          if (matched.length != expectedNumCandidates) {
            throw QueryCompilationErrors.incompatibleViewSchemaChange(
              viewName, colName, expectedNumCandidates, matched)
          }
          matched(ordinal)

        case u @ UnresolvedAttribute(nameParts) =>
          val result = withPosition(u) {
            resolveColumnByName(nameParts).orElse(resolveLiteralFunction(nameParts)).map {
              // We trim unnecessary alias here. Note that, we cannot trim the alias at top-level,
              // as we should resolve `UnresolvedAttribute` to a named expression. The caller side
              // can trim the top-level alias if it's safe to do so. Since we will call
              // CleanupAliases later in Analyzer, trim non top-level unnecessary alias is safe.
              case Alias(child, _) if !isTopLevel => child
              case other => other
            }.getOrElse(u)
          }
          logDebug(s"Resolving $u to $result")
          result

        case u @ UnresolvedExtractValue(child, fieldName) =>
          val newChild = innerResolve(child, isTopLevel = false)
          if (newChild.resolved) {
            ExtractValue(newChild, fieldName, resolver)
          } else {
            u.copy(child = newChild)
          }

        case _ => e.mapChildren(innerResolve(_, isTopLevel = false))
      }
    }

    try {
      innerResolve(expr, isTopLevel = true)
    } catch {
      case _: AnalysisException if !throws => expr
    }
  }

  /**
   * Resolves `UnresolvedAttribute`, `GetColumnByOrdinal` and extract value expressions(s) by the
   * input plan's output attributes. In order to resolve the nested fields correctly, this function
   * makes use of `throws` parameter to control when to raise an AnalysisException.
   *
   * Example :
   * SELECT * FROM t ORDER BY a.b
   *
   * In the above example, after `a` is resolved to a struct-type column, we may fail to resolve `b`
   * if there is no such nested field named "b". We should not fail and wait for other rules to
   * resolve it if possible.
   */
  def resolveExpressionByPlanOutput(
      expr: Expression,
      plan: LogicalPlan,
      throws: Boolean = false): Expression = {
    resolveExpression(
      expr,
      resolveColumnByName = nameParts => {
        plan.resolve(nameParts, resolver)
      },
      getAttrCandidates = () => plan.output,
      throws = throws)
  }

  /**
   * Resolves `UnresolvedAttribute`, `GetColumnByOrdinal` and extract value expressions(s) by the
   * input plan's children output attributes.
   *
   * @param e The expression need to be resolved.
   * @param q The LogicalPlan whose children are used to resolve expression's attribute.
   * @return resolved Expression.
   */
  def resolveExpressionByPlanChildren(
      e: Expression,
      q: LogicalPlan): Expression = {
    resolveExpression(
      e,
      resolveColumnByName = nameParts => {
        q.resolveChildren(nameParts, resolver)
      },
      getAttrCandidates = () => {
        assert(q.children.length == 1)
        q.children.head.output
      },
      throws = true)
  }

  /**
   * In many dialects of SQL it is valid to use ordinal positions in order/sort by and group by
   * clauses. This rule is to convert ordinal positions to the corresponding expressions in the
   * select list. This support is introduced in Spark 2.0.
   *
   * - When the sort references or group by expressions are not integer but foldable expressions,
   * just ignore them.
   * - When spark.sql.orderByOrdinal/spark.sql.groupByOrdinal is set to false, ignore the position
   * numbers too.
   *
   * Before the release of Spark 2.0, the literals in order/sort by and group by clauses
   * have no effect on the results.
   */
  object ResolveOrdinalInOrderByAndGroupBy extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsPattern(UNRESOLVED_ORDINAL), ruleId) {
      case p if !p.childrenResolved => p
      // Replace the index with the related attribute for ORDER BY,
      // which is a 1-base position of the projection list.
      case Sort(orders, global, child)
        if orders.exists(_.child.isInstanceOf[UnresolvedOrdinal]) =>
        val newOrders = orders map {
          case s @ SortOrder(UnresolvedOrdinal(index), direction, nullOrdering, _) =>
            if (index > 0 && index <= child.output.size) {
              SortOrder(child.output(index - 1), direction, nullOrdering, Seq.empty)
            } else {
              throw QueryCompilationErrors.orderByPositionRangeError(index, child.output.size, s)
            }
          case o => o
        }
        Sort(newOrders, global, child)

      // Replace the index with the corresponding expression in aggregateExpressions. The index is
      // a 1-base position of aggregateExpressions, which is output columns (select expression)
      case Aggregate(groups, aggs, child) if aggs.forall(_.resolved) &&
        groups.exists(containUnresolvedOrdinal) =>
        val newGroups = groups.map(resolveGroupByExpressionOrdinal(_, aggs))
        Aggregate(newGroups, aggs, child)
    }

    private def containUnresolvedOrdinal(e: Expression): Boolean = e match {
      case _: UnresolvedOrdinal => true
      case gs: BaseGroupingSets => gs.children.exists(containUnresolvedOrdinal)
      case _ => false
    }

    private def resolveGroupByExpressionOrdinal(
        expr: Expression,
        aggs: Seq[Expression]): Expression = expr match {
      case ordinal @ UnresolvedOrdinal(index) =>
        withPosition(ordinal) {
          if (index > 0 && index <= aggs.size) {
            val ordinalExpr = aggs(index - 1)
            if (ordinalExpr.find(_.isInstanceOf[AggregateExpression]).nonEmpty) {
              throw QueryCompilationErrors.groupByPositionRefersToAggregateFunctionError(
                index, ordinalExpr)
            } else {
              ordinalExpr
            }
          } else {
            throw QueryCompilationErrors.groupByPositionRangeError(index, aggs.size)
          }
        }
      case gs: BaseGroupingSets =>
        gs.withNewChildren(gs.children.map(resolveGroupByExpressionOrdinal(_, aggs)))
      case others => others
    }
  }

  /**
   * Replace unresolved expressions in grouping keys with resolved ones in SELECT clauses.
   * This rule is expected to run after [[ResolveReferences]] applied.
   */
  object ResolveAggAliasInGroupBy extends Rule[LogicalPlan] {

    // This is a strict check though, we put this to apply the rule only if the expression is not
    // resolvable by child.
    private def notResolvableByChild(attrName: String, child: LogicalPlan): Boolean = {
      !child.output.exists(a => resolver(a.name, attrName))
    }

    private def mayResolveAttrByAggregateExprs(
        exprs: Seq[Expression], aggs: Seq[NamedExpression], child: LogicalPlan): Seq[Expression] = {
      exprs.map { _.transformWithPruning(_.containsPattern(UNRESOLVED_ATTRIBUTE)) {
        case u: UnresolvedAttribute if notResolvableByChild(u.name, child) =>
          aggs.find(ne => resolver(ne.name, u.name)).getOrElse(u)
      }}
    }

    // Group by alias is not allowed in ANSI mode.
    private def allowGroupByAlias: Boolean = conf.groupByAliases && !conf.ansiEnabled

    override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      // mayResolveAttrByAggregateExprs requires the TreePattern UNRESOLVED_ATTRIBUTE.
      _.containsAllPatterns(AGGREGATE, UNRESOLVED_ATTRIBUTE), ruleId) {
      case agg @ Aggregate(groups, aggs, child)
          if allowGroupByAlias && child.resolved && aggs.forall(_.resolved) &&
            groups.exists(!_.resolved) =>
        agg.copy(groupingExpressions = mayResolveAttrByAggregateExprs(groups, aggs, child))
    }
  }

  /**
   * In many dialects of SQL it is valid to sort by attributes that are not present in the SELECT
   * clause.  This rule detects such queries and adds the required attributes to the original
   * projection, so that they will be available during sorting. Another projection is added to
   * remove these attributes after sorting.
   *
   * The HAVING clause could also used a grouping columns that is not presented in the SELECT.
   */
  object ResolveMissingReferences extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsAnyPattern(SORT, FILTER, REPARTITION_OPERATION), ruleId) {
      // Skip sort with aggregate. This will be handled in ResolveAggregateFunctions
      case sa @ Sort(_, _, child: Aggregate) => sa

      case s @ Sort(order, _, child)
          if (!s.resolved || s.missingInput.nonEmpty) && child.resolved =>
        val (newOrder, newChild) = resolveExprsAndAddMissingAttrs(order, child)
        val ordering = newOrder.map(_.asInstanceOf[SortOrder])
        if (child.output == newChild.output) {
          s.copy(order = ordering)
        } else {
          // Add missing attributes and then project them away.
          val newSort = s.copy(order = ordering, child = newChild)
          Project(child.output, newSort)
        }

      case f @ Filter(cond, child) if (!f.resolved || f.missingInput.nonEmpty) && child.resolved =>
        val (newCond, newChild) = resolveExprsAndAddMissingAttrs(Seq(cond), child)
        if (child.output == newChild.output) {
          f.copy(condition = newCond.head)
        } else {
          // Add missing attributes and then project them away.
          val newFilter = Filter(newCond.head, newChild)
          Project(child.output, newFilter)
        }

      case r @ RepartitionByExpression(partitionExprs, child, _)
          if (!r.resolved || r.missingInput.nonEmpty) && child.resolved =>
        val (newPartitionExprs, newChild) = resolveExprsAndAddMissingAttrs(partitionExprs, child)
        if (child.output == newChild.output) {
          r.copy(newPartitionExprs, newChild)
        } else {
          Project(child.output, r.copy(newPartitionExprs, newChild))
        }
    }

    /**
     * This method tries to resolve expressions and find missing attributes recursively.
     * Specifically, when the expressions used in `Sort` or `Filter` contain unresolved attributes
     * or resolved attributes which are missing from child output. This method tries to find the
     * missing attributes and add them into the projection.
     */
    private def resolveExprsAndAddMissingAttrs(
        exprs: Seq[Expression], plan: LogicalPlan): (Seq[Expression], LogicalPlan) = {
      // Missing attributes can be unresolved attributes or resolved attributes which are not in
      // the output attributes of the plan.
      if (exprs.forall(e => e.resolved && e.references.subsetOf(plan.outputSet))) {
        (exprs, plan)
      } else {
        plan match {
          case p: Project =>
            // Resolving expressions against current plan.
            val maybeResolvedExprs = exprs.map(resolveExpressionByPlanOutput(_, p))
            // Recursively resolving expressions on the child of current plan.
            val (newExprs, newChild) = resolveExprsAndAddMissingAttrs(maybeResolvedExprs, p.child)
            // If some attributes used by expressions are resolvable only on the rewritten child
            // plan, we need to add them into original projection.
            val missingAttrs = (AttributeSet(newExprs) -- p.outputSet).intersect(newChild.outputSet)
            (newExprs, Project(p.projectList ++ missingAttrs, newChild))

          case a @ Aggregate(groupExprs, aggExprs, child) =>
            val maybeResolvedExprs = exprs.map(resolveExpressionByPlanOutput(_, a))
            val (newExprs, newChild) = resolveExprsAndAddMissingAttrs(maybeResolvedExprs, child)
            val missingAttrs = (AttributeSet(newExprs) -- a.outputSet).intersect(newChild.outputSet)
            if (missingAttrs.forall(attr => groupExprs.exists(_.semanticEquals(attr)))) {
              // All the missing attributes are grouping expressions, valid case.
              (newExprs, a.copy(aggregateExpressions = aggExprs ++ missingAttrs, child = newChild))
            } else {
              // Need to add non-grouping attributes, invalid case.
              (exprs, a)
            }

          case g: Generate =>
            val maybeResolvedExprs = exprs.map(resolveExpressionByPlanOutput(_, g))
            val (newExprs, newChild) = resolveExprsAndAddMissingAttrs(maybeResolvedExprs, g.child)
            (newExprs, g.copy(unrequiredChildIndex = Nil, child = newChild))

          // For `Distinct` and `SubqueryAlias`, we can't recursively resolve and add attributes
          // via its children.
          case u: UnaryNode if !u.isInstanceOf[Distinct] && !u.isInstanceOf[SubqueryAlias] =>
            val maybeResolvedExprs = exprs.map(resolveExpressionByPlanOutput(_, u))
            val (newExprs, newChild) = resolveExprsAndAddMissingAttrs(maybeResolvedExprs, u.child)
            (newExprs, u.withNewChildren(Seq(newChild)))

          // For other operators, we can't recursively resolve and add attributes via its children.
          case other =>
            (exprs.map(resolveExpressionByPlanOutput(_, other)), other)
        }
      }
    }
  }

  /**
   * Checks whether a function identifier referenced by an [[UnresolvedFunction]] is defined in the
   * function registry. Note that this rule doesn't try to resolve the [[UnresolvedFunction]]. It
   * only performs simple existence check according to the function identifier to quickly identify
   * undefined functions without triggering relation resolution, which may incur potentially
   * expensive partition/schema discovery process in some cases.
   * In order to avoid duplicate external functions lookup, the external function identifier will
   * store in the local hash set externalFunctionNameSet.
   * @see [[ResolveFunctions]]
   * @see https://issues.apache.org/jira/browse/SPARK-19737
   */
  object LookupFunctions extends Rule[LogicalPlan] {
    override def apply(plan: LogicalPlan): LogicalPlan = {
      val externalFunctionNameSet = new mutable.HashSet[FunctionIdentifier]()
      plan.resolveExpressionsWithPruning(_.containsAnyPattern(UNRESOLVED_FUNCTION)) {
        case f @ UnresolvedFunction(AsFunctionIdentifier(ident), _, _, _, _) =>
          if (externalFunctionNameSet.contains(normalizeFuncName(ident)) ||
            v1SessionCatalog.isRegisteredFunction(ident)) {
            f
          } else if (v1SessionCatalog.isPersistentFunction(ident)) {
            externalFunctionNameSet.add(normalizeFuncName(ident))
            f
          } else {
            withPosition(f) {
              throw new NoSuchFunctionException(
                ident.database.getOrElse(v1SessionCatalog.getCurrentDatabase),
                ident.funcName)
            }
          }
      }
    }

    def normalizeFuncName(name: FunctionIdentifier): FunctionIdentifier = {
      val funcName = if (conf.caseSensitiveAnalysis) {
        name.funcName
      } else {
        name.funcName.toLowerCase(Locale.ROOT)
      }

      val databaseName = name.database match {
        case Some(a) => formatDatabaseName(a)
        case None => v1SessionCatalog.getCurrentDatabase
      }

      FunctionIdentifier(funcName, Some(databaseName))
    }

    protected def formatDatabaseName(name: String): String = {
      if (conf.caseSensitiveAnalysis) name else name.toLowerCase(Locale.ROOT)
    }
  }

  /**
   * Replaces [[UnresolvedFunc]]s with concrete [[LogicalPlan]]s.
   * Replaces [[UnresolvedFunction]]s with concrete [[Expression]]s.
   */
  object ResolveFunctions extends Rule[LogicalPlan] {
    val trimWarningEnabled = new AtomicBoolean(true)
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsAnyPattern(UNRESOLVED_FUNC, UNRESOLVED_FUNCTION, GENERATOR), ruleId) {
      // Resolve functions with concrete relations from v2 catalog.
      case UnresolvedFunc(multipartIdent) =>
        val funcIdent = parseSessionCatalogFunctionIdentifier(multipartIdent)
        ResolvedFunc(Identifier.of(funcIdent.database.toArray, funcIdent.funcName))

      case q: LogicalPlan =>
        q.transformExpressionsWithPruning(
          _.containsAnyPattern(UNRESOLVED_FUNCTION, GENERATOR), ruleId) {
          case u if !u.childrenResolved => u // Skip until children are resolved.
          case u @ UnresolvedGenerator(name, children) =>
            withPosition(u) {
              v1SessionCatalog.lookupFunction(name, children) match {
                case generator: Generator => generator
                case other => throw QueryCompilationErrors.generatorNotExpectedError(
                  name, other.getClass.getCanonicalName)
              }
            }

          case u @ UnresolvedFunction(AsFunctionIdentifier(ident), arguments, isDistinct, filter,
            ignoreNulls) => withPosition(u) {
              v1SessionCatalog.lookupFunction(ident, arguments) match {
                // AggregateWindowFunctions are AggregateFunctions that can only be evaluated within
                // the context of a Window clause. They do not need to be wrapped in an
                // AggregateExpression.
                case wf: AggregateWindowFunction =>
                  if (isDistinct) {
                    throw QueryCompilationErrors.functionWithUnsupportedSyntaxError(
                      wf.prettyName, "DISTINCT")
                  } else if (filter.isDefined) {
                    throw QueryCompilationErrors.functionWithUnsupportedSyntaxError(
                      wf.prettyName, "FILTER clause")
                  } else if (ignoreNulls) {
                    wf match {
                      case nthValue: NthValue =>
                        nthValue.copy(ignoreNulls = ignoreNulls)
                      case _ =>
                        throw QueryCompilationErrors.functionWithUnsupportedSyntaxError(
                          wf.prettyName, "IGNORE NULLS")
                    }
                  } else {
                    wf
                  }
                case owf: FrameLessOffsetWindowFunction =>
                  if (isDistinct) {
                    throw QueryCompilationErrors.functionWithUnsupportedSyntaxError(
                      owf.prettyName, "DISTINCT")
                  } else if (filter.isDefined) {
                    throw QueryCompilationErrors.functionWithUnsupportedSyntaxError(
                      owf.prettyName, "FILTER clause")
                  } else if (ignoreNulls) {
                    owf match {
                      case lead: Lead =>
                        lead.copy(ignoreNulls = ignoreNulls)
                      case lag: Lag =>
                        lag.copy(ignoreNulls = ignoreNulls)
                    }
                  } else {
                    owf
                  }
                // We get an aggregate function, we need to wrap it in an AggregateExpression.
                case agg: AggregateFunction =>
                  if (filter.isDefined && !filter.get.deterministic) {
                    throw QueryCompilationErrors.nonDeterministicFilterInAggregateError
                  }
                  if (ignoreNulls) {
                    val aggFunc = agg match {
                      case first: First => first.copy(ignoreNulls = ignoreNulls)
                      case last: Last => last.copy(ignoreNulls = ignoreNulls)
                      case _ =>
                        throw QueryCompilationErrors.functionWithUnsupportedSyntaxError(
                          agg.prettyName, "IGNORE NULLS")
                    }
                    AggregateExpression(aggFunc, Complete, isDistinct, filter)
                  } else {
                    AggregateExpression(agg, Complete, isDistinct, filter)
                  }
                // This function is not an aggregate function, just return the resolved one.
                case other if isDistinct =>
                  throw QueryCompilationErrors.functionWithUnsupportedSyntaxError(
                    other.prettyName, "DISTINCT")
                case other if filter.isDefined =>
                  throw QueryCompilationErrors.functionWithUnsupportedSyntaxError(
                    other.prettyName, "FILTER clause")
                case other if ignoreNulls =>
                  throw QueryCompilationErrors.functionWithUnsupportedSyntaxError(
                    other.prettyName, "IGNORE NULLS")
                case e: String2TrimExpression if arguments.size == 2 =>
                  if (trimWarningEnabled.get) {
                    log.warn("Two-parameter TRIM/LTRIM/RTRIM function signatures are deprecated." +
                      " Use SQL syntax `TRIM((BOTH | LEADING | TRAILING)? trimStr FROM str)`" +
                      " instead.")
                    trimWarningEnabled.set(false)
                  }
                  e
                case other =>
                  other
              }
          }

          case u @ UnresolvedFunction(nameParts, arguments, isDistinct, filter, ignoreNulls) =>
            withPosition(u) {
              expandIdentifier(nameParts) match {
                case NonSessionCatalogAndIdentifier(catalog, ident) =>
                  if (!catalog.isFunctionCatalog) {
                    throw QueryCompilationErrors.lookupFunctionInNonFunctionCatalogError(
                      ident, catalog)
                  }

                  val unbound = catalog.asFunctionCatalog.loadFunction(ident)
                  val inputType = StructType(arguments.zipWithIndex.map {
                    case (exp, pos) => StructField(s"_$pos", exp.dataType, exp.nullable)
                  })
                  val bound = try {
                    unbound.bind(inputType)
                  } catch {
                    case unsupported: UnsupportedOperationException =>
                      throw QueryCompilationErrors.functionCannotProcessInputError(
                        unbound, arguments, unsupported)
                  }

                  if (bound.inputTypes().length != arguments.length) {
                    throw QueryCompilationErrors.v2FunctionInvalidInputTypeLengthError(
                      bound, arguments)
                  }

                  bound match {
                    case scalarFunc: ScalarFunction[_] =>
                      processV2ScalarFunction(scalarFunc, arguments, isDistinct,
                        filter, ignoreNulls)
                    case aggFunc: V2AggregateFunction[_, _] =>
                      processV2AggregateFunction(aggFunc, arguments, isDistinct, filter,
                        ignoreNulls)
                    case _ =>
                      failAnalysis(s"Function '${bound.name()}' does not implement ScalarFunction" +
                        s" or AggregateFunction")
                  }

                case _ => u
              }
            }
        }
    }

    private def processV2ScalarFunction(
        scalarFunc: ScalarFunction[_],
        arguments: Seq[Expression],
        isDistinct: Boolean,
        filter: Option[Expression],
        ignoreNulls: Boolean): Expression = {
      if (isDistinct) {
        throw QueryCompilationErrors.functionWithUnsupportedSyntaxError(
          scalarFunc.name(), "DISTINCT")
      } else if (filter.isDefined) {
        throw QueryCompilationErrors.functionWithUnsupportedSyntaxError(
          scalarFunc.name(), "FILTER clause")
      } else if (ignoreNulls) {
        throw QueryCompilationErrors.functionWithUnsupportedSyntaxError(
          scalarFunc.name(), "IGNORE NULLS")
      } else {
        val declaredInputTypes = scalarFunc.inputTypes().toSeq
        val argClasses = declaredInputTypes.map(ScalaReflection.dataTypeJavaClass)
        findMethod(scalarFunc, MAGIC_METHOD_NAME, argClasses) match {
          case Some(m) if Modifier.isStatic(m.getModifiers) =>
            StaticInvoke(scalarFunc.getClass, scalarFunc.resultType(),
              MAGIC_METHOD_NAME, arguments, inputTypes = declaredInputTypes,
                propagateNull = false, returnNullable = scalarFunc.isResultNullable)
          case Some(_) =>
            val caller = Literal.create(scalarFunc, ObjectType(scalarFunc.getClass))
            Invoke(caller, MAGIC_METHOD_NAME, scalarFunc.resultType(),
              arguments, methodInputTypes = declaredInputTypes, propagateNull = false,
              returnNullable = scalarFunc.isResultNullable)
          case _ =>
            // TODO: handle functions defined in Scala too - in Scala, even if a
            //  subclass do not override the default method in parent interface
            //  defined in Java, the method can still be found from
            //  `getDeclaredMethod`.
            findMethod(scalarFunc, "produceResult", Seq(classOf[InternalRow])) match {
              case Some(_) =>
                ApplyFunctionExpression(scalarFunc, arguments)
              case _ =>
                failAnalysis(s"ScalarFunction '${scalarFunc.name()}' neither implement" +
                  s" magic method nor override 'produceResult'")
            }
        }
      }
    }

    private def processV2AggregateFunction(
        aggFunc: V2AggregateFunction[_, _],
        arguments: Seq[Expression],
        isDistinct: Boolean,
        filter: Option[Expression],
        ignoreNulls: Boolean): Expression = {
      if (ignoreNulls) {
        throw QueryCompilationErrors.functionWithUnsupportedSyntaxError(
          aggFunc.name(), "IGNORE NULLS")
      }
      val aggregator = V2Aggregator(aggFunc, arguments)
      AggregateExpression(aggregator, Complete, isDistinct, filter)
    }

    /**
     * Check if the input `fn` implements the given `methodName` with parameter types specified
     * via `argClasses`.
     */
    private def findMethod(
        fn: BoundFunction,
        methodName: String,
        argClasses: Seq[Class[_]]): Option[Method] = {
      val cls = fn.getClass
      try {
        Some(cls.getDeclaredMethod(methodName, argClasses: _*))
      } catch {
        case _: NoSuchMethodException =>
          None
      }
    }
  }

  /**
   * This rule resolves and rewrites subqueries inside expressions.
   *
   * Note: CTEs are handled in CTESubstitution.
   */
  object ResolveSubquery extends Rule[LogicalPlan] with PredicateHelper {

    /**
     * Wrap attributes in the expression with [[OuterReference]]s.
     */
    private def wrapOuterReference[E <: Expression](e: E): E = {
      e.transform { case a: Attribute => OuterReference(a) }.asInstanceOf[E]
    }

    /**
     * Resolve the correlated expressions in a subquery by using the an outer plans' references. All
     * resolved outer references are wrapped in an [[OuterReference]]
     */
    private def resolveOuterReferences(plan: LogicalPlan, outer: LogicalPlan): LogicalPlan = {
      plan.resolveOperatorsDownWithPruning(_.containsPattern(UNRESOLVED_ATTRIBUTE)) {
        case q: LogicalPlan if q.childrenResolved && !q.resolved =>
          q.transformExpressionsWithPruning(_.containsPattern(UNRESOLVED_ATTRIBUTE)) {
            case u @ UnresolvedAttribute(nameParts) =>
              withPosition(u) {
                try {
                  outer.resolve(nameParts, resolver) match {
                    case Some(outerAttr) => wrapOuterReference(outerAttr)
                    case None => u
                  }
                } catch {
                  case _: AnalysisException => u
                }
              }
          }
      }
    }

    /**
     * Resolves the subquery plan that is referenced in a subquery expression. The normal
     * attribute references are resolved using regular analyzer and the outer references are
     * resolved from the outer plans using the resolveOuterReferences method.
     *
     * Outer references from the correlated predicates are updated as children of
     * Subquery expression.
     */
    private def resolveSubQuery(
        e: SubqueryExpression,
        plans: Seq[LogicalPlan])(
        f: (LogicalPlan, Seq[Expression]) => SubqueryExpression): SubqueryExpression = {
      // Step 1: Resolve the outer expressions.
      var previous: LogicalPlan = null
      var current = e.plan
      do {
        // Try to resolve the subquery plan using the regular analyzer.
        previous = current
        current = executeSameContext(current)

        // Use the outer references to resolve the subquery plan if it isn't resolved yet.
        val i = plans.iterator
        val afterResolve = current
        while (!current.resolved && current.fastEquals(afterResolve) && i.hasNext) {
          current = resolveOuterReferences(current, i.next())
        }
      } while (!current.resolved && !current.fastEquals(previous))

      // Step 2: If the subquery plan is fully resolved, pull the outer references and record
      // them as children of SubqueryExpression.
      if (current.resolved) {
        // Record the outer references as children of subquery expression.
        f(current, SubExprUtils.getOuterReferences(current))
      } else {
        e.withNewPlan(current)
      }
    }

    /**
     * Resolves the subquery. Apart of resolving the subquery and outer references (if any)
     * in the subquery plan, the children of subquery expression are updated to record the
     * outer references. This is needed to make sure
     * (1) The column(s) referred from the outer query are not pruned from the plan during
     *     optimization.
     * (2) Any aggregate expression(s) that reference outer attributes are pushed down to
     *     outer plan to get evaluated.
     */
    private def resolveSubQueries(plan: LogicalPlan, plans: Seq[LogicalPlan]): LogicalPlan = {
      plan.transformAllExpressionsWithPruning(_.containsPattern(PLAN_EXPRESSION), ruleId) {
        case s @ ScalarSubquery(sub, _, exprId, _) if !sub.resolved =>
          resolveSubQuery(s, plans)(ScalarSubquery(_, _, exprId))
        case e @ Exists(sub, _, exprId, _) if !sub.resolved =>
          resolveSubQuery(e, plans)(Exists(_, _, exprId))
        case InSubquery(values, l @ ListQuery(_, _, exprId, _, _))
            if values.forall(_.resolved) && !l.resolved =>
          val expr = resolveSubQuery(l, plans)((plan, exprs) => {
            ListQuery(plan, exprs, exprId, plan.output)
          })
          InSubquery(values, expr.asInstanceOf[ListQuery])
        case s @ LateralSubquery(sub, _, exprId, _) if !sub.resolved =>
          resolveSubQuery(s, plans)(LateralSubquery(_, _, exprId))
      }
    }

    /**
     * Resolve and rewrite all subqueries in an operator tree..
     */
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsPattern(PLAN_EXPRESSION), ruleId) {
      case j: LateralJoin if j.left.resolved =>
        resolveSubQueries(j, j.children)
      // Only a few unary nodes (Project/Filter/Aggregate) can contain subqueries.
      case q: UnaryNode if q.childrenResolved =>
        resolveSubQueries(q, q.children)
      case j: Join if j.childrenResolved && j.duplicateResolved =>
        resolveSubQueries(j, j.children)
      case s: SupportsSubquery if s.childrenResolved =>
        resolveSubQueries(s, s.children)
    }
  }

  /**
   * Replaces unresolved column aliases for a subquery with projections.
   */
  object ResolveSubqueryColumnAliases extends Rule[LogicalPlan] {

     def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
       _.containsPattern(UNRESOLVED_SUBQUERY_COLUMN_ALIAS), ruleId) {
      case u @ UnresolvedSubqueryColumnAliases(columnNames, child) if child.resolved =>
        // Resolves output attributes if a query has alias names in its subquery:
        // e.g., SELECT * FROM (SELECT 1 AS a, 1 AS b) t(col1, col2)
        val outputAttrs = child.output
        // Checks if the number of the aliases equals to the number of output columns
        // in the subquery.
        if (columnNames.size != outputAttrs.size) {
          throw QueryCompilationErrors.aliasNumberNotMatchColumnNumberError(
            columnNames.size, outputAttrs.size, u)
        }
        val aliases = outputAttrs.zip(columnNames).map { case (attr, aliasName) =>
          Alias(attr, aliasName)()
        }
        Project(aliases, child)
    }
  }

  /**
   * Turns projections that contain aggregate expressions into aggregations.
   */
  object GlobalAggregates extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      t => t.containsAnyPattern(AGGREGATE_EXPRESSION, PYTHON_UDF) && t.containsPattern(PROJECT),
      ruleId) {
      case Project(projectList, child) if containsAggregates(projectList) =>
        Aggregate(Nil, projectList, child)
    }

    def containsAggregates(exprs: Seq[Expression]): Boolean = {
      // Collect all Windowed Aggregate Expressions.
      val windowedAggExprs: Set[Expression] = exprs.flatMap { expr =>
        expr.collect {
          case WindowExpression(ae: AggregateExpression, _) => ae
          case WindowExpression(e: PythonUDF, _) if PythonUDF.isGroupedAggPandasUDF(e) => e
        }
      }.toSet

      // Find the first Aggregate Expression that is not Windowed.
      exprs.exists(_.collectFirst {
        case ae: AggregateExpression if !windowedAggExprs.contains(ae) => ae
        case e: PythonUDF if PythonUDF.isGroupedAggPandasUDF(e) &&
          !windowedAggExprs.contains(e) => e
      }.isDefined)
    }
  }

  /**
   * This rule finds aggregate expressions that are not in an aggregate operator.  For example,
   * those in a HAVING clause or ORDER BY clause.  These expressions are pushed down to the
   * underlying aggregate operator and then projected away after the original operator.
   */
  object ResolveAggregateFunctions extends Rule[LogicalPlan] with AliasHelper {
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsPattern(AGGREGATE), ruleId) {
      // Resolve aggregate with having clause to Filter(..., Aggregate()). Note, to avoid wrongly
      // resolve the having condition expression, here we skip resolving it in ResolveReferences
      // and transform it to Filter after aggregate is resolved. Basically columns in HAVING should
      // be resolved with `agg.child.output` first. See more details in SPARK-31519.
      case UnresolvedHaving(cond, agg: Aggregate) if agg.resolved =>
        resolveOperatorWithAggregate(Seq(cond), agg, (newExprs, newChild) => {
          Filter(newExprs.head, newChild)
        })

      case Filter(cond, agg: Aggregate) if agg.resolved =>
        // We should resolve the references normally based on child (agg.output) first.
        val maybeResolved = resolveExpressionByPlanOutput(cond, agg)
        resolveOperatorWithAggregate(Seq(maybeResolved), agg, (newExprs, newChild) => {
          Filter(newExprs.head, newChild)
        })

      case Sort(sortOrder, global, agg: Aggregate) if agg.resolved =>
        // We should resolve the references normally based on child (agg.output) first.
        val maybeResolved = sortOrder.map(_.child).map(resolveExpressionByPlanOutput(_, agg))
        resolveOperatorWithAggregate(maybeResolved, agg, (newExprs, newChild) => {
          val newSortOrder = sortOrder.zip(newExprs).map {
            case (sortOrder, expr) => sortOrder.copy(child = expr)
          }
          Sort(newSortOrder, global, newChild)
        })
    }

    /**
     * Resolves the given expressions as if they are in the given Aggregate operator, which means
     * the column can be resolved using `agg.child` and aggregate functions/grouping columns are
     * allowed. It returns a list of named expressions that need to be appended to
     * `agg.aggregateExpressions`, and the list of resolved expressions.
     */
    def resolveExprsWithAggregate(
        exprs: Seq[Expression],
        agg: Aggregate): (Seq[NamedExpression], Seq[Expression]) = {
      def resolveCol(input: Expression): Expression = {
        input.transform {
          case u: UnresolvedAttribute =>
            try {
              // Resolve the column and wrap it with `TempResolvedColumn`. If the resolved column
              // doesn't end up with as aggregate function input or grouping column, we should
              // undo the column resolution to avoid confusing error message. For example, if
              // a table `t` has two columns `c1` and `c2`, for query `SELECT ... FROM t
              // GROUP BY c1 HAVING c2 = 0`, even though we can resolve column `c2` here, we
              // should undo it later and fail with "Column c2 not found".
              agg.child.resolve(u.nameParts, resolver).map(TempResolvedColumn(_, u.nameParts))
                .getOrElse(u)
            } catch {
              case _: AnalysisException => u
            }
        }
      }

      def resolveSubQuery(input: Expression): Expression = {
        if (SubqueryExpression.hasSubquery(input)) {
          val fake = Project(Alias(input, "fake")() :: Nil, agg.child)
          ResolveSubquery(fake).asInstanceOf[Project].projectList.head.asInstanceOf[Alias].child
        } else {
          input
        }
      }

      val extraAggExprs = ArrayBuffer.empty[NamedExpression]
      val transformed = exprs.map { e =>
        // Try resolving the expression as though it is in the aggregate clause.
        val maybeResolved = resolveSubQuery(resolveCol(e))
        if (!maybeResolved.resolved) {
          maybeResolved
        } else {
          buildAggExprList(maybeResolved, agg, extraAggExprs)
        }
      }
      (extraAggExprs.toSeq, transformed)
    }

    private def trimTempResolvedField(input: Expression): Expression = input.transform {
      case t: TempResolvedColumn => t.child
    }

    private def buildAggExprList(
        expr: Expression,
        agg: Aggregate,
        aggExprList: ArrayBuffer[NamedExpression]): Expression = {
      // Avoid adding an extra aggregate expression if it's already present in
      // `agg.aggregateExpressions`.
      val index = agg.aggregateExpressions.indexWhere {
        case Alias(child, _) => child semanticEquals expr
        case other => other semanticEquals expr
      }
      if (index >= 0) {
        agg.aggregateExpressions(index).toAttribute
      } else {
        expr match {
          case ae: AggregateExpression =>
            val cleaned = trimTempResolvedField(ae)
            val alias = Alias(cleaned, cleaned.toString)()
            aggExprList += alias
            alias.toAttribute
          case grouping: Expression if agg.groupingExpressions.exists(grouping.semanticEquals) =>
            trimTempResolvedField(grouping) match {
              case ne: NamedExpression =>
                aggExprList += ne
                ne.toAttribute
              case other =>
                val alias = Alias(other, other.toString)()
                aggExprList += alias
                alias.toAttribute
            }
          case t: TempResolvedColumn =>
            // Undo the resolution as this column is neither inside aggregate functions nor a
            // grouping column. It shouldn't be resolved with `agg.child.output`.
            CurrentOrigin.withOrigin(t.origin)(UnresolvedAttribute(t.nameParts))
          case other =>
            other.withNewChildren(other.children.map(buildAggExprList(_, agg, aggExprList)))
        }
      }
    }

    def resolveOperatorWithAggregate(
        exprs: Seq[Expression],
        agg: Aggregate,
        buildOperator: (Seq[Expression], Aggregate) => LogicalPlan): LogicalPlan = {
      val (extraAggExprs, resolvedExprs) = resolveExprsWithAggregate(exprs, agg)
      if (extraAggExprs.isEmpty) {
        buildOperator(resolvedExprs, agg)
      } else {
        Project(agg.output, buildOperator(resolvedExprs, agg.copy(
          aggregateExpressions = agg.aggregateExpressions ++ extraAggExprs)))
      }
    }
  }

  /**
   * Extracts [[Generator]] from the projectList of a [[Project]] operator and creates [[Generate]]
   * operator under [[Project]].
   *
   * This rule will throw [[AnalysisException]] for following cases:
   * 1. [[Generator]] is nested in expressions, e.g. `SELECT explode(list) + 1 FROM tbl`
   * 2. more than one [[Generator]] is found in projectList,
   *    e.g. `SELECT explode(list), explode(list) FROM tbl`
   * 3. [[Generator]] is found in other operators that are not [[Project]] or [[Generate]],
   *    e.g. `SELECT * FROM tbl SORT BY explode(list)`
   */
  object ExtractGenerator extends Rule[LogicalPlan] {
    private def hasGenerator(expr: Expression): Boolean = {
      expr.find(_.isInstanceOf[Generator]).isDefined
    }

    private def hasNestedGenerator(expr: NamedExpression): Boolean = {
      def hasInnerGenerator(g: Generator): Boolean = g match {
        // Since `GeneratorOuter` is just a wrapper of generators, we skip it here
        case go: GeneratorOuter =>
          hasInnerGenerator(go.child)
        case _ =>
          g.children.exists { _.find {
            case _: Generator => true
            case _ => false
          }.isDefined }
      }
      trimNonTopLevelAliases(expr) match {
        case UnresolvedAlias(g: Generator, _) => hasInnerGenerator(g)
        case Alias(g: Generator, _) => hasInnerGenerator(g)
        case MultiAlias(g: Generator, _) => hasInnerGenerator(g)
        case other => hasGenerator(other)
      }
    }

    private def hasAggFunctionInGenerator(ne: Seq[NamedExpression]): Boolean = {
      ne.exists(_.find {
        case g: Generator =>
          g.children.exists(_.find(_.isInstanceOf[AggregateFunction]).isDefined)
        case _ =>
          false
      }.nonEmpty)
    }

    private def trimAlias(expr: NamedExpression): Expression = expr match {
      case UnresolvedAlias(child, _) => child
      case Alias(child, _) => child
      case MultiAlias(child, _) => child
      case _ => expr
    }

    private object AliasedGenerator {
      /**
       * Extracts a [[Generator]] expression, any names assigned by aliases to the outputs
       * and the outer flag. The outer flag is used when joining the generator output.
       * @param e the [[Expression]]
       * @return (the [[Generator]], seq of output names, outer flag)
       */
      def unapply(e: Expression): Option[(Generator, Seq[String], Boolean)] = e match {
        case Alias(GeneratorOuter(g: Generator), name) if g.resolved => Some((g, name :: Nil, true))
        case MultiAlias(GeneratorOuter(g: Generator), names) if g.resolved => Some((g, names, true))
        case Alias(g: Generator, name) if g.resolved => Some((g, name :: Nil, false))
        case MultiAlias(g: Generator, names) if g.resolved => Some((g, names, false))
        case _ => None
      }
    }

    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsPattern(GENERATOR), ruleId) {
      case Project(projectList, _) if projectList.exists(hasNestedGenerator) =>
        val nestedGenerator = projectList.find(hasNestedGenerator).get
        throw QueryCompilationErrors.nestedGeneratorError(trimAlias(nestedGenerator))

      case Project(projectList, _) if projectList.count(hasGenerator) > 1 =>
        val generators = projectList.filter(hasGenerator).map(trimAlias)
        throw QueryCompilationErrors.moreThanOneGeneratorError(generators, "select")

      case Aggregate(_, aggList, _) if aggList.exists(hasNestedGenerator) =>
        val nestedGenerator = aggList.find(hasNestedGenerator).get
        throw QueryCompilationErrors.nestedGeneratorError(trimAlias(nestedGenerator))

      case Aggregate(_, aggList, _) if aggList.count(hasGenerator) > 1 =>
        val generators = aggList.filter(hasGenerator).map(trimAlias)
        throw QueryCompilationErrors.moreThanOneGeneratorError(generators, "aggregate")

      case agg @ Aggregate(groupList, aggList, child) if aggList.forall {
          case AliasedGenerator(_, _, _) => true
          case other => other.resolved
        } && aggList.exists(hasGenerator) =>
        // If generator in the aggregate list was visited, set the boolean flag true.
        var generatorVisited = false

        val projectExprs = Array.ofDim[NamedExpression](aggList.length)
        val newAggList = aggList
          .map(trimNonTopLevelAliases)
          .zipWithIndex
          .flatMap {
            case (AliasedGenerator(generator, names, outer), idx) =>
              // It's a sanity check, this should not happen as the previous case will throw
              // exception earlier.
              assert(!generatorVisited, "More than one generator found in aggregate.")
              generatorVisited = true

              val newGenChildren: Seq[Expression] = generator.children.zipWithIndex.map {
                case (e, idx) => if (e.foldable) e else Alias(e, s"_gen_input_${idx}")()
              }
              val newGenerator = {
                val g = generator.withNewChildren(newGenChildren.map { e =>
                  if (e.foldable) e else e.asInstanceOf[Alias].toAttribute
                }).asInstanceOf[Generator]
                if (outer) GeneratorOuter(g) else g
              }
              val newAliasedGenerator = if (names.length == 1) {
                Alias(newGenerator, names(0))()
              } else {
                MultiAlias(newGenerator, names)
              }
              projectExprs(idx) = newAliasedGenerator
              newGenChildren.filter(!_.foldable).asInstanceOf[Seq[NamedExpression]]
            case (other, idx) =>
              projectExprs(idx) = other.toAttribute
              other :: Nil
          }

        val newAgg = Aggregate(groupList, newAggList, child)
        Project(projectExprs.toList, newAgg)

      case p @ Project(projectList, _) if hasAggFunctionInGenerator(projectList) =>
        // If a generator has any aggregate function, we need to apply the `GlobalAggregates` rule
        // first for replacing `Project` with `Aggregate`.
        p

      case p @ Project(projectList, child) =>
        val (resolvedGenerator, newProjectList) = projectList
          .map(trimNonTopLevelAliases)
          .foldLeft((None: Option[Generate], Nil: Seq[NamedExpression])) { (res, e) =>
            e match {
              case AliasedGenerator(generator, names, outer) if generator.childrenResolved =>
                // It's a sanity check, this should not happen as the previous case will throw
                // exception earlier.
                assert(res._1.isEmpty, "More than one generator found in SELECT.")

                val g = Generate(
                  generator,
                  unrequiredChildIndex = Nil,
                  outer = outer,
                  qualifier = None,
                  generatorOutput = ResolveGenerate.makeGeneratorOutput(generator, names),
                  child)

                (Some(g), res._2 ++ g.generatorOutput)
              case other =>
                (res._1, res._2 :+ other)
            }
          }

        if (resolvedGenerator.isDefined) {
          Project(newProjectList, resolvedGenerator.get)
        } else {
          p
        }

      case g: Generate => g

      case p if p.expressions.exists(hasGenerator) =>
        throw QueryCompilationErrors.generatorOutsideSelectError(p)
    }
  }

  /**
   * Rewrites table generating expressions that either need one or more of the following in order
   * to be resolved:
   *  - concrete attribute references for their output.
   *  - to be relocated from a SELECT clause (i.e. from  a [[Project]]) into a [[Generate]]).
   *
   * Names for the output [[Attribute]]s are extracted from [[Alias]] or [[MultiAlias]] expressions
   * that wrap the [[Generator]].
   */
  object ResolveGenerate extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsPattern(GENERATE), ruleId) {
      case g: Generate if !g.child.resolved || !g.generator.resolved => g
      case g: Generate if !g.resolved =>
        g.copy(generatorOutput = makeGeneratorOutput(g.generator, g.generatorOutput.map(_.name)))
    }

    /**
     * Construct the output attributes for a [[Generator]], given a list of names.  If the list of
     * names is empty names are assigned from field names in generator.
     */
    private[analysis] def makeGeneratorOutput(
        generator: Generator,
        names: Seq[String]): Seq[Attribute] = {
      val elementAttrs = generator.elementSchema.toAttributes

      if (names.length == elementAttrs.length) {
        names.zip(elementAttrs).map {
          case (name, attr) => attr.withName(name)
        }
      } else if (names.isEmpty) {
        elementAttrs
      } else {
        throw QueryCompilationErrors.aliasesNumberNotMatchUDTFOutputError(
          elementAttrs.size, names.mkString(","))
      }
    }
  }

  /**
   * Extracts [[WindowExpression]]s from the projectList of a [[Project]] operator and
   * aggregateExpressions of an [[Aggregate]] operator and creates individual [[Window]]
   * operators for every distinct [[WindowSpecDefinition]].
   *
   * This rule handles three cases:
   *  - A [[Project]] having [[WindowExpression]]s in its projectList;
   *  - An [[Aggregate]] having [[WindowExpression]]s in its aggregateExpressions.
   *  - A [[Filter]]->[[Aggregate]] pattern representing GROUP BY with a HAVING
   *    clause and the [[Aggregate]] has [[WindowExpression]]s in its aggregateExpressions.
   * Note: If there is a GROUP BY clause in the query, aggregations and corresponding
   * filters (expressions in the HAVING clause) should be evaluated before any
   * [[WindowExpression]]. If a query has SELECT DISTINCT, the DISTINCT part should be
   * evaluated after all [[WindowExpression]]s.
   *
   * For every case, the transformation works as follows:
   * 1. For a list of [[Expression]]s (a projectList or an aggregateExpressions), partitions
   *    it two lists of [[Expression]]s, one for all [[WindowExpression]]s and another for
   *    all regular expressions.
   * 2. For all [[WindowExpression]]s, groups them based on their [[WindowSpecDefinition]]s
   *    and [[WindowFunctionType]]s.
   * 3. For every distinct [[WindowSpecDefinition]] and [[WindowFunctionType]], creates a
   *    [[Window]] operator and inserts it into the plan tree.
   */
  object ExtractWindowExpressions extends Rule[LogicalPlan] {
    type Spec = (Seq[Expression], Seq[SortOrder], WindowFunctionType)

    private def hasWindowFunction(exprs: Seq[Expression]): Boolean =
      exprs.exists(hasWindowFunction)

    private def hasWindowFunction(expr: Expression): Boolean = {
      expr.find {
        case window: WindowExpression => true
        case _ => false
      }.isDefined
    }

    /**
     * From a Seq of [[NamedExpression]]s, extract expressions containing window expressions and
     * other regular expressions that do not contain any window expression. For example, for
     * `col1, Sum(col2 + col3) OVER (PARTITION BY col4 ORDER BY col5)`, we will extract
     * `col1`, `col2 + col3`, `col4`, and `col5` out and replace their appearances in
     * the window expression as attribute references. So, the first returned value will be
     * `[Sum(_w0) OVER (PARTITION BY _w1 ORDER BY _w2)]` and the second returned value will be
     * [col1, col2 + col3 as _w0, col4 as _w1, col5 as _w2].
     *
     * @return (seq of expressions containing at least one window expression,
     *          seq of non-window expressions)
     */
    private def extract(
        expressions: Seq[NamedExpression]): (Seq[NamedExpression], Seq[NamedExpression]) = {
      // First, we partition the input expressions to two part. For the first part,
      // every expression in it contain at least one WindowExpression.
      // Expressions in the second part do not have any WindowExpression.
      val (expressionsWithWindowFunctions, regularExpressions) =
        expressions.partition(hasWindowFunction)

      // Then, we need to extract those regular expressions used in the WindowExpression.
      // For example, when we have col1 - Sum(col2 + col3) OVER (PARTITION BY col4 ORDER BY col5),
      // we need to make sure that col1 to col5 are all projected from the child of the Window
      // operator.
      val extractedExprBuffer = new ArrayBuffer[NamedExpression]()
      def extractExpr(expr: Expression): Expression = expr match {
        case ne: NamedExpression =>
          // If a named expression is not in regularExpressions, add it to
          // extractedExprBuffer and replace it with an AttributeReference.
          val missingExpr =
            AttributeSet(Seq(expr)) -- (regularExpressions ++ extractedExprBuffer)
          if (missingExpr.nonEmpty) {
            extractedExprBuffer += ne
          }
          // alias will be cleaned in the rule CleanupAliases
          ne
        case e: Expression if e.foldable =>
          e // No need to create an attribute reference if it will be evaluated as a Literal.
        case e: Expression =>
          // For other expressions, we extract it and replace it with an AttributeReference (with
          // an internal column name, e.g. "_w0").
          val withName = Alias(e, s"_w${extractedExprBuffer.length}")()
          extractedExprBuffer += withName
          withName.toAttribute
      }

      // Now, we extract regular expressions from expressionsWithWindowFunctions
      // by using extractExpr.
      val seenWindowAggregates = new ArrayBuffer[AggregateExpression]
      val newExpressionsWithWindowFunctions = expressionsWithWindowFunctions.map {
        _.transform {
          // Extracts children expressions of a WindowFunction (input parameters of
          // a WindowFunction).
          case wf: WindowFunction =>
            val newChildren = wf.children.map(extractExpr)
            wf.withNewChildren(newChildren)

          // Extracts expressions from the partition spec and order spec.
          case wsc @ WindowSpecDefinition(partitionSpec, orderSpec, _) =>
            val newPartitionSpec = partitionSpec.map(extractExpr)
            val newOrderSpec = orderSpec.map { so =>
              val newChild = extractExpr(so.child)
              so.copy(child = newChild)
            }
            wsc.copy(partitionSpec = newPartitionSpec, orderSpec = newOrderSpec)

          case WindowExpression(ae: AggregateExpression, _) if ae.filter.isDefined =>
            throw QueryCompilationErrors.windowAggregateFunctionWithFilterNotSupportedError

          // Extract Windowed AggregateExpression
          case we @ WindowExpression(
              ae @ AggregateExpression(function, _, _, _, _),
              spec: WindowSpecDefinition) =>
            val newChildren = function.children.map(extractExpr)
            val newFunction = function.withNewChildren(newChildren).asInstanceOf[AggregateFunction]
            val newAgg = ae.copy(aggregateFunction = newFunction)
            seenWindowAggregates += newAgg
            WindowExpression(newAgg, spec)

          case AggregateExpression(aggFunc, _, _, _, _) if hasWindowFunction(aggFunc.children) =>
            throw QueryCompilationErrors.windowFunctionInsideAggregateFunctionNotAllowedError

          // Extracts AggregateExpression. For example, for SUM(x) - Sum(y) OVER (...),
          // we need to extract SUM(x).
          case agg: AggregateExpression if !seenWindowAggregates.contains(agg) =>
            val withName = Alias(agg, s"_w${extractedExprBuffer.length}")()
            extractedExprBuffer += withName
            withName.toAttribute

          // Extracts other attributes
          case attr: Attribute => extractExpr(attr)

        }.asInstanceOf[NamedExpression]
      }

      (newExpressionsWithWindowFunctions, regularExpressions ++ extractedExprBuffer)
    } // end of extract

    /**
     * Adds operators for Window Expressions. Every Window operator handles a single Window Spec.
     */
    private def addWindow(
        expressionsWithWindowFunctions: Seq[NamedExpression],
        child: LogicalPlan): LogicalPlan = {
      // First, we need to extract all WindowExpressions from expressionsWithWindowFunctions
      // and put those extracted WindowExpressions to extractedWindowExprBuffer.
      // This step is needed because it is possible that an expression contains multiple
      // WindowExpressions with different Window Specs.
      // After extracting WindowExpressions, we need to construct a project list to generate
      // expressionsWithWindowFunctions based on extractedWindowExprBuffer.
      // For example, for "sum(a) over (...) / sum(b) over (...)", we will first extract
      // "sum(a) over (...)" and "sum(b) over (...)" out, and assign "_we0" as the alias to
      // "sum(a) over (...)" and "_we1" as the alias to "sum(b) over (...)".
      // Then, the projectList will be [_we0/_we1].
      val extractedWindowExprBuffer = new ArrayBuffer[NamedExpression]()
      val newExpressionsWithWindowFunctions = expressionsWithWindowFunctions.map {
        // We need to use transformDown because we want to trigger
        // "case alias @ Alias(window: WindowExpression, _)" first.
        _.transformDown {
          case alias @ Alias(window: WindowExpression, _) =>
            // If a WindowExpression has an assigned alias, just use it.
            extractedWindowExprBuffer += alias
            alias.toAttribute
          case window: WindowExpression =>
            // If there is no alias assigned to the WindowExpressions. We create an
            // internal column.
            val withName = Alias(window, s"_we${extractedWindowExprBuffer.length}")()
            extractedWindowExprBuffer += withName
            withName.toAttribute
        }.asInstanceOf[NamedExpression]
      }

      // SPARK-32616: Use a linked hash map to maintains the insertion order of the Window
      // operators, so the query with multiple Window operators can have the determined plan.
      val groupedWindowExpressions = mutable.LinkedHashMap.empty[Spec, ArrayBuffer[NamedExpression]]
      // Second, we group extractedWindowExprBuffer based on their Partition and Order Specs.
      extractedWindowExprBuffer.foreach { expr =>
        val distinctWindowSpec = expr.collect {
          case window: WindowExpression => window.windowSpec
        }.distinct

        // We do a final check and see if we only have a single Window Spec defined in an
        // expressions.
        if (distinctWindowSpec.isEmpty) {
          throw QueryCompilationErrors.expressionWithoutWindowExpressionError(expr)
        } else if (distinctWindowSpec.length > 1) {
          // newExpressionsWithWindowFunctions only have expressions with a single
          // WindowExpression. If we reach here, we have a bug.
          throw QueryCompilationErrors.expressionWithMultiWindowExpressionsError(
            expr, distinctWindowSpec)
        } else {
          val spec = distinctWindowSpec.head
          val specKey = (spec.partitionSpec, spec.orderSpec, WindowFunctionType.functionType(expr))
          val windowExprs = groupedWindowExpressions
            .getOrElseUpdate(specKey, new ArrayBuffer[NamedExpression])
          windowExprs += expr
        }
      }

      // Third, we aggregate them by adding each Window operator for each Window Spec and then
      // setting this to the child of the next Window operator.
      val windowOps =
        groupedWindowExpressions.foldLeft(child) {
          case (last, ((partitionSpec, orderSpec, _), windowExpressions)) =>
            Window(windowExpressions.toSeq, partitionSpec, orderSpec, last)
        }

      // Finally, we create a Project to output windowOps's output
      // newExpressionsWithWindowFunctions.
      Project(windowOps.output ++ newExpressionsWithWindowFunctions, windowOps)
    } // end of addWindow

    // We have to use transformDown at here to make sure the rule of
    // "Aggregate with Having clause" will be triggered.
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsDownWithPruning(
      _.containsPattern(WINDOW_EXPRESSION), ruleId) {

      case Filter(condition, _) if hasWindowFunction(condition) =>
        throw QueryCompilationErrors.windowFunctionNotAllowedError("WHERE")

      case UnresolvedHaving(condition, _) if hasWindowFunction(condition) =>
        throw QueryCompilationErrors.windowFunctionNotAllowedError("HAVING")

      // Aggregate with Having clause. This rule works with an unresolved Aggregate because
      // a resolved Aggregate will not have Window Functions.
      case f @ UnresolvedHaving(condition, a @ Aggregate(groupingExprs, aggregateExprs, child))
        if child.resolved &&
          hasWindowFunction(aggregateExprs) &&
          a.expressions.forall(_.resolved) =>
        val (windowExpressions, aggregateExpressions) = extract(aggregateExprs)
        // Create an Aggregate operator to evaluate aggregation functions.
        val withAggregate = Aggregate(groupingExprs, aggregateExpressions, child)
        // Add a Filter operator for conditions in the Having clause.
        val withFilter = Filter(condition, withAggregate)
        val withWindow = addWindow(windowExpressions, withFilter)

        // Finally, generate output columns according to the original projectList.
        val finalProjectList = aggregateExprs.map(_.toAttribute)
        Project(finalProjectList, withWindow)

      case p: LogicalPlan if !p.childrenResolved => p

      // Aggregate without Having clause.
      case a @ Aggregate(groupingExprs, aggregateExprs, child)
        if hasWindowFunction(aggregateExprs) &&
          a.expressions.forall(_.resolved) =>
        val (windowExpressions, aggregateExpressions) = extract(aggregateExprs)
        // Create an Aggregate operator to evaluate aggregation functions.
        val withAggregate = Aggregate(groupingExprs, aggregateExpressions, child)
        // Add Window operators.
        val withWindow = addWindow(windowExpressions, withAggregate)

        // Finally, generate output columns according to the original projectList.
        val finalProjectList = aggregateExprs.map(_.toAttribute)
        Project(finalProjectList, withWindow)

      // We only extract Window Expressions after all expressions of the Project
      // have been resolved.
      case p @ Project(projectList, child)
        if hasWindowFunction(projectList) && !p.expressions.exists(!_.resolved) =>
        val (windowExpressions, regularExpressions) = extract(projectList)
        // We add a project to get all needed expressions for window expressions from the child
        // of the original Project operator.
        val withProject = Project(regularExpressions, child)
        // Add Window operators.
        val withWindow = addWindow(windowExpressions, withProject)

        // Finally, generate output columns according to the original projectList.
        val finalProjectList = projectList.map(_.toAttribute)
        Project(finalProjectList, withWindow)
    }
  }

  /**
   * Set the seed for random number generation.
   */
  object ResolveRandomSeed extends Rule[LogicalPlan] {
    private lazy val random = new Random()

    override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsPattern(EXPRESSION_WITH_RANDOM_SEED), ruleId) {
      case p if p.resolved => p
      case p => p.transformExpressionsUpWithPruning(
        _.containsPattern(EXPRESSION_WITH_RANDOM_SEED), ruleId) {
        case e: ExpressionWithRandomSeed if e.seedExpression == UnresolvedSeed =>
          e.withNewSeed(random.nextLong())
      }
    }
  }

  /**
   * Correctly handle null primitive inputs for UDF by adding extra [[If]] expression to do the
   * null check.  When user defines a UDF with primitive parameters, there is no way to tell if the
   * primitive parameter is null or not, so here we assume the primitive input is null-propagatable
   * and we should return null if the input is null.
   */
  object HandleNullInputsForUDF extends Rule[LogicalPlan] {
    override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsPattern(SCALA_UDF)) {
      case p if !p.resolved => p // Skip unresolved nodes.

      case p => p.transformExpressionsUpWithPruning(_.containsPattern(SCALA_UDF)) {

        case udf: ScalaUDF if udf.inputPrimitives.contains(true) =>
          // Otherwise, add special handling of null for fields that can't accept null.
          // The result of operations like this, when passed null, is generally to return null.
          assert(udf.inputPrimitives.length == udf.children.length)

          val inputPrimitivesPair = udf.inputPrimitives.zip(udf.children)
          val inputNullCheck = inputPrimitivesPair.collect {
            case (isPrimitive, input) if isPrimitive && input.nullable =>
              IsNull(input)
          }.reduceLeftOption[Expression](Or)

          if (inputNullCheck.isDefined) {
            // Once we add an `If` check above the udf, it is safe to mark those checked inputs
            // as null-safe (i.e., wrap with `KnownNotNull`), because the null-returning
            // branch of `If` will be called if any of these checked inputs is null. Thus we can
            // prevent this rule from being applied repeatedly.
            val newInputs = inputPrimitivesPair.map {
              case (isPrimitive, input) =>
                if (isPrimitive && input.nullable) {
                  KnownNotNull(input)
                } else {
                  input
                }
            }
            val newUDF = udf.copy(children = newInputs)
            If(inputNullCheck.get, Literal.create(null, udf.dataType), newUDF)
          } else {
            udf
          }
      }
    }
  }

  /**
   * Resolve the encoders for the UDF by explicitly given the attributes. We give the
   * attributes explicitly in order to handle the case where the data type of the input
   * value is not the same with the internal schema of the encoder, which could cause
   * data loss. For example, the encoder should not cast the input value to Decimal(38, 18)
   * if the actual data type is Decimal(30, 0).
   *
   * The resolved encoders then will be used to deserialize the internal row to Scala value.
   */
  object ResolveEncodersInUDF extends Rule[LogicalPlan] {
    override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsPattern(SCALA_UDF), ruleId) {
      case p if !p.resolved => p // Skip unresolved nodes.

      case p => p.transformExpressionsUpWithPruning(_.containsPattern(SCALA_UDF), ruleId) {

        case udf: ScalaUDF if udf.inputEncoders.nonEmpty =>
          val boundEncoders = udf.inputEncoders.zipWithIndex.map { case (encOpt, i) =>
            val dataType = udf.children(i).dataType
            encOpt.map { enc =>
              val attrs = if (enc.isSerializedAsStructForTopLevel) {
                dataType.asInstanceOf[StructType].toAttributes
              } else {
                // the field name doesn't matter here, so we use
                // a simple literal to avoid any overhead
                new StructType().add("input", dataType).toAttributes
              }
              enc.resolveAndBind(attrs)
            }
          }
          udf.copy(inputEncoders = boundEncoders)
      }
    }
  }

  /**
   * Check and add proper window frames for all window functions.
   */
  object ResolveWindowFrame extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveExpressionsWithPruning(
      _.containsPattern(WINDOW_EXPRESSION), ruleId) {
      case WindowExpression(wf: FrameLessOffsetWindowFunction,
        WindowSpecDefinition(_, _, f: SpecifiedWindowFrame)) if wf.frame != f =>
        throw QueryCompilationErrors.cannotSpecifyWindowFrameError(wf.prettyName)
      case WindowExpression(wf: WindowFunction, WindowSpecDefinition(_, _, f: SpecifiedWindowFrame))
          if wf.frame != UnspecifiedFrame && wf.frame != f =>
        throw QueryCompilationErrors.windowFrameNotMatchRequiredFrameError(f, wf.frame)
      case WindowExpression(wf: WindowFunction, s @ WindowSpecDefinition(_, _, UnspecifiedFrame))
          if wf.frame != UnspecifiedFrame =>
        WindowExpression(wf, s.copy(frameSpecification = wf.frame))
      case we @ WindowExpression(e, s @ WindowSpecDefinition(_, o, UnspecifiedFrame))
          if e.resolved =>
        val frame = if (o.nonEmpty) {
          SpecifiedWindowFrame(RangeFrame, UnboundedPreceding, CurrentRow)
        } else {
          SpecifiedWindowFrame(RowFrame, UnboundedPreceding, UnboundedFollowing)
        }
        we.copy(windowSpec = s.copy(frameSpecification = frame))
    }
  }

  /**
   * Check and add order to [[AggregateWindowFunction]]s.
   */
  object ResolveWindowOrder extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveExpressionsWithPruning(
      _.containsPattern(WINDOW_EXPRESSION), ruleId) {
      case WindowExpression(wf: WindowFunction, spec) if spec.orderSpec.isEmpty =>
        throw QueryCompilationErrors.windowFunctionWithWindowFrameNotOrderedError(wf)
      case WindowExpression(rank: RankLike, spec) if spec.resolved =>
        val order = spec.orderSpec.map(_.child)
        WindowExpression(rank.withOrder(order), spec)
    }
  }

  /**
   * Removes natural or using joins by calculating output columns based on output from two sides,
   * Then apply a Project on a normal Join to eliminate natural or using join.
   */
  object ResolveNaturalAndUsingJoin extends Rule[LogicalPlan] {
    override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsPattern(NATURAL_LIKE_JOIN), ruleId) {
      case j @ Join(left, right, UsingJoin(joinType, usingCols), _, hint)
          if left.resolved && right.resolved && j.duplicateResolved =>
        commonNaturalJoinProcessing(left, right, joinType, usingCols, None, hint)
      case j @ Join(left, right, NaturalJoin(joinType), condition, hint)
          if j.resolvedExceptNatural =>
        // find common column names from both sides
        val joinNames = left.output.map(_.name).intersect(right.output.map(_.name))
        commonNaturalJoinProcessing(left, right, joinType, joinNames, condition, hint)
    }
  }

  /**
   * Resolves columns of an output table from the data in a logical plan. This rule will:
   *
   * - Reorder columns when the write is by name
   * - Insert casts when data types do not match
   * - Insert aliases when column names do not match
   * - Detect plans that are not compatible with the output table and throw AnalysisException
   */
  object ResolveOutputRelation extends Rule[LogicalPlan] {
    override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsWithPruning(
      _.containsPattern(COMMAND), ruleId) {
      case v2Write: V2WriteCommand
          if v2Write.table.resolved && v2Write.query.resolved && !v2Write.outputResolved =>
        validateStoreAssignmentPolicy()
        val projection = TableOutputResolver.resolveOutputColumns(
          v2Write.table.name, v2Write.table.output, v2Write.query, v2Write.isByName, conf)
        if (projection != v2Write.query) {
          val cleanedTable = v2Write.table match {
            case r: DataSourceV2Relation =>
              r.copy(output = r.output.map(CharVarcharUtils.cleanAttrMetadata))
            case other => other
          }
          v2Write.withNewQuery(projection).withNewTable(cleanedTable)
        } else {
          v2Write
        }
    }
  }

  object ResolveUserSpecifiedColumns extends Rule[LogicalPlan] {
    override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsWithPruning(
      AlwaysProcess.fn, ruleId) {
      case i: InsertIntoStatement if i.table.resolved && i.query.resolved &&
          i.userSpecifiedCols.nonEmpty =>
        val resolved = resolveUserSpecifiedColumns(i)
        val projection = addColumnListOnQuery(i.table.output, resolved, i.query)
        i.copy(userSpecifiedCols = Nil, query = projection)
    }

    private def resolveUserSpecifiedColumns(i: InsertIntoStatement): Seq[NamedExpression] = {
      SchemaUtils.checkColumnNameDuplication(
        i.userSpecifiedCols, "in the column list", resolver)

      i.userSpecifiedCols.map { col =>
          i.table.resolve(Seq(col), resolver)
            .getOrElse(throw QueryCompilationErrors.cannotResolveUserSpecifiedColumnsError(
              col, i.table))
      }
    }

    private def addColumnListOnQuery(
        tableOutput: Seq[Attribute],
        cols: Seq[NamedExpression],
        query: LogicalPlan): LogicalPlan = {
      if (cols.size != query.output.size) {
        throw QueryCompilationErrors.writeTableWithMismatchedColumnsError(
          cols.size, query.output.size, query)
      }
      val nameToQueryExpr = cols.zip(query.output).toMap
      // Static partition columns in the table output should not appear in the column list
      // they will be handled in another rule ResolveInsertInto
      val reordered = tableOutput.flatMap { nameToQueryExpr.get(_).orElse(None) }
      if (reordered == query.output) {
        query
      } else {
        Project(reordered, query)
      }
    }
  }

  private def validateStoreAssignmentPolicy(): Unit = {
    // SPARK-28730: LEGACY store assignment policy is disallowed in data source v2.
    if (conf.storeAssignmentPolicy == StoreAssignmentPolicy.LEGACY) {
      throw QueryCompilationErrors.legacyStoreAssignmentPolicyError()
    }
  }

  private def commonNaturalJoinProcessing(
      left: LogicalPlan,
      right: LogicalPlan,
      joinType: JoinType,
      joinNames: Seq[String],
      condition: Option[Expression],
      hint: JoinHint): LogicalPlan = {
    import org.apache.spark.sql.catalyst.util._

    val leftKeys = joinNames.map { keyName =>
      left.output.find(attr => resolver(attr.name, keyName)).getOrElse {
        throw QueryCompilationErrors.unresolvedUsingColForJoinError(keyName, left, "left")
      }
    }
    val rightKeys = joinNames.map { keyName =>
      right.output.find(attr => resolver(attr.name, keyName)).getOrElse {
        throw QueryCompilationErrors.unresolvedUsingColForJoinError(keyName, right, "right")
      }
    }
    val joinPairs = leftKeys.zip(rightKeys)

    val newCondition = (condition ++ joinPairs.map(EqualTo.tupled)).reduceOption(And)

    // columns not in joinPairs
    val lUniqueOutput = left.output.filterNot(att => leftKeys.contains(att))
    val rUniqueOutput = right.output.filterNot(att => rightKeys.contains(att))

    // the output list looks like: join keys, columns from left, columns from right
    val (projectList, hiddenList) = joinType match {
      case LeftOuter =>
        (leftKeys ++ lUniqueOutput ++ rUniqueOutput.map(_.withNullability(true)), rightKeys)
      case LeftExistence(_) =>
        (leftKeys ++ lUniqueOutput, Seq.empty)
      case RightOuter =>
        (rightKeys ++ lUniqueOutput.map(_.withNullability(true)) ++ rUniqueOutput, leftKeys)
      case FullOuter =>
        // in full outer join, joinCols should be non-null if there is.
        val joinedCols = joinPairs.map { case (l, r) => Alias(Coalesce(Seq(l, r)), l.name)() }
        (joinedCols ++
          lUniqueOutput.map(_.withNullability(true)) ++
          rUniqueOutput.map(_.withNullability(true)),
          leftKeys ++ rightKeys)
      case _ : InnerLike =>
        (leftKeys ++ lUniqueOutput ++ rUniqueOutput, rightKeys)
      case _ =>
        throw QueryExecutionErrors.unsupportedNaturalJoinTypeError(joinType)
    }
    // use Project to hide duplicated common keys
    // propagate hidden columns from nested USING/NATURAL JOINs
    val project = Project(projectList, Join(left, right, joinType, newCondition, hint))
    project.setTagValue(
      Project.hiddenOutputTag,
      hiddenList.map(_.markAsSupportsQualifiedStar()) ++
        project.child.metadataOutput.filter(_.supportsQualifiedStar))
    project
  }

  /**
   * Replaces [[UnresolvedDeserializer]] with the deserialization expression that has been resolved
   * to the given input attributes.
   */
  object ResolveDeserializer extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsPattern(UNRESOLVED_DESERIALIZER), ruleId) {
      case p if !p.childrenResolved => p
      case p if p.resolved => p

      case p => p.transformExpressionsWithPruning(
        _.containsPattern(UNRESOLVED_DESERIALIZER), ruleId) {
        case UnresolvedDeserializer(deserializer, inputAttributes) =>
          val inputs = if (inputAttributes.isEmpty) {
            p.children.flatMap(_.output)
          } else {
            inputAttributes
          }

          validateTopLevelTupleFields(deserializer, inputs)
          val resolved = resolveExpressionByPlanOutput(
            deserializer, LocalRelation(inputs), throws = true)
          val result = resolved transformDown {
            case UnresolvedMapObjects(func, inputData, cls) if inputData.resolved =>
              inputData.dataType match {
                case ArrayType(et, cn) =>
                  MapObjects(func, inputData, et, cn, cls) transformUp {
                    case UnresolvedExtractValue(child, fieldName) if child.resolved =>
                      ExtractValue(child, fieldName, resolver)
                  }
                case other =>
                  throw QueryCompilationErrors.dataTypeMismatchForDeserializerError(other,
                    "array")
              }
            case u: UnresolvedCatalystToExternalMap if u.child.resolved =>
              u.child.dataType match {
                case _: MapType =>
                  CatalystToExternalMap(u) transformUp {
                    case UnresolvedExtractValue(child, fieldName) if child.resolved =>
                      ExtractValue(child, fieldName, resolver)
                  }
                case other =>
                  throw QueryCompilationErrors.dataTypeMismatchForDeserializerError(other, "map")
              }
          }
          validateNestedTupleFields(result)
          result
      }
    }

    private def fail(schema: StructType, maxOrdinal: Int): Unit = {
      throw QueryCompilationErrors.fieldNumberMismatchForDeserializerError(schema, maxOrdinal)
    }

    /**
     * For each top-level Tuple field, we use [[GetColumnByOrdinal]] to get its corresponding column
     * by position.  However, the actual number of columns may be different from the number of Tuple
     * fields.  This method is used to check the number of columns and fields, and throw an
     * exception if they do not match.
     */
    private def validateTopLevelTupleFields(
        deserializer: Expression, inputs: Seq[Attribute]): Unit = {
      val ordinals = deserializer.collect {
        case GetColumnByOrdinal(ordinal, _) => ordinal
      }.distinct.sorted

      if (ordinals.nonEmpty && ordinals != inputs.indices) {
        fail(inputs.toStructType, ordinals.last)
      }
    }

    /**
     * For each nested Tuple field, we use [[GetStructField]] to get its corresponding struct field
     * by position.  However, the actual number of struct fields may be different from the number
     * of nested Tuple fields.  This method is used to check the number of struct fields and nested
     * Tuple fields, and throw an exception if they do not match.
     */
    private def validateNestedTupleFields(deserializer: Expression): Unit = {
      val structChildToOrdinals = deserializer
        // There are 2 kinds of `GetStructField`:
        //   1. resolved from `UnresolvedExtractValue`, and it will have a `name` property.
        //   2. created when we build deserializer expression for nested tuple, no `name` property.
        // Here we want to validate the ordinals of nested tuple, so we should only catch
        // `GetStructField` without the name property.
        .collect { case g: GetStructField if g.name.isEmpty => g }
        .groupBy(_.child)
        .mapValues(_.map(_.ordinal).distinct.sorted)

      structChildToOrdinals.foreach { case (expr, ordinals) =>
        val schema = expr.dataType.asInstanceOf[StructType]
        if (ordinals != schema.indices) {
          fail(schema, ordinals.last)
        }
      }
    }
  }

  /**
   * Resolves [[NewInstance]] by finding and adding the outer scope to it if the object being
   * constructed is an inner class.
   */
  object ResolveNewInstance extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsPattern(NEW_INSTANCE), ruleId) {
      case p if !p.childrenResolved => p
      case p if p.resolved => p

      case p => p.transformExpressionsUpWithPruning(_.containsPattern(NEW_INSTANCE), ruleId) {
        case n: NewInstance if n.childrenResolved && !n.resolved =>
          val outer = OuterScopes.getOuterScope(n.cls)
          if (outer == null) {
            throw QueryCompilationErrors.outerScopeFailureForNewInstanceError(n.cls.getName)
          }
          n.copy(outerPointer = Some(outer))
      }
    }
  }

  /**
   * Replace the [[UpCast]] expression by [[Cast]], and throw exceptions if the cast may truncate.
   */
  object ResolveUpCast extends Rule[LogicalPlan] {
    private def fail(from: Expression, to: DataType, walkedTypePath: Seq[String]) = {
      val fromStr = from match {
        case l: LambdaVariable => "array element"
        case e => e.sql
      }
      throw QueryCompilationErrors.upCastFailureError(fromStr, from, to, walkedTypePath)
    }

    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
      _.containsPattern(UP_CAST), ruleId) {
      case p if !p.childrenResolved => p
      case p if p.resolved => p

      case p => p.transformExpressionsWithPruning(_.containsPattern(UP_CAST), ruleId) {
        case u @ UpCast(child, _, _) if !child.resolved => u

        case UpCast(_, target, _) if target != DecimalType && !target.isInstanceOf[DataType] =>
          throw QueryCompilationErrors.unsupportedAbstractDataTypeForUpCastError(target)

        case UpCast(child, target, walkedTypePath) if target == DecimalType
          && child.dataType.isInstanceOf[DecimalType] =>
          assert(walkedTypePath.nonEmpty,
            "object DecimalType should only be used inside ExpressionEncoder")

          // SPARK-31750: if we want to upcast to the general decimal type, and the `child` is
          // already decimal type, we can remove the `Upcast` and accept any precision/scale.
          // This can happen for cases like `spark.read.parquet("/tmp/file").as[BigDecimal]`.
          child

        case UpCast(child, target: AtomicType, _)
            if conf.getConf(SQLConf.LEGACY_LOOSE_UPCAST) &&
              child.dataType == StringType =>
          Cast(child, target.asNullable)

        case u @ UpCast(child, _, walkedTypePath) if !Cast.canUpCast(child.dataType, u.dataType) =>
          fail(child, u.dataType, walkedTypePath)

        case u @ UpCast(child, _, _) => Cast(child, u.dataType.asNullable)
      }
    }
  }

  /** Rule to mostly resolve, normalize and rewrite column names based on case sensitivity. */
  object ResolveAlterTableChanges extends Rule[LogicalPlan] {
    def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUp {
      case a @ AlterTable(_, _, t: NamedRelation, changes) if t.resolved =>
        // 'colsToAdd' keeps track of new columns being added. It stores a mapping from a
        // normalized parent name of fields to field names that belong to the parent.
        // For example, if we add columns "a.b.c", "a.b.d", and "a.c", 'colsToAdd' will become
        // Map(Seq("a", "b") -> Seq("c", "d"), Seq("a") -> Seq("c")).
        val colsToAdd = mutable.Map.empty[Seq[String], Seq[String]]
        val schema = t.schema
        val normalizedChanges = changes.flatMap {
          case add: AddColumn =>
            def addColumn(
                parentSchema: StructType,
                parentName: String,
                normalizedParentName: Seq[String]): TableChange = {
              val fieldsAdded = colsToAdd.getOrElse(normalizedParentName, Nil)
              val pos = findColumnPosition(add.position(), parentName, parentSchema, fieldsAdded)
              val field = add.fieldNames().last
              colsToAdd(normalizedParentName) = fieldsAdded :+ field
              TableChange.addColumn(
                (normalizedParentName :+ field).toArray,
                add.dataType(),
                add.isNullable,
                add.comment,
                pos)
            }
            val parent = add.fieldNames().init
            if (parent.nonEmpty) {
              // Adding a nested field, need to normalize the parent column and position
              val target = schema.findNestedField(parent, includeCollections = true, conf.resolver)
              if (target.isEmpty) {
                // Leave unresolved. Throws error in CheckAnalysis
                Some(add)
              } else {
                val (normalizedName, sf) = target.get
                sf.dataType match {
                  case struct: StructType =>
                    Some(addColumn(struct, parent.quoted, normalizedName :+ sf.name))
                  case other =>
                    Some(add)
                }
              }
            } else {
              // Adding to the root. Just need to normalize position
              Some(addColumn(schema, "root", Nil))
            }

          case typeChange: UpdateColumnType =>
            // Hive style syntax provides the column type, even if it may not have changed
            val fieldOpt = schema.findNestedField(
              typeChange.fieldNames(), includeCollections = true, conf.resolver)

            if (fieldOpt.isEmpty) {
              // We couldn't resolve the field. Leave it to CheckAnalysis
              Some(typeChange)
            } else {
              val (fieldNames, field) = fieldOpt.get
              val dt = CharVarcharUtils.getRawType(field.metadata).getOrElse(field.dataType)
              if (dt == typeChange.newDataType()) {
                // The user didn't want the field to change, so remove this change
                None
              } else {
                Some(TableChange.updateColumnType(
                  (fieldNames :+ field.name).toArray, typeChange.newDataType()))
              }
            }
          case n: UpdateColumnNullability =>
            // Need to resolve column
            resolveFieldNames(
              schema,
              n.fieldNames(),
              TableChange.updateColumnNullability(_, n.nullable())).orElse(Some(n))

          case position: UpdateColumnPosition =>
            position.position() match {
              case after: After =>
                // Need to resolve column as well as position reference
                val fieldOpt = schema.findNestedField(
                  position.fieldNames(), includeCollections = true, conf.resolver)

                if (fieldOpt.isEmpty) {
                  Some(position)
                } else {
                  val (normalizedPath, field) = fieldOpt.get
                  val targetCol = schema.findNestedField(
                    normalizedPath :+ after.column(), includeCollections = true, conf.resolver)
                  if (targetCol.isEmpty) {
                    // Leave unchanged to CheckAnalysis
                    Some(position)
                  } else {
                    Some(TableChange.updateColumnPosition(
                      (normalizedPath :+ field.name).toArray,
                      ColumnPosition.after(targetCol.get._2.name)))
                  }
                }
              case _ =>
                // Need to resolve column
                resolveFieldNames(
                  schema,
                  position.fieldNames(),
                  TableChange.updateColumnPosition(_, position.position())).orElse(Some(position))
            }

          case comment: UpdateColumnComment =>
            resolveFieldNames(
              schema,
              comment.fieldNames(),
              TableChange.updateColumnComment(_, comment.newComment())).orElse(Some(comment))

          case rename: RenameColumn =>
            resolveFieldNames(
              schema,
              rename.fieldNames(),
              TableChange.renameColumn(_, rename.newName())).orElse(Some(rename))

          case delete: DeleteColumn =>
            resolveFieldNames(schema, delete.fieldNames(), TableChange.deleteColumn)
              .orElse(Some(delete))

          case column: ColumnChange =>
            // This is informational for future developers
            throw QueryExecutionErrors.columnChangeUnsupportedError
          case other => Some(other)
        }

        a.copy(changes = normalizedChanges)
    }

    /**
     * Returns the table change if the field can be resolved, returns None if the column is not
     * found. An error will be thrown in CheckAnalysis for columns that can't be resolved.
     */
    private def resolveFieldNames(
        schema: StructType,
        fieldNames: Array[String],
        copy: Array[String] => TableChange): Option[TableChange] = {
      val fieldOpt = schema.findNestedField(
        fieldNames, includeCollections = true, conf.resolver)
      fieldOpt.map { case (path, field) => copy((path :+ field.name).toArray) }
    }

    private def findColumnPosition(
        position: ColumnPosition,
        parentName: String,
        struct: StructType,
        fieldsAdded: Seq[String]): ColumnPosition = {
      position match {
        case null => null
        case after: After =>
          (struct.fieldNames ++ fieldsAdded).find(n => conf.resolver(n, after.column())) match {
            case Some(colName) =>
              ColumnPosition.after(colName)
            case None =>
              throw QueryCompilationErrors.referenceColNotFoundForAlterTableChangesError(after,
                parentName)
          }
        case other => other
      }
    }
  }

  /**
   * A rule that marks a command as analyzed so that its children are removed to avoid
   * being optimized. This rule should run after all other analysis rules are run.
   */
  object HandleAnalysisOnlyCommand extends Rule[LogicalPlan] {
    override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsWithPruning(
      _.containsPattern(COMMAND)) {
      case c: AnalysisOnlyCommand if c.resolved =>
        checkAnalysis(c)
        c.markAsAnalyzed()
    }
  }
}

/**
 * Removes [[SubqueryAlias]] operators from the plan. Subqueries are only required to provide
 * scoping information for attributes and can be removed once analysis is complete.
 */
object EliminateSubqueryAliases extends Rule[LogicalPlan] {
  // This is also called in the beginning of the optimization phase, and as a result
  // is using transformUp rather than resolveOperators.
  def apply(plan: LogicalPlan): LogicalPlan = AnalysisHelper.allowInvokingTransformsInAnalyzer {
    plan.transformUpWithPruning(AlwaysProcess.fn, ruleId) {
      case SubqueryAlias(_, child) => child
    }
  }
}

/**
 * Removes [[Union]] operators from the plan if it just has one child.
 */
object EliminateUnions extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsWithPruning(
    _.containsPattern(UNION), ruleId) {
    case u: Union if u.children.size == 1 => u.children.head
  }
}

/**
 * Cleans up unnecessary Aliases inside the plan. Basically we only need Alias as a top level
 * expression in Project(project list) or Aggregate(aggregate expressions) or
 * Window(window expressions). Notice that if an expression has other expression parameters which
 * are not in its `children`, e.g. `RuntimeReplaceable`, the transformation for Aliases in this
 * rule can't work for those parameters.
 */
object CleanupAliases extends Rule[LogicalPlan] with AliasHelper {
  override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
    // trimNonTopLevelAliases can transform Alias and MultiAlias.
    _.containsAnyPattern(ALIAS, MULTI_ALIAS)) {
    case Project(projectList, child) =>
      val cleanedProjectList = projectList.map(trimNonTopLevelAliases)
      Project(cleanedProjectList, child)

    case Aggregate(grouping, aggs, child) =>
      val cleanedAggs = aggs.map(trimNonTopLevelAliases)
      Aggregate(grouping.map(trimAliases), cleanedAggs, child)

    case Window(windowExprs, partitionSpec, orderSpec, child) =>
      val cleanedWindowExprs = windowExprs.map(trimNonTopLevelAliases)
      Window(cleanedWindowExprs, partitionSpec.map(trimAliases),
        orderSpec.map(trimAliases(_).asInstanceOf[SortOrder]), child)

    case CollectMetrics(name, metrics, child) =>
      val cleanedMetrics = metrics.map(trimNonTopLevelAliases)
      CollectMetrics(name, cleanedMetrics, child)

    // Operators that operate on objects should only have expressions from encoders, which should
    // never have extra aliases.
    case o: ObjectConsumer => o
    case o: ObjectProducer => o
    case a: AppendColumns => a

    case other =>
      other.transformExpressionsDownWithPruning(_.containsAnyPattern(ALIAS, MULTI_ALIAS)) {
        case Alias(child, _) => child
      }
  }
}

/**
 * Ignore event time watermark in batch query, which is only supported in Structured Streaming.
 * TODO: add this rule into analyzer rule list.
 */
object EliminateEventTimeWatermark extends Rule[LogicalPlan] {
  override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsWithPruning(
    _.containsPattern(EVENT_TIME_WATERMARK)) {
    case EventTimeWatermark(_, _, child) if !child.isStreaming => child
  }
}

/**
 * Maps a time column to multiple time windows using the Expand operator. Since it's non-trivial to
 * figure out how many windows a time column can map to, we over-estimate the number of windows and
 * filter out the rows where the time column is not inside the time window.
 */
object TimeWindowing extends Rule[LogicalPlan] {
  import org.apache.spark.sql.catalyst.dsl.expressions._

  private final val WINDOW_COL_NAME = "window"
  private final val WINDOW_START = "start"
  private final val WINDOW_END = "end"

  /**
   * Generates the logical plan for generating window ranges on a timestamp column. Without
   * knowing what the timestamp value is, it's non-trivial to figure out deterministically how many
   * window ranges a timestamp will map to given all possible combinations of a window duration,
   * slide duration and start time (offset). Therefore, we express and over-estimate the number of
   * windows there may be, and filter the valid windows. We use last Project operator to group
   * the window columns into a struct so they can be accessed as `window.start` and `window.end`.
   *
   * The windows are calculated as below:
   * maxNumOverlapping <- ceil(windowDuration / slideDuration)
   * for (i <- 0 until maxNumOverlapping)
   *   windowId <- ceil((timestamp - startTime) / slideDuration)
   *   windowStart <- windowId * slideDuration + (i - maxNumOverlapping) * slideDuration + startTime
   *   windowEnd <- windowStart + windowDuration
   *   return windowStart, windowEnd
   *
   * This behaves as follows for the given parameters for the time: 12:05. The valid windows are
   * marked with a +, and invalid ones are marked with a x. The invalid ones are filtered using the
   * Filter operator.
   * window: 12m, slide: 5m, start: 0m :: window: 12m, slide: 5m, start: 2m
   *     11:55 - 12:07 +                      11:52 - 12:04 x
   *     12:00 - 12:12 +                      11:57 - 12:09 +
   *     12:05 - 12:17 +                      12:02 - 12:14 +
   *
   * @param plan The logical plan
   * @return the logical plan that will generate the time windows using the Expand operator, with
   *         the Filter operator for correctness and Project for usability.
   */
  def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUpWithPruning(
    _.containsPattern(TIME_WINDOW), ruleId) {
    case p: LogicalPlan if p.children.size == 1 =>
      val child = p.children.head
      val windowExpressions =
        p.expressions.flatMap(_.collect { case t: TimeWindow => t }).toSet

      val numWindowExpr = windowExpressions.size
      // Only support a single window expression for now
      if (numWindowExpr == 1 &&
          windowExpressions.head.timeColumn.resolved &&
          windowExpressions.head.checkInputDataTypes().isSuccess) {

        val window = windowExpressions.head

        val metadata = window.timeColumn match {
          case a: Attribute => a.metadata
          case _ => Metadata.empty
        }

        def getWindow(i: Int, overlappingWindows: Int): Expression = {
          val division = (PreciseTimestampConversion(
            window.timeColumn, TimestampType, LongType) - window.startTime) / window.slideDuration
          val ceil = Ceil(division)
          // if the division is equal to the ceiling, our record is the start of a window
          val windowId = CaseWhen(Seq((ceil === division, ceil + 1)), Some(ceil))
          val windowStart = (windowId + i - overlappingWindows) *
            window.slideDuration + window.startTime
          val windowEnd = windowStart + window.windowDuration

          CreateNamedStruct(
            Literal(WINDOW_START) ::
              PreciseTimestampConversion(windowStart, LongType, TimestampType) ::
              Literal(WINDOW_END) ::
              PreciseTimestampConversion(windowEnd, LongType, TimestampType) ::
              Nil)
        }

        val windowAttr = AttributeReference(
          WINDOW_COL_NAME, window.dataType, metadata = metadata)()

        if (window.windowDuration == window.slideDuration) {
          val windowStruct = Alias(getWindow(0, 1), WINDOW_COL_NAME)(
            exprId = windowAttr.exprId, explicitMetadata = Some(metadata))

          val replacedPlan = p transformExpressions {
            case t: TimeWindow => windowAttr
          }

          // For backwards compatibility we add a filter to filter out nulls
          val filterExpr = IsNotNull(window.timeColumn)

          replacedPlan.withNewChildren(
            Filter(filterExpr,
              Project(windowStruct +: child.output, child)) :: Nil)
        } else {
          val overlappingWindows =
            math.ceil(window.windowDuration * 1.0 / window.slideDuration).toInt
          val windows =
            Seq.tabulate(overlappingWindows)(i => getWindow(i, overlappingWindows))

          val projections = windows.map(_ +: child.output)

          val filterExpr =
            window.timeColumn >= windowAttr.getField(WINDOW_START) &&
              window.timeColumn < windowAttr.getField(WINDOW_END)

          val substitutedPlan = Filter(filterExpr,
            Expand(projections, windowAttr +: child.output, child))

          val renamedPlan = p transformExpressions {
            case t: TimeWindow => windowAttr
          }

          renamedPlan.withNewChildren(substitutedPlan :: Nil)
        }
      } else if (numWindowExpr > 1) {
        throw QueryCompilationErrors.multiTimeWindowExpressionsNotSupportedError(p)
      } else {
        p // Return unchanged. Analyzer will throw exception later
      }
  }
}

/**
 * Resolve a [[CreateNamedStruct]] if it contains [[NamePlaceholder]]s.
 */
object ResolveCreateNamedStruct extends Rule[LogicalPlan] {
  override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveExpressionsWithPruning(
    _.containsPattern(CREATE_NAMED_STRUCT), ruleId) {
    case e: CreateNamedStruct if !e.resolved =>
      val children = e.children.grouped(2).flatMap {
        case Seq(NamePlaceholder, e: NamedExpression) if e.resolved =>
          Seq(Literal(e.name), e)
        case kv =>
          kv
      }
      CreateNamedStruct(children.toList)
  }
}

/**
 * The aggregate expressions from subquery referencing outer query block are pushed
 * down to the outer query block for evaluation. This rule below updates such outer references
 * as AttributeReference referring attributes from the parent/outer query block.
 *
 * For example (SQL):
 * {{{
 *   SELECT l.a FROM l GROUP BY 1 HAVING EXISTS (SELECT 1 FROM r WHERE r.d < min(l.b))
 * }}}
 * Plan before the rule.
 *    Project [a#226]
 *    +- Filter exists#245 [min(b#227)#249]
 *       :  +- Project [1 AS 1#247]
 *       :     +- Filter (d#238 < min(outer(b#227)))       <-----
 *       :        +- SubqueryAlias r
 *       :           +- Project [_1#234 AS c#237, _2#235 AS d#238]
 *       :              +- LocalRelation [_1#234, _2#235]
 *       +- Aggregate [a#226], [a#226, min(b#227) AS min(b#227)#249]
 *          +- SubqueryAlias l
 *             +- Project [_1#223 AS a#226, _2#224 AS b#227]
 *                +- LocalRelation [_1#223, _2#224]
 * Plan after the rule.
 *    Project [a#226]
 *    +- Filter exists#245 [min(b#227)#249]
 *       :  +- Project [1 AS 1#247]
 *       :     +- Filter (d#238 < outer(min(b#227)#249))   <-----
 *       :        +- SubqueryAlias r
 *       :           +- Project [_1#234 AS c#237, _2#235 AS d#238]
 *       :              +- LocalRelation [_1#234, _2#235]
 *       +- Aggregate [a#226], [a#226, min(b#227) AS min(b#227)#249]
 *          +- SubqueryAlias l
 *             +- Project [_1#223 AS a#226, _2#224 AS b#227]
 *                +- LocalRelation [_1#223, _2#224]
 */
object UpdateOuterReferences extends Rule[LogicalPlan] {
  private def stripAlias(expr: Expression): Expression = expr match { case a: Alias => a.child }

  private def updateOuterReferenceInSubquery(
      plan: LogicalPlan,
      refExprs: Seq[Expression]): LogicalPlan = {
    plan resolveExpressions { case e =>
      val outerAlias =
        refExprs.find(stripAlias(_).semanticEquals(stripOuterReference(e)))
      outerAlias match {
        case Some(a: Alias) => OuterReference(a.toAttribute)
        case _ => e
      }
    }
  }

  def apply(plan: LogicalPlan): LogicalPlan = {
    plan.resolveOperatorsWithPruning(
      _.containsAllPatterns(PLAN_EXPRESSION, FILTER, AGGREGATE), ruleId) {
      case f @ Filter(_, a: Aggregate) if f.resolved =>
        f.transformExpressionsWithPruning(_.containsPattern(PLAN_EXPRESSION), ruleId) {
          case s: SubqueryExpression if s.children.nonEmpty =>
            // Collect the aliases from output of aggregate.
            val outerAliases = a.aggregateExpressions collect { case a: Alias => a }
            // Update the subquery plan to record the OuterReference to point to outer query plan.
            s.withNewPlan(updateOuterReferenceInSubquery(s.plan, outerAliases))
      }
    }
  }
}

/**
 * This rule performs string padding for char type comparison.
 *
 * When comparing char type column/field with string literal or char type column/field,
 * right-pad the shorter one to the longer length.
 */
object ApplyCharTypePadding extends Rule[LogicalPlan] {

  object AttrOrOuterRef {
    def unapply(e: Expression): Option[Attribute] = e match {
      case a: Attribute => Some(a)
      case OuterReference(a: Attribute) => Some(a)
      case _ => None
    }
  }

  override def apply(plan: LogicalPlan): LogicalPlan = {
    plan.resolveOperatorsUpWithPruning(_.containsAnyPattern(BINARY_COMPARISON, IN)) {
      case operator => operator.transformExpressionsUpWithPruning(
        _.containsAnyPattern(BINARY_COMPARISON, IN)) {
        case e if !e.childrenResolved => e

        // String literal is treated as char type when it's compared to a char type column.
        // We should pad the shorter one to the longer length.
        case b @ BinaryComparison(e @ AttrOrOuterRef(attr), lit) if lit.foldable =>
          padAttrLitCmp(e, attr.metadata, lit).map { newChildren =>
            b.withNewChildren(newChildren)
          }.getOrElse(b)

        case b @ BinaryComparison(lit, e @ AttrOrOuterRef(attr)) if lit.foldable =>
          padAttrLitCmp(e, attr.metadata, lit).map { newChildren =>
            b.withNewChildren(newChildren.reverse)
          }.getOrElse(b)

        case i @ In(e @ AttrOrOuterRef(attr), list)
          if attr.dataType == StringType && list.forall(_.foldable) =>
          CharVarcharUtils.getRawType(attr.metadata).flatMap {
            case CharType(length) =>
              val (nulls, literalChars) =
                list.map(_.eval().asInstanceOf[UTF8String]).partition(_ == null)
              val literalCharLengths = literalChars.map(_.numChars())
              val targetLen = (length +: literalCharLengths).max
              Some(i.copy(
                value = addPadding(e, length, targetLen),
                list = list.zip(literalCharLengths).map {
                  case (lit, charLength) => addPadding(lit, charLength, targetLen)
                } ++ nulls.map(Literal.create(_, StringType))))
            case _ => None
          }.getOrElse(i)

        // For char type column or inner field comparison, pad the shorter one to the longer length.
        case b @ BinaryComparison(e1 @ AttrOrOuterRef(left), e2 @ AttrOrOuterRef(right))
            // For the same attribute, they must be the same length and no padding is needed.
            if !left.semanticEquals(right) =>
          val outerRefs = (e1, e2) match {
            case (_: OuterReference, _: OuterReference) => Seq(left, right)
            case (_: OuterReference, _) => Seq(left)
            case (_, _: OuterReference) => Seq(right)
            case _ => Nil
          }
          val newChildren = CharVarcharUtils.addPaddingInStringComparison(Seq(left, right))
          if (outerRefs.nonEmpty) {
            b.withNewChildren(newChildren.map(_.transform {
              case a: Attribute if outerRefs.exists(_.semanticEquals(a)) => OuterReference(a)
            }))
          } else {
            b.withNewChildren(newChildren)
          }

        case i @ In(e @ AttrOrOuterRef(attr), list) if list.forall(_.isInstanceOf[Attribute]) =>
          val newChildren = CharVarcharUtils.addPaddingInStringComparison(
            attr +: list.map(_.asInstanceOf[Attribute]))
          if (e.isInstanceOf[OuterReference]) {
            i.copy(
              value = newChildren.head.transform {
                case a: Attribute if a.semanticEquals(attr) => OuterReference(a)
              },
              list = newChildren.tail)
          } else {
            i.copy(value = newChildren.head, list = newChildren.tail)
          }
      }
    }
  }

  private def padAttrLitCmp(
      expr: Expression,
      metadata: Metadata,
      lit: Expression): Option[Seq[Expression]] = {
    if (expr.dataType == StringType) {
      CharVarcharUtils.getRawType(metadata).flatMap {
        case CharType(length) =>
          val str = lit.eval().asInstanceOf[UTF8String]
          if (str == null) {
            None
          } else {
            val stringLitLen = str.numChars()
            if (length < stringLitLen) {
              Some(Seq(StringRPad(expr, Literal(stringLitLen)), lit))
            } else if (length > stringLitLen) {
              Some(Seq(expr, StringRPad(lit, Literal(length))))
            } else {
              None
            }
          }
        case _ => None
      }
    } else {
      None
    }
  }

  private def padOuterRefAttrCmp(outerAttr: Attribute, attr: Attribute): Seq[Expression] = {
    val Seq(r, newAttr) = CharVarcharUtils.addPaddingInStringComparison(Seq(outerAttr, attr))
    val newOuterRef = r.transform {
      case ar: Attribute if ar.semanticEquals(outerAttr) => OuterReference(ar)
    }
    Seq(newOuterRef, newAttr)
  }

  private def addPadding(expr: Expression, charLength: Int, targetLength: Int): Expression = {
    if (targetLength > charLength) StringRPad(expr, Literal(targetLength)) else expr
  }
}

/**
 * Removes all [[TempResolvedColumn]]s in the query plan. This is the last resort, in case some
 * rules in the main resolution batch miss to remove [[TempResolvedColumn]]s. We should run this
 * rule right after the main resolution batch.
 */
object RemoveTempResolvedColumn extends Rule[LogicalPlan] {
  override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveExpressions {
    case t: TempResolvedColumn => UnresolvedAttribute(t.nameParts)
  }
}
