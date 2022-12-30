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

import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.catalyst.{FunctionIdentifier, TableIdentifier}
import org.apache.spark.sql.catalyst.catalog.{CatalogStorageFormat, CatalogTable, CatalogTableType, CatalogUtils}
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.util.{quoteIfNeeded, toPrettySQL, ResolveDefaultColumns => DefaultCols}
import org.apache.spark.sql.catalyst.util.ResolveDefaultColumns._
import org.apache.spark.sql.connector.catalog.{CatalogManager, CatalogPlugin, CatalogV2Util, Identifier, LookupCatalog, SupportsNamespaces, V1Table}
import org.apache.spark.sql.connector.expressions.Transform
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.execution.command._
import org.apache.spark.sql.execution.datasources.{CreateTable => CreateTableV1, DataSource}
import org.apache.spark.sql.execution.datasources.v2.FileDataSourceV2
import org.apache.spark.sql.internal.{HiveSerDe, SQLConf}
import org.apache.spark.sql.internal.connector.V1Function
import org.apache.spark.sql.types.{MetadataBuilder, StructField, StructType}

/**
 * Resolves catalogs from the multi-part identifiers in SQL statements, and convert the statements
 * to the corresponding v1 or v2 commands if the resolved catalog is the session catalog.
 *
 * We can remove this rule once we implement all the catalog functionality in `V2SessionCatalog`.
 */
