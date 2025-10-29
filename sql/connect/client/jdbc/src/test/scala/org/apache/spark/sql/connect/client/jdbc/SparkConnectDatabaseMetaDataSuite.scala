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

package org.apache.spark.sql.connect.client.jdbc

import java.sql.{Array => _, _}

import org.apache.spark.SparkBuildInfo.{spark_version => SPARK_VERSION}
import org.apache.spark.sql.connect.client.jdbc.test.JdbcHelper
import org.apache.spark.sql.connect.test.{ConnectFunSuite, RemoteSparkSession}
import org.apache.spark.util.VersionUtils

class SparkConnectDatabaseMetaDataSuite extends ConnectFunSuite with RemoteSparkSession
    with JdbcHelper {

  def jdbcUrl: String = s"jdbc:sc://localhost:$serverPort"

  test("SparkConnectDatabaseMetaData simple methods") {
    withConnection { conn =>
      val spark = conn.asInstanceOf[SparkConnectConnection].spark
      val metadata = conn.getMetaData
      assert(metadata.allProceduresAreCallable === false)
      assert(metadata.allTablesAreSelectable === false)
      assert(metadata.getURL === jdbcUrl)
      assert(metadata.isReadOnly === false)
      assert(metadata.nullsAreSortedHigh === false)
      assert(metadata.nullsAreSortedLow === false)
      assert(metadata.nullsAreSortedAtStart === false)
      assert(metadata.nullsAreSortedAtEnd === false)
      assert(metadata.getUserName === spark.client.configuration.userName)
      assert(metadata.getDatabaseProductName === "Apache Spark Connect Server")
      assert(metadata.getDatabaseProductVersion === spark.version)
      assert(metadata.getDriverVersion === SPARK_VERSION)
      assert(metadata.getDriverMajorVersion === VersionUtils.majorVersion(SPARK_VERSION))
      assert(metadata.getDriverMinorVersion === VersionUtils.minorVersion(SPARK_VERSION))
      assert(metadata.usesLocalFiles === false)
      assert(metadata.usesLocalFilePerTable === false)
      assert(metadata.supportsMixedCaseIdentifiers === false)
      assert(metadata.storesUpperCaseIdentifiers === false)
      assert(metadata.storesLowerCaseIdentifiers === false)
      assert(metadata.storesMixedCaseIdentifiers === false)
      assert(metadata.supportsMixedCaseQuotedIdentifiers === false)
      assert(metadata.storesUpperCaseQuotedIdentifiers === false)
      assert(metadata.storesLowerCaseQuotedIdentifiers === false)
      assert(metadata.storesMixedCaseQuotedIdentifiers === false)
      assert(metadata.getIdentifierQuoteString === "`")
      assert(metadata.getExtraNameCharacters === "")
      assert(metadata.supportsAlterTableWithAddColumn === true)
      assert(metadata.supportsAlterTableWithDropColumn === true)
      assert(metadata.supportsColumnAliasing === true)
      assert(metadata.nullPlusNonNullIsNull === true)
      assert(metadata.supportsTableCorrelationNames === true)
      assert(metadata.supportsDifferentTableCorrelationNames === false)
      assert(metadata.supportsExpressionsInOrderBy === true)
      assert(metadata.supportsOrderByUnrelated === true)
      assert(metadata.supportsGroupBy === true)
      assert(metadata.supportsGroupByUnrelated === true)
      assert(metadata.supportsGroupByBeyondSelect === true)
      assert(metadata.supportsLikeEscapeClause === true)
      assert(metadata.supportsMultipleResultSets === false)
      assert(metadata.supportsMultipleTransactions === false)
      assert(metadata.supportsNonNullableColumns === true)
      assert(metadata.supportsMinimumSQLGrammar === true)
      assert(metadata.supportsCoreSQLGrammar === true)
      assert(metadata.supportsExtendedSQLGrammar === false)
      assert(metadata.supportsANSI92EntryLevelSQL === true)
      assert(metadata.supportsANSI92IntermediateSQL === false)
      assert(metadata.supportsANSI92FullSQL === false)
      assert(metadata.supportsIntegrityEnhancementFacility === false)
      assert(metadata.supportsOuterJoins === true)
      assert(metadata.supportsFullOuterJoins === true)
      assert(metadata.supportsLimitedOuterJoins === true)
      assert(metadata.getSchemaTerm === "schema")
      assert(metadata.getProcedureTerm === "procedure")
      assert(metadata.getCatalogTerm === "catalog")
      assert(metadata.isCatalogAtStart === true)
      assert(metadata.getCatalogSeparator === ".")
      assert(metadata.supportsSchemasInDataManipulation === true)
      assert(metadata.supportsSchemasInProcedureCalls === true)
      assert(metadata.supportsSchemasInTableDefinitions === true)
      assert(metadata.supportsSchemasInIndexDefinitions === true)
      assert(metadata.supportsCatalogsInDataManipulation === true)
      assert(metadata.supportsCatalogsInProcedureCalls === true)
      assert(metadata.supportsCatalogsInTableDefinitions === true)
      assert(metadata.supportsCatalogsInIndexDefinitions === true)
      assert(metadata.supportsPositionedDelete === false)
      assert(metadata.supportsPositionedUpdate === false)
      assert(metadata.supportsSelectForUpdate === false)
      assert(metadata.supportsStoredProcedures === true)
      assert(metadata.supportsSubqueriesInComparisons === true)
      assert(metadata.supportsSubqueriesInExists === true)
      assert(metadata.supportsSubqueriesInIns === true)
      assert(metadata.supportsSubqueriesInQuantifieds === true)
      assert(metadata.supportsCorrelatedSubqueries === true)
      assert(metadata.supportsUnion === true)
      assert(metadata.supportsUnionAll === true)
      assert(metadata.supportsOpenCursorsAcrossCommit === false)
      assert(metadata.supportsOpenCursorsAcrossRollback === false)
      assert(metadata.supportsOpenStatementsAcrossCommit === false)
      assert(metadata.supportsOpenStatementsAcrossRollback === false)
      assert(metadata.getMaxBinaryLiteralLength === 0)
      assert(metadata.getMaxCharLiteralLength === 0)
      assert(metadata.getMaxColumnNameLength === 0)
      assert(metadata.getMaxColumnsInGroupBy === 0)
      assert(metadata.getMaxColumnsInIndex === 0)
      assert(metadata.getMaxColumnsInOrderBy === 0)
      assert(metadata.getMaxColumnsInSelect === 0)
      assert(metadata.getMaxColumnsInTable === 0)
      assert(metadata.getMaxConnections === 0)
      assert(metadata.getMaxCursorNameLength === 0)
      assert(metadata.getMaxIndexLength === 0)
      assert(metadata.getMaxSchemaNameLength === 0)
      assert(metadata.getMaxProcedureNameLength === 0)
      assert(metadata.getMaxCatalogNameLength === 0)
      assert(metadata.getMaxRowSize === 0)
      assert(metadata.doesMaxRowSizeIncludeBlobs === false)
      assert(metadata.getMaxStatementLength === 0)
      assert(metadata.getMaxStatements === 0)
      assert(metadata.getMaxTableNameLength === 0)
      assert(metadata.getMaxTablesInSelect === 0)
      assert(metadata.getMaxUserNameLength === 0)
      assert(metadata.getDefaultTransactionIsolation === Connection.TRANSACTION_NONE)
      assert(metadata.supportsTransactions === false)
      Seq(Connection.TRANSACTION_NONE, Connection.TRANSACTION_READ_UNCOMMITTED,
        Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ,
        Connection.TRANSACTION_SERIALIZABLE).foreach { level =>
        val actual = metadata.supportsTransactionIsolationLevel(level)
        val expected = level == Connection.TRANSACTION_NONE
        assert(actual === expected)
      }
      assert(metadata.supportsDataDefinitionAndDataManipulationTransactions === false)
      assert(metadata.supportsDataManipulationTransactionsOnly === false)
      assert(metadata.dataDefinitionCausesTransactionCommit === false)
      assert(metadata.dataDefinitionIgnoredInTransactions === false)
      Seq(ResultSet.TYPE_FORWARD_ONLY, ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.TYPE_SCROLL_SENSITIVE).foreach { typ =>
        var actual = metadata.supportsResultSetType(typ)
        var expected = typ == ResultSet.TYPE_FORWARD_ONLY
        assert(actual === expected)
        Seq(ResultSet.CONCUR_READ_ONLY, ResultSet.CONCUR_UPDATABLE).foreach { concur =>
          actual = metadata.supportsResultSetConcurrency(typ, concur)
          expected = typ == ResultSet.TYPE_FORWARD_ONLY && concur == ResultSet.CONCUR_READ_ONLY
          assert(actual === expected)
        }
        assert(metadata.ownUpdatesAreVisible(typ) === false)
        assert(metadata.ownDeletesAreVisible(typ) === false)
        assert(metadata.ownInsertsAreVisible(typ) === false)
        assert(metadata.othersUpdatesAreVisible(typ) === false)
        assert(metadata.othersDeletesAreVisible(typ) === false)
        assert(metadata.othersInsertsAreVisible(typ) === false)
        assert(metadata.updatesAreDetected(typ) === false)
        assert(metadata.deletesAreDetected(typ) === false)
        assert(metadata.insertsAreDetected(typ) === false)
      }
      assert(metadata.supportsBatchUpdates === false)
      assert(metadata.getConnection === conn)
      assert(metadata.supportsSavepoints === false)
      assert(metadata.supportsMultipleOpenResults === false)
      assert(metadata.supportsGetGeneratedKeys === false)
      assert(metadata.supportsResultSetHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT) === false)
      assert(metadata.supportsResultSetHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT) === true)
      assert(metadata.getResultSetHoldability === ResultSet.CLOSE_CURSORS_AT_COMMIT)
      assert(metadata.getDatabaseMajorVersion === VersionUtils.majorVersion(spark.version))
      assert(metadata.getDatabaseMinorVersion === VersionUtils.minorVersion(spark.version))
      assert(metadata.getJDBCMajorVersion === 4)
      assert(metadata.getJDBCMinorVersion === 3)
      assert(metadata.getSQLStateType === DatabaseMetaData.sqlStateSQL)
      assert(metadata.locatorsUpdateCopy === false)
      assert(metadata.supportsStoredFunctionsUsingCallSyntax === false)
      assert(metadata.autoCommitFailureClosesAllResultSets === false)
      assert(metadata.supportsStatementPooling === false)
      assert(metadata.getRowIdLifetime === RowIdLifetime.ROWID_UNSUPPORTED)
      assert(metadata.generatedKeyAlwaysReturned === false)
      assert(metadata.getMaxLogicalLobSize === 0)
      assert(metadata.supportsRefCursors === false)
      assert(metadata.supportsSharding === false)
    }
  }
}