class ResolveSessionCatalog(val catalogManager: CatalogManager)
  extends Rule[LogicalPlan] with LookupCatalog {
  import org.apache.spark.sql.connector.catalog.CatalogV2Implicits._
  import org.apache.spark.sql.connector.catalog.CatalogV2Util._
  import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Implicits._

  override def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperatorsUp {
    case AddColumns(ResolvedV1TableIdentifier(ident), cols) =>
      cols.foreach { c =>
        assertTopLevelColumn(c.name, "AlterTableAddColumnsCommand")
        if (!c.nullable) {
          throw QueryCompilationErrors.addColumnWithV1TableCannotSpecifyNotNullError
        }
      }
      AlterTableAddColumnsCommand(ident.asTableIdentifier, cols.map(convertToStructField))

    case ReplaceColumns(ResolvedV1TableIdentifier(_), _) =>
      throw QueryCompilationErrors.operationOnlySupportedWithV2TableError("REPLACE COLUMNS")

    case a @ AlterColumn(ResolvedV1TableAndIdentifier(table, ident), _, _, _, _, _, _) =>
      if (a.column.name.length > 1) {
        throw QueryCompilationErrors
          .operationOnlySupportedWithV2TableError("ALTER COLUMN with qualified column")
      }
      if (a.nullable.isDefined) {
        throw QueryCompilationErrors.alterColumnWithV1TableCannotSpecifyNotNullError
      }
      if (a.position.isDefined) {
        throw QueryCompilationErrors
          .operationOnlySupportedWithV2TableError("ALTER COLUMN ... FIRST | ALTER")
      }
      val builder = new MetadataBuilder
      // Add comment to metadata
      a.comment.map(c => builder.putString("comment", c))
      val colName = a.column.name(0)
      val dataType = a.dataType.getOrElse {
        table.schema.findNestedField(Seq(colName), resolver = conf.resolver)
          .map(_._2.dataType)
          .getOrElse {
            throw QueryCompilationErrors.alterColumnCannotFindColumnInV1TableError(
              quoteIfNeeded(colName), table)
          }
      }
      // Add the current default column value string (if any) to the column metadata.
      a.setDefaultExpression.map { c => builder.putString(CURRENT_DEFAULT_COLUMN_METADATA_KEY, c) }
      val newColumn = StructField(
        colName,
        dataType,
        nullable = true,
        builder.build())
      AlterTableChangeColumnCommand(ident.asTableIdentifier, colName, newColumn)

    case RenameColumn(ResolvedV1TableIdentifier(_), _, _) =>
      throw QueryCompilationErrors.operationOnlySupportedWithV2TableError("RENAME COLUMN")

    case DropColumns(ResolvedV1TableIdentifier(_), _, _) =>
      throw QueryCompilationErrors.operationOnlySupportedWithV2TableError("DROP COLUMN")

    case SetTableProperties(ResolvedV1TableIdentifier(ident), props) =>
      AlterTableSetPropertiesCommand(ident.asTableIdentifier, props, isView = false)

    case UnsetTableProperties(ResolvedV1TableIdentifier(ident), keys, ifExists) =>
      AlterTableUnsetPropertiesCommand(ident.asTableIdentifier, keys, ifExists, isView = false)

    case SetViewProperties(ResolvedView(ident, _), props) =>
      AlterTableSetPropertiesCommand(ident.asTableIdentifier, props, isView = true)

    case UnsetViewProperties(ResolvedView(ident, _), keys, ifExists) =>
      AlterTableUnsetPropertiesCommand(ident.asTableIdentifier, keys, ifExists, isView = true)

    case DescribeNamespace(DatabaseInSessionCatalog(db), extended, output) if conf.useV1Command =>
      DescribeDatabaseCommand(db, extended, output)

    case SetNamespaceProperties(DatabaseInSessionCatalog(db), properties) if conf.useV1Command =>
      AlterDatabasePropertiesCommand(db, properties)

    case SetNamespaceLocation(DatabaseInSessionCatalog(db), location) if conf.useV1Command =>
      AlterDatabaseSetLocationCommand(db, location)

    case RenameTable(ResolvedV1TableOrViewIdentifier(oldName), newName, isView) =>
      AlterTableRenameCommand(oldName.asTableIdentifier, newName.asTableIdentifier, isView)

    // Use v1 command to describe (temp) view, as v2 catalog doesn't support view yet.
    case DescribeRelation(
         ResolvedV1TableOrViewIdentifier(ident), partitionSpec, isExtended, output) =>
      DescribeTableCommand(ident.asTableIdentifier, partitionSpec, isExtended, output)

    case DescribeColumn(
         ResolvedViewIdentifier(ident), column: UnresolvedAttribute, isExtended, output) =>
      // For views, the column will not be resolved by `ResolveReferences` because
      // `ResolvedView` stores only the identifier.
      DescribeColumnCommand(ident.asTableIdentifier, column.nameParts, isExtended, output)

    case DescribeColumn(ResolvedV1TableIdentifier(ident), column, isExtended, output) =>
      column match {
        case u: UnresolvedAttribute =>
          throw QueryCompilationErrors.columnDoesNotExistError(u.name)
        case a: Attribute =>
          DescribeColumnCommand(ident.asTableIdentifier, a.qualifier :+ a.name, isExtended, output)
        case Alias(child, _) =>
          throw QueryCompilationErrors.commandNotSupportNestedColumnError(
            "DESC TABLE COLUMN", toPrettySQL(child))
        case _ =>
          throw new IllegalStateException(s"[BUG] unexpected column expression: $column")
      }

    // For CREATE TABLE [AS SELECT], we should use the v1 command if the catalog is resolved to the
    // session catalog and the table provider is not v2.
    case c @ CreateTable(ResolvedDBObjectName(catalog, name), _, _, _, _)
        if isSessionCatalog(catalog) =>
      val (storageFormat, provider) = getStorageFormatAndProvider(
        c.tableSpec.provider, c.tableSpec.options, c.tableSpec.location, c.tableSpec.serde,
        ctas = false)
      if (!isV2Provider(provider)) {
        constructV1TableCmd(None, c.tableSpec, name, c.tableSchema, c.partitioning,
          c.ignoreIfExists, storageFormat, provider)
      } else {
        c
      }

    case c @ CreateTableAsSelect(ResolvedDBObjectName(catalog, name), _, _, _, writeOptions, _)
        if isSessionCatalog(catalog) =>
      val (storageFormat, provider) = getStorageFormatAndProvider(
        c.tableSpec.provider,
        c.tableSpec.options ++ writeOptions,
        c.tableSpec.location,
        c.tableSpec.serde,
        ctas = true)

      if (!isV2Provider(provider)) {
        constructV1TableCmd(Some(c.query), c.tableSpec, name, new StructType, c.partitioning,
          c.ignoreIfExists, storageFormat, provider)
      } else {
        c
      }

    case RefreshTable(ResolvedV1TableIdentifier(ident)) =>
      RefreshTableCommand(ident.asTableIdentifier)

    case RefreshTable(r: ResolvedView) =>
      RefreshTableCommand(r.identifier.asTableIdentifier)

    // For REPLACE TABLE [AS SELECT], we should fail if the catalog is resolved to the
    // session catalog and the table provider is not v2.
    case c @ ReplaceTable(ResolvedDBObjectName(catalog, _), _, _, _, _)
        if isSessionCatalog(catalog) =>
      val provider = c.tableSpec.provider.getOrElse(conf.defaultDataSourceName)
      if (!isV2Provider(provider)) {
        throw QueryCompilationErrors.operationOnlySupportedWithV2TableError("REPLACE TABLE")
      } else {
        c
      }

    case c @ ReplaceTableAsSelect(ResolvedDBObjectName(catalog, _), _, _, _, _, _)
        if isSessionCatalog(catalog) =>
      val provider = c.tableSpec.provider.getOrElse(conf.defaultDataSourceName)
      if (!isV2Provider(provider)) {
        throw QueryCompilationErrors
          .operationOnlySupportedWithV2TableError("REPLACE TABLE AS SELECT")
      } else {
        c
      }

    case DropTable(ResolvedV1TableIdentifier(ident), ifExists, purge) =>
      DropTableCommand(ident.asTableIdentifier, ifExists, isView = false, purge = purge)

    // v1 DROP TABLE supports temp view.
    case DropTable(r: ResolvedView, ifExists, purge) =>
      if (!r.isTemp) {
        throw QueryCompilationErrors.cannotDropViewWithDropTableError
      }
      DropTableCommand(r.identifier.asTableIdentifier, ifExists, isView = false, purge = purge)

    case DropView(r: ResolvedView, ifExists) =>
      DropTableCommand(r.identifier.asTableIdentifier, ifExists, isView = true, purge = false)

    case c @ CreateNamespace(DatabaseNameInSessionCatalog(name), _, _) if conf.useV1Command =>
      val comment = c.properties.get(SupportsNamespaces.PROP_COMMENT)
      val location = c.properties.get(SupportsNamespaces.PROP_LOCATION)
      val newProperties = c.properties -- CatalogV2Util.NAMESPACE_RESERVED_PROPERTIES
      CreateDatabaseCommand(name, c.ifNotExists, location, comment, newProperties)

    case d @ DropNamespace(DatabaseInSessionCatalog(db), _, _) if conf.useV1Command =>
      DropDatabaseCommand(db, d.ifExists, d.cascade)

    case ShowTables(DatabaseInSessionCatalog(db), pattern, output) if conf.useV1Command =>
      ShowTablesCommand(Some(db), pattern, output)

    case ShowTableExtended(
        DatabaseInSessionCatalog(db),
        pattern,
        partitionSpec @ (None | Some(UnresolvedPartitionSpec(_, _))),
        output) =>
      val newOutput = if (conf.getConf(SQLConf.LEGACY_KEEP_COMMAND_OUTPUT_SCHEMA)) {
        assert(output.length == 4)
        output.head.withName("database") +: output.tail
      } else {
        output
      }
      val tablePartitionSpec = partitionSpec.map(_.asInstanceOf[UnresolvedPartitionSpec].spec)
      ShowTablesCommand(Some(db), Some(pattern), newOutput, true, tablePartitionSpec)

    // ANALYZE TABLE works on permanent views if the views are cached.
    case AnalyzeTable(ResolvedV1TableOrViewIdentifier(ident), partitionSpec, noScan) =>
      if (partitionSpec.isEmpty) {
        AnalyzeTableCommand(ident.asTableIdentifier, noScan)
      } else {
        AnalyzePartitionCommand(ident.asTableIdentifier, partitionSpec, noScan)
      }

    case AnalyzeTables(DatabaseInSessionCatalog(db), noScan) =>
      AnalyzeTablesCommand(Some(db), noScan)

    case AnalyzeColumn(ResolvedV1TableOrViewIdentifier(ident), columnNames, allColumns) =>
      AnalyzeColumnCommand(ident.asTableIdentifier, columnNames, allColumns)

    case RepairTable(ResolvedV1TableIdentifier(ident), addPartitions, dropPartitions) =>
      RepairTableCommand(ident.asTableIdentifier, addPartitions, dropPartitions)

    case LoadData(ResolvedV1TableIdentifier(ident), path, isLocal, isOverwrite, partition) =>
      LoadDataCommand(
        ident.asTableIdentifier,
        path,
        isLocal,
        isOverwrite,
        partition)

    case ShowCreateTable(ResolvedV1TableOrViewIdentifier(ident), asSerde, output) if asSerde =>
      ShowCreateTableAsSerdeCommand(ident.asTableIdentifier, output)

    // If target is view, force use v1 command
    case ShowCreateTable(ResolvedViewIdentifier(ident), _, output) =>
      ShowCreateTableCommand(ident.asTableIdentifier, output)

    case ShowCreateTable(ResolvedV1TableIdentifier(ident), _, output)
      if conf.useV1Command => ShowCreateTableCommand(ident.asTableIdentifier, output)

    case ShowCreateTable(ResolvedTable(catalog, ident, table: V1Table, _), _, output)
      if isSessionCatalog(catalog) && DDLUtils.isHiveTable(table.catalogTable) =>
        ShowCreateTableCommand(ident.asTableIdentifier, output)

    case TruncateTable(ResolvedV1TableIdentifier(ident)) =>
      TruncateTableCommand(ident.asTableIdentifier, None)

    case TruncatePartition(ResolvedV1TableIdentifier(ident), partitionSpec) =>
      TruncateTableCommand(
        ident.asTableIdentifier,
        Seq(partitionSpec).asUnresolvedPartitionSpecs.map(_.spec).headOption)

    case ShowPartitions(
        ResolvedV1TableOrViewIdentifier(ident),
        pattern @ (None | Some(UnresolvedPartitionSpec(_, _))), output) =>
      ShowPartitionsCommand(
        ident.asTableIdentifier,
        output,
        pattern.map(_.asInstanceOf[UnresolvedPartitionSpec].spec))

    case ShowColumns(ResolvedV1TableOrViewIdentifier(ident), ns, output) =>
      val v1TableName = ident.asTableIdentifier
      val resolver = conf.resolver
      val db = ns match {
        case Some(db) if v1TableName.database.exists(!resolver(_, db.head)) =>
          throw QueryCompilationErrors.showColumnsWithConflictDatabasesError(db, v1TableName)
        case _ => ns.map(_.head)
      }
      ShowColumnsCommand(db, v1TableName, output)

    case RecoverPartitions(ResolvedV1TableIdentifier(ident)) =>
      RepairTableCommand(
        ident.asTableIdentifier,
        enableAddPartitions = true,
        enableDropPartitions = false,
        "ALTER TABLE RECOVER PARTITIONS")

    case AddPartitions(ResolvedV1TableIdentifier(ident), partSpecsAndLocs, ifNotExists) =>
      AlterTableAddPartitionCommand(
        ident.asTableIdentifier,
        partSpecsAndLocs.asUnresolvedPartitionSpecs.map(spec => (spec.spec, spec.location)),
        ifNotExists)

    case RenamePartitions(
        ResolvedV1TableIdentifier(ident),
        UnresolvedPartitionSpec(from, _),
        UnresolvedPartitionSpec(to, _)) =>
      AlterTableRenamePartitionCommand(ident.asTableIdentifier, from, to)

    case DropPartitions(
        ResolvedV1TableIdentifier(ident), specs, ifExists, purge) =>
      AlterTableDropPartitionCommand(
        ident.asTableIdentifier,
        specs.asUnresolvedPartitionSpecs.map(_.spec),
        ifExists,
        purge,
        retainData = false)

    case SetTableSerDeProperties(
        ResolvedV1TableIdentifier(ident),
        serdeClassName,
        serdeProperties,
        partitionSpec) =>
      AlterTableSerDePropertiesCommand(
        ident.asTableIdentifier,
        serdeClassName,
        serdeProperties,
        partitionSpec)

    case SetTableLocation(ResolvedV1TableIdentifier(ident), partitionSpec, location) =>
      AlterTableSetLocationCommand(ident.asTableIdentifier, partitionSpec, location)

    case AlterViewAs(ResolvedView(ident, _), originalText, query) =>
      AlterViewAsCommand(
        ident.asTableIdentifier,
        originalText,
        query)

    case CreateView(ResolvedDBObjectName(catalog, nameParts), userSpecifiedColumns, comment,
        properties, originalText, child, allowExisting, replace) =>
      if (isSessionCatalog(catalog)) {
        CreateViewCommand(
          name = nameParts.asTableIdentifier,
          userSpecifiedColumns = userSpecifiedColumns,
          comment = comment,
          properties = properties,
          originalText = originalText,
          plan = child,
          allowExisting = allowExisting,
          replace = replace,
          viewType = PersistedView)
      } else {
        throw QueryCompilationErrors.missingCatalogAbilityError(catalog, "views")
      }

    case ShowViews(ns: ResolvedNamespace, pattern, output) =>
      ns match {
        case DatabaseInSessionCatalog(db) => ShowViewsCommand(db, pattern, output)
        case _ =>
          throw QueryCompilationErrors.missingCatalogAbilityError(ns.catalog, "views")
      }

    // If target is view, force use v1 command
    case ShowTableProperties(ResolvedViewIdentifier(ident), propertyKey, output) =>
      ShowTablePropertiesCommand(ident.asTableIdentifier, propertyKey, output)

    case ShowTableProperties(ResolvedV1TableIdentifier(ident), propertyKey, output)
        if conf.useV1Command =>
      ShowTablePropertiesCommand(ident.asTableIdentifier, propertyKey, output)

    case DescribeFunction(ResolvedNonPersistentFunc(_, V1Function(info)), extended) =>
      DescribeFunctionCommand(info, extended)

    case DescribeFunction(ResolvedPersistentFunc(catalog, _, func), extended) =>
      if (isSessionCatalog(catalog)) {
        DescribeFunctionCommand(func.asInstanceOf[V1Function].info, extended)
      } else {
        throw QueryCompilationErrors.missingCatalogAbilityError(catalog, "functions")
      }

    case ShowFunctions(ns: ResolvedNamespace, userScope, systemScope, pattern, output) =>
      ns match {
        case DatabaseInSessionCatalog(db) =>
          ShowFunctionsCommand(db, pattern, userScope, systemScope, output)
        case _ =>
          throw QueryCompilationErrors.missingCatalogAbilityError(ns.catalog, "functions")
      }

    case DropFunction(ResolvedPersistentFunc(catalog, identifier, _), ifExists) =>
      if (isSessionCatalog(catalog)) {
        val funcIdentifier = identifier.asFunctionIdentifier
        DropFunctionCommand(funcIdentifier, ifExists, false)
      } else {
        throw QueryCompilationErrors.missingCatalogAbilityError(catalog, "DROP FUNCTION")
      }

    case RefreshFunction(ResolvedPersistentFunc(catalog, identifier, _)) =>
      if (isSessionCatalog(catalog)) {
        val funcIdentifier = identifier.asFunctionIdentifier
        RefreshFunctionCommand(funcIdentifier.database, funcIdentifier.funcName)
      } else {
        throw QueryCompilationErrors.missingCatalogAbilityError(catalog, "REFRESH FUNCTION")
      }

    case CreateFunction(ResolvedDBObjectName(catalog, nameParts),
        className, resources, ignoreIfExists, replace) =>
      if (isSessionCatalog(catalog)) {
        val database = if (nameParts.length > 2) {
          throw QueryCompilationErrors.requiresSinglePartNamespaceError(nameParts)
        } else if (nameParts.length == 2) {
          Some(nameParts.head)
        } else {
          None
        }
        val identifier = FunctionIdentifier(nameParts.last, database)
        CreateFunctionCommand(
          identifier,
          className,
          resources,
          false,
          ignoreIfExists,
          replace)
      } else {
        throw QueryCompilationErrors.missingCatalogAbilityError(catalog, "CREATE FUNCTION")
      }
  }

  private def constructV1TableCmd(
      query: Option[LogicalPlan],
      tableSpec: TableSpec,
      name: Seq[String],
      tableSchema: StructType,
      partitioning: Seq[Transform],
      ignoreIfExists: Boolean,
      storageFormat: CatalogStorageFormat,
      provider: String): CreateTableV1 = {
    val tableDesc = buildCatalogTable(name.asTableIdentifier, tableSchema,
        partitioning, tableSpec.properties, provider,
        tableSpec.location, tableSpec.comment, storageFormat, tableSpec.external)
    val mode = if (ignoreIfExists) SaveMode.Ignore else SaveMode.ErrorIfExists
    CreateTableV1(tableDesc, mode, query)
  }

  private def getStorageFormatAndProvider(
      provider: Option[String],
      options: Map[String, String],
      location: Option[String],
      maybeSerdeInfo: Option[SerdeInfo],
      ctas: Boolean): (CatalogStorageFormat, String) = {
    val nonHiveStorageFormat = CatalogStorageFormat.empty.copy(
      locationUri = location.map(CatalogUtils.stringToURI),
      properties = options)
    val defaultHiveStorage = HiveSerDe.getDefaultStorage(conf).copy(
      locationUri = location.map(CatalogUtils.stringToURI),
      properties = options)

    if (provider.isDefined) {
      // The parser guarantees that USING and STORED AS/ROW FORMAT won't co-exist.
      if (maybeSerdeInfo.isDefined) {
        throw QueryCompilationErrors.cannotCreateTableWithBothProviderAndSerdeError(
          provider, maybeSerdeInfo)
      }
      (nonHiveStorageFormat, provider.get)
    } else if (maybeSerdeInfo.isDefined) {
      val serdeInfo = maybeSerdeInfo.get
      SerdeInfo.checkSerdePropMerging(serdeInfo.serdeProperties, defaultHiveStorage.properties)
      val storageFormat = if (serdeInfo.storedAs.isDefined) {
        // If `STORED AS fileFormat` is used, infer inputFormat, outputFormat and serde from it.
        HiveSerDe.sourceToSerDe(serdeInfo.storedAs.get) match {
          case Some(hiveSerde) =>
            defaultHiveStorage.copy(
              inputFormat = hiveSerde.inputFormat.orElse(defaultHiveStorage.inputFormat),
              outputFormat = hiveSerde.outputFormat.orElse(defaultHiveStorage.outputFormat),
              // User specified serde takes precedence over the one inferred from file format.
              serde = serdeInfo.serde.orElse(hiveSerde.serde).orElse(defaultHiveStorage.serde),
              properties = serdeInfo.serdeProperties ++ defaultHiveStorage.properties)
          case _ => throw QueryCompilationErrors.invalidFileFormatForStoredAsError(serdeInfo)
        }
      } else {
        defaultHiveStorage.copy(
          inputFormat =
            serdeInfo.formatClasses.map(_.input).orElse(defaultHiveStorage.inputFormat),
          outputFormat =
            serdeInfo.formatClasses.map(_.output).orElse(defaultHiveStorage.outputFormat),
          serde = serdeInfo.serde.orElse(defaultHiveStorage.serde),
          properties = serdeInfo.serdeProperties ++ defaultHiveStorage.properties)
      }
      (storageFormat, DDLUtils.HIVE_PROVIDER)
    } else {
      // If neither USING nor STORED AS/ROW FORMAT is specified, we create native data source
      // tables if:
      //   1. `LEGACY_CREATE_HIVE_TABLE_BY_DEFAULT` is false, or
      //   2. It's a CTAS and `conf.convertCTAS` is true.
      val createHiveTableByDefault = conf.getConf(SQLConf.LEGACY_CREATE_HIVE_TABLE_BY_DEFAULT)
      if (!createHiveTableByDefault || (ctas && conf.convertCTAS)) {
        (nonHiveStorageFormat, conf.defaultDataSourceName)
      } else {
        logWarning("A Hive serde table will be created as there is no table provider " +
          s"specified. You can set ${SQLConf.LEGACY_CREATE_HIVE_TABLE_BY_DEFAULT.key} to false " +
          "so that native data source table will be created instead.")
        (defaultHiveStorage, DDLUtils.HIVE_PROVIDER)
      }
    }
  }

  private def buildCatalogTable(
      table: TableIdentifier,
      schema: StructType,
      partitioning: Seq[Transform],
      properties: Map[String, String],
      provider: String,
      location: Option[String],
      comment: Option[String],
      storageFormat: CatalogStorageFormat,
      external: Boolean): CatalogTable = {
    val tableType = if (external || location.isDefined) {
      CatalogTableType.EXTERNAL
    } else {
      CatalogTableType.MANAGED
    }
    val (partitionColumns, maybeBucketSpec) = partitioning.toSeq.convertTransforms

    CatalogTable(
      identifier = table,
      tableType = tableType,
      storage = storageFormat,
      schema = schema,
      provider = Some(provider),
      partitionColumnNames = partitionColumns,
      bucketSpec = maybeBucketSpec,
      properties = properties,
      comment = comment)
  }

  object SessionCatalogAndTable {
    def unapply(nameParts: Seq[String]): Option[(CatalogPlugin, Seq[String])] = nameParts match {
      case SessionCatalogAndIdentifier(catalog, ident) =>
        Some(catalog -> ident.asMultipartIdentifier)
      case _ => None
    }
  }

  object ResolvedViewIdentifier {
    def unapply(resolved: LogicalPlan): Option[Identifier] = resolved match {
      case ResolvedView(ident, _) => Some(ident)
      case _ => None
    }
  }

  object ResolvedV1TableAndIdentifier {
    def unapply(resolved: LogicalPlan): Option[(V1Table, Identifier)] = resolved match {
      case ResolvedTable(catalog, ident, table: V1Table, _) if isSessionCatalog(catalog) =>
        Some(table -> ident)
      case _ => None
    }
  }

  object ResolvedV1TableIdentifier {
    def unapply(resolved: LogicalPlan): Option[Identifier] = resolved match {
      case ResolvedTable(catalog, ident, _: V1Table, _) if isSessionCatalog(catalog) => Some(ident)
      case _ => None
    }
  }

  object ResolvedV1TableOrViewIdentifier {
    def unapply(resolved: LogicalPlan): Option[Identifier] = resolved match {
      case ResolvedV1TableIdentifier(ident) => Some(ident)
      case ResolvedViewIdentifier(ident) => Some(ident)
      case _ => None
    }
  }

  private def assertTopLevelColumn(colName: Seq[String], command: String): Unit = {
    if (colName.length > 1) {
      throw QueryCompilationErrors.commandNotSupportNestedColumnError(command, colName.quoted)
    }
  }

  private def convertToStructField(col: QualifiedColType): StructField = {
    val builder = new MetadataBuilder
    col.comment.foreach(builder.putString("comment", _))
    col.default.map {
      value: String => builder.putString(DefaultCols.CURRENT_DEFAULT_COLUMN_METADATA_KEY, value)
    }
    StructField(col.name.head, col.dataType, nullable = true, builder.build())
  }

  private def isV2Provider(provider: String): Boolean = {
    // Return earlier since `lookupDataSourceV2` may fail to resolve provider "hive" to
    // `HiveFileFormat`, when running tests in sql/core.
    if (DDLUtils.isHiveTable(Some(provider))) return false
    DataSource.lookupDataSourceV2(provider, conf) match {
      // TODO(SPARK-28396): Currently file source v2 can't work with tables.
      case Some(_: FileDataSourceV2) => false
      case Some(_) => true
      case _ => false
    }
  }

  private object DatabaseInSessionCatalog {
    def unapply(resolved: ResolvedNamespace): Option[String] = resolved match {
      case ResolvedNamespace(catalog, _) if !isSessionCatalog(catalog) => None
      case ResolvedNamespace(_, Seq()) =>
        throw QueryCompilationErrors.databaseFromV1SessionCatalogNotSpecifiedError()
      case ResolvedNamespace(_, Seq(dbName)) => Some(dbName)
      case _ =>
        assert(resolved.namespace.length > 1)
        throw QueryCompilationErrors.nestedDatabaseUnsupportedByV1SessionCatalogError(
          resolved.namespace.map(quoteIfNeeded).mkString("."))
    }
  }

  private object DatabaseNameInSessionCatalog {
    def unapply(resolved: ResolvedDBObjectName): Option[String] = resolved match {
      case ResolvedDBObjectName(catalog, _) if !isSessionCatalog(catalog) => None
      case ResolvedDBObjectName(_, Seq(dbName)) => Some(dbName)
      case _ =>
        assert(resolved.nameParts.length > 1)
        throw QueryCompilationErrors.invalidDatabaseNameError(resolved.nameParts.quoted)
    }
  }
}
