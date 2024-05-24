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
package org.apache.spark.sql.jdbc

import java.sql.{Connection, ResultSet, Statement}
import java.util.Locale

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.sql.{QueryGeneratorHelper, QueryTest}
import org.apache.spark.sql.Row
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.tags.DockerTest

/**
 * This suite is used to generate subqueries, and test Spark against Postgres.
 * To run this test suite for a specific version (e.g., postgres:16.3-alpine):
 * {{{
 *   ENABLE_DOCKER_INTEGRATION_TESTS=1 POSTGRES_DOCKER_IMAGE_NAME=postgres:16.3-alpine
 *     ./build/sbt -Pdocker-integration-tests
 *     "docker-integration-tests/testOnly org.apache.spark.sql.jdbc.GeneratedSubquerySuite"
 * }}}
 */
@DockerTest
class GeneratedSubquerySuite extends DockerJDBCIntegrationSuite with QueryGeneratorHelper {

  override val db = new DatabaseOnDocker {
    override val imageName = sys.env.getOrElse("POSTGRES_DOCKER_IMAGE_NAME", "postgres:16.3-alpine")
    override val env = Map(
      "POSTGRES_PASSWORD" -> "rootpass"
    )
    override val usesIpc = false
    override val jdbcPort = 5432
    override def getJdbcUrl(ip: String, port: Int): String =
      s"jdbc:postgresql://$ip:$port/postgres?user=postgres&password=rootpass"
  }

  private val FIRST_COLUMN = "a"
  private val SECOND_COLUMN = "b"

  // Table definitions
  private val INNER_TABLE_NAME = "inner_table"
  private val INNER_TABLE_SCHEMA = Seq(
  Attribute(FIRST_COLUMN, Some(INNER_TABLE_NAME)),
  Attribute(SECOND_COLUMN, Some(INNER_TABLE_NAME)))
  private val INNER_TABLE = TableRelation(INNER_TABLE_NAME, INNER_TABLE_SCHEMA)

  private val OUTER_TABLE_NAME = "outer_table"
  private val OUTER_TABLE_SCHEMA = Seq(
    Attribute(FIRST_COLUMN, Some(OUTER_TABLE_NAME)),
    Attribute(SECOND_COLUMN, Some(OUTER_TABLE_NAME)))
  private val OUTER_TABLE = TableRelation(OUTER_TABLE_NAME, OUTER_TABLE_SCHEMA)

  private val NO_MATCH_TABLE_NAME = "no_match_table"
  private val NO_MATCH_TABLE_SCHEMA = Seq(
    Attribute(FIRST_COLUMN, Some(NO_MATCH_TABLE_NAME)),
    Attribute(SECOND_COLUMN, Some(NO_MATCH_TABLE_NAME)))
  private val NO_MATCH_TABLE = TableRelation(NO_MATCH_TABLE_NAME, NO_MATCH_TABLE_SCHEMA)

  private val JOIN_TABLE_NAME = "join_table"
  private val JOIN_TABLE_SCHEMA = Seq(
    Attribute(FIRST_COLUMN, Some(JOIN_TABLE_NAME)),
    Attribute(SECOND_COLUMN, Some(JOIN_TABLE_NAME)))
  private val JOIN_TABLE = TableRelation(JOIN_TABLE_NAME, JOIN_TABLE_SCHEMA)

  private val NULL_TABLE_NAME = "null_table"
  private val NULL_TABLE_SCHEMA = Seq(
    Attribute(FIRST_COLUMN, Some(NULL_TABLE_NAME)),
    Attribute(SECOND_COLUMN, Some(NULL_TABLE_NAME)))
  private val NULL_TABLE = TableRelation(NULL_TABLE_NAME, NULL_TABLE_SCHEMA)

  override def dataPreparation(conn: Connection): Unit = {}

  /**
   * Function to generate a subquery given the following parameters:
   * @param innerTable The relation within the subquery.
   * @param correlationConditions Conditions referencing both inner and outer tables.
   * @param isDistinct Whether the result of the subquery is to be de-duplicated.
   * @param operatorInSubquery The operator to be included in this subquery.
   * @param isScalarSubquery Whether the subquery is a scalar subquery or not.
   */
  private def generateSubquery(
      innerTable: Relation,
      correlationConditions: Seq[Predicate],
      isDistinct: Boolean,
      operatorInSubquery: Operator,
      isScalarSubquery: Boolean): Query = {

    // Generating the From clause of the subquery:
    val fromClause = FromClause(Seq(innerTable))

    // Generating the Select clause of the subquery: consider Aggregation result expressions, if the
    // operator to be included is an Aggregate.
    val projections = operatorInSubquery match {
      case Aggregate(resultExpressions, _) => resultExpressions
      case _ => Seq(innerTable.output.head)
    }
    val selectClause = SelectClause(projections, isDistinct = isDistinct)

    // Generating the Where clause of the subquery: add correlation conditions, if any.
    val whereClause = if (correlationConditions.nonEmpty) {
      Some(WhereClause(correlationConditions))
    } else {
      None
    }

    // Generating the GroupBy clause of the subquery: add GroupBy if the operator to be included is
    // an Aggregate.
    val groupByClause = operatorInSubquery match {
      case a: Aggregate if a.groupingExpressions.nonEmpty =>
        Some(GroupByClause(a.groupingExpressions))
      case _ => None
    }

    // For the OrderBy, consider whether or not the result of the subquery is required to be sorted.
    // This is to maintain test determinism. This is affected by whether the subquery has a limit
    // clause.
    val requiresLimitOne = isScalarSubquery && (operatorInSubquery match {
      case a: Aggregate => a.groupingExpressions.nonEmpty
      case l: Limit => l.limitValue > 1
      case _ => true
    })

    val orderByClause = if (requiresLimitOne || operatorInSubquery.isInstanceOf[Limit]) {
      Some(OrderByClause(projections))
    } else {
      None
    }

    // For the Limit clause, consider whether the subquery needs to return 1 row, or whether the
    // operator to be included is a Limit.
    val limitClause = if (requiresLimitOne) {
      Some(Limit(1))
    } else {
      operatorInSubquery match {
        case limit: Limit => Some(limit)
        case _ => None
      }
    }

    Query(selectClause, fromClause, whereClause, groupByClause, orderByClause, limitClause)
  }

  /**
   * Generate a query (that has a subquery) with the given parameters.
   * @param innerTable Table within the subquery.
   * @param outerTable Table outside of the subquery, in the main query.
   * @param subqueryAlias
   * @param subqueryLocation The clause of the main query where the subquery is located.
   * @param subqueryType The type of subquery, such as SCALAR, RELATION, PREDICATE
   * @param isCorrelated Whether the subquery is to be correlated.
   * @param isDistinct Whether subquery results is to be de-duplicated, i.e. have a DISTINCT clause.
   * @param operatorInSubquery The operator to be included in the subquery.
   */
  private def generateQuery(
      innerTable: Relation,
      outerTable: Relation,
      subqueryAlias: String,
      subqueryLocation: SubqueryLocation.Value,
      subqueryType: SubqueryType.Value,
      isCorrelated: Boolean,
      isDistinct: Boolean,
      operatorInSubquery: Operator): Query = {

    // Correlation conditions, this is hardcoded for now.
    val correlationConditions = if (isCorrelated) {
      Seq(Equals(innerTable.output.head, outerTable.output.head))
    } else {
      Seq()
    }
    val isScalarSubquery = Seq(SubqueryType.ATTRIBUTE, SubqueryType.SCALAR_PREDICATE_EQUALS,
      SubqueryType.SCALAR_PREDICATE_LESS_THAN).contains(subqueryType)
    val subqueryOrganization = generateSubquery(
      innerTable, correlationConditions, isDistinct, operatorInSubquery, isScalarSubquery)

    val (queryProjection, selectClause, fromClause, whereClause) = subqueryLocation match {
      case SubqueryLocation.SELECT =>
        // If the subquery is in the FROM clause, then it is treated as an Attribute.
        val queryProjection = outerTable.output ++
          Seq(Alias(Subquery(subqueryOrganization), subqueryAlias))
        val fromClause = FromClause(Seq(outerTable))
        val selectClause = SelectClause(queryProjection)
        (queryProjection, selectClause, fromClause, None)
      case SubqueryLocation.FROM =>
        // If the subquery is in the FROM clause, then it is treated as a Relation.
        val subqueryProjection = subqueryOrganization.selectClause.projection
        // Transform the subquery projection as Attributes from a Relation.
        val subqueryOutput = subqueryProjection.map {
          case a: Attribute => Attribute(name = a.name, qualifier = Some(subqueryAlias))
          case a: Alias => Attribute(name = a.name, qualifier = Some(subqueryAlias))
        }
        val selectClause = SelectClause(subqueryOutput)
        val subqueryRelation = SubqueryRelation(subqueryAlias, subqueryOutput,
          subqueryOrganization)
        val fromClause = FromClause(Seq(subqueryRelation))
        (subqueryOutput, selectClause, fromClause, None)
      case SubqueryLocation.WHERE =>
        // If the subquery is in the WHERE clause, then it is treated as a Predicate.
        val queryProjection = outerTable.output
        val selectClause = SelectClause(queryProjection)
        val fromClause = FromClause(Seq(outerTable))
        // Hardcoded expression for "=", "<", "IN" and "NOT IN"
        val expr = outerTable.output.last
        val whereClausePredicate = subqueryType match {
          case SubqueryType.SCALAR_PREDICATE_EQUALS =>
            Equals(expr, Subquery(subqueryOrganization))
          case SubqueryType.SCALAR_PREDICATE_LESS_THAN =>
            LessThan(expr, Subquery(subqueryOrganization))
          case SubqueryType.EXISTS => Exists(subqueryOrganization)
          case SubqueryType.NOT_EXISTS => Not(Exists(subqueryOrganization))
          case SubqueryType.IN => In(expr, subqueryOrganization)
          case SubqueryType.NOT_IN => Not(In(expr, subqueryOrganization))
        }
        val whereClause = Some(WhereClause(Seq(whereClausePredicate)))
        (queryProjection, selectClause, fromClause, whereClause)
    }
    val orderByClause = Some(OrderByClause(queryProjection))

    Query(selectClause, fromClause, whereClause, groupByClause = None,
      orderByClause, limitClause = None)
  }

  private def getPostgresResult(stmt: Statement, sql: String): Array[Row] = {
    val isResultSet = stmt.execute(sql)
    val rows = ArrayBuffer[Row]()
    if (isResultSet) {
      val rs = stmt.getResultSet
      val metadata = rs.getMetaData
      while (rs.next()) {
        val row = Row.fromSeq((1 to metadata.getColumnCount).map(i => rs.getObject(i)))
        rows.append(row)
      }
      rows.toArray
    } else {
      Array.empty
    }
  }

  def generateQueriesAndRunTestCases(): Unit = {
    val tableCombinations = Seq(
      (INNER_TABLE, OUTER_TABLE),
      (INNER_TABLE, NULL_TABLE),
      (NULL_TABLE, OUTER_TABLE),
      (NO_MATCH_TABLE, OUTER_TABLE),
      (INNER_TABLE, NO_MATCH_TABLE)
    )

    val innerSubqueryAlias = "innerSubqueryAlias"
    val subqueryAlias = "subqueryAlias"
    val aggregationFunctionAlias = "aggFunctionAlias"
    val joinTypes = Seq(JoinType.INNER, JoinType.LEFT_OUTER, JoinType.RIGHT_OUTER)
    val setOperations = Seq(
      SetOperationType.UNION, SetOperationType.EXCEPT, SetOperationType.INTERSECT)

    // Generate combinations of the inner table to have joins and set operations (with the
    // JOIN_TABLE).
    val allRelationCombinations = tableCombinations.flatMap {
      case (innerTable, outerTable) =>
        val joins = joinTypes.map(joinType => JoinedRelation(
          leftRelation = innerTable,
          rightRelation = JOIN_TABLE,
          // Hardcoded keys for join condition.
          condition = Equals(innerTable.output.head, JOIN_TABLE.output.head),
          joinType = joinType))
        // Hardcoded select all for set operation.
        val leftTableQuery = Query(SelectClause(innerTable.output), FromClause(Seq(innerTable)))
        val rightTableQuery = Query(SelectClause(JOIN_TABLE.output), FromClause(Seq(JOIN_TABLE)))
        val setOps = setOperations.map(setOp =>
          SetOperation(leftTableQuery, rightTableQuery, setOp))
          .map(plan => {
            val output = innerTable.output.map(a => a.copy(qualifier = Some(innerSubqueryAlias)))
            SubqueryRelation(name = innerSubqueryAlias, output = output, inner = plan)
          })
        (Seq(innerTable) ++ joins ++ setOps).map(inner => (inner, outerTable))
    }

    def subqueryTypeChoices(subqueryLocation: SubqueryLocation.Value): Seq[SubqueryType.Value] = {
      subqueryLocation match {
        case SubqueryLocation.SELECT => Seq(SubqueryType.ATTRIBUTE)
        case SubqueryLocation.FROM => Seq(SubqueryType.RELATION)
        case SubqueryLocation.WHERE => Seq(
          SubqueryType.SCALAR_PREDICATE_LESS_THAN,
          SubqueryType.SCALAR_PREDICATE_EQUALS,
          SubqueryType.IN,
          SubqueryType.NOT_IN,
          SubqueryType.EXISTS,
          SubqueryType.NOT_EXISTS)
      }
    }

    // If the subquery is in the FROM clause of the main query, it cannot be correlated.
    def correlationChoices(subqueryLocation: SubqueryLocation.Value): Seq[Boolean] =
      subqueryLocation match {
        case SubqueryLocation.FROM => Seq(false)
        case _ => Seq(true, false)
      }

    def distinctChoices(subqueryOperator: Operator): Seq[Boolean] = {
      subqueryOperator match {
        // Don't do DISTINCT if there is no group by because it is redundant.
        case Aggregate(_, groupingExpressions) if groupingExpressions.isEmpty => Seq(false)
        case _ => Seq(true, false)
      }
    }

    case class SubquerySpec(query: String, isCorrelated: Boolean, subqueryType: SubqueryType.Value)

    val generatedQuerySpecs = scala.collection.mutable.Set[SubquerySpec]()

    // Generate queries across the different axis.
    for {
      (innerTable, outerTable) <- allRelationCombinations
      subqueryLocation <-
        Seq(SubqueryLocation.WHERE, SubqueryLocation.SELECT, SubqueryLocation.FROM)
      subqueryType <- subqueryTypeChoices(subqueryLocation)
      isCorrelated <- correlationChoices(subqueryLocation)
    } {
      // Hardcoded aggregation column and group by column.
      val (aggColumn, groupByColumn) = innerTable.output.head -> innerTable.output(1)
      val aggFunctions = Seq(Sum(aggColumn), Count(aggColumn))
        .map(af => Alias(af, aggregationFunctionAlias))
      val groupByOptions = Seq(true, false)
      // Generate all combinations of (aggFunction = sum/count, groupBy = true/false).
      val combinations = aggFunctions.flatMap(agg => groupByOptions.map(groupBy => (agg, groupBy)))
      val aggregates = combinations.map {
        case (af, groupBy) => Aggregate(Seq(af), if (groupBy) Seq(groupByColumn) else Seq())
      }
      val subqueryOperators = Seq(Limit(1), Limit(10)) ++ aggregates

      for {
        subqueryOperator <- subqueryOperators
        isDistinct <- distinctChoices(subqueryOperator)
      } {
        generatedQuerySpecs += SubquerySpec(generateQuery(innerTable, outerTable,
          subqueryAlias, subqueryLocation, subqueryType, isCorrelated, isDistinct,
          subqueryOperator).toString + ";", isCorrelated, subqueryType)
      }
    }

    // Partition the queries by (isCorrelated, subqueryLocation, SubqueryType).
    val partitionedQueries = generatedQuerySpecs.groupBy(query =>
      (query.isCorrelated, query.subqueryType))

    // Create separate test case for each partition.
    partitionedQueries.foreach { case ((isCorrelated, subqueryType), querySpec) =>
      val testName = if (isCorrelated) {
        s"correlated-${subqueryType.toString.toLowerCase(Locale.ROOT)}"
      } else {
        s"uncorrelated-${subqueryType.toString.toLowerCase(Locale.ROOT)}"
      }
      test(testName) {
        val conn = getConnection()
        val innerTableCreationSql = f"""
            |CREATE TEMPORARY VIEW ${INNER_TABLE.name}
            |(${INNER_TABLE.output.map(_.name).mkString(", ")}) AS VALUES
            |    (1, 1),
            |    (2, 2),
            |    (3, 3),
            |    (4, 4),
            |    (5, 5),
            |    (8, 8),
            |    (9, 9);
            |""".stripMargin
        val outerTableCreationSql = f"""
            |CREATE TEMPORARY VIEW ${OUTER_TABLE.name}
            |(${OUTER_TABLE.output.map(_.name).mkString(", ")}) AS VALUES
            |    (1, 1),
            |    (2, 1),
            |    (3, 3),
            |    (6, 6),
            |    (7, 7),
            |    (9, 9);
            |""".stripMargin
        val noMatchTableCreationSql = f"""
            |CREATE TEMPORARY VIEW ${NO_MATCH_TABLE.name}
            |(${NO_MATCH_TABLE.output.map(_.name).mkString(", ")}) AS VALUES
            |    (1000, 1000);
            |""".stripMargin
        val joinTableCreationSql = f"""
            |CREATE TEMPORARY VIEW ${JOIN_TABLE.name}
            |(${JOIN_TABLE.output.map(_.name).mkString(", ")}) AS VALUES
            |    (1, 1),
            |    (2, 1),
            |    (3, 3),
            |    (7, 8),
            |    (5, 6);
            |""".stripMargin
        val nullTableCreationSql = f"""
            |CREATE TEMPORARY VIEW ${NULL_TABLE.name}
            |(${NULL_TABLE.output.map(_.name).mkString(", ")}) AS
            | SELECT CAST(null AS int), CAST(null as int);
            |""".stripMargin
        conn.prepareStatement(innerTableCreationSql).executeUpdate()
        conn.prepareStatement(outerTableCreationSql).executeUpdate()
        conn.prepareStatement(nullTableCreationSql).executeUpdate()
        conn.prepareStatement(joinTableCreationSql).executeUpdate()
        conn.prepareStatement(noMatchTableCreationSql).executeUpdate()
        val localSparkSession = spark.newSession()
        localSparkSession.sql(innerTableCreationSql)
        localSparkSession.sql(outerTableCreationSql)
        localSparkSession.sql(nullTableCreationSql)
        localSparkSession.sql(joinTableCreationSql)
        localSparkSession.sql(noMatchTableCreationSql)
        // Enable ANSI so that { NULL IN { <empty> } } behavior is correct in Spark.
        localSparkSession.conf.set(SQLConf.ANSI_ENABLED.key, true)

        val generatedQueries = querySpec.map(_.query).toSeq
        // Randomize query order because we are taking a subset of queries.
        val shuffledQueries = scala.util.Random.shuffle(generatedQueries)

        // Run generated queries on both Spark and Postgres, and test against each other.
        shuffledQueries.take(GeneratedSubquerySuite.NUM_QUERIES_PER_TEST).foreach { sqlStr =>
          if (!GeneratedSubquerySuite.KNOWN_QUERIES_WITH_DIFFERENT_RESULTS.contains(sqlStr)) {
            val stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
              ResultSet.CONCUR_READ_ONLY)
            val sparkDf = localSparkSession.sql(sqlStr)
            val postgresResult = getPostgresResult(stmt, sqlStr)
            QueryTest.checkAnswer(sparkDf, postgresResult.toSeq)
          }
        }
        conn.close()
      }
    }
  }

  generateQueriesAndRunTestCases()
}

object GeneratedSubquerySuite {

  // Limit number of generated queries per test so that tests will not take too long.
  private val NUM_QUERIES_PER_TEST = 1000

  // scalastyle:off line.size.limit
  private val KNOWN_QUERIES_WITH_DIFFERENT_RESULTS = Seq(
    // SPARK-46743
    "SELECT outer_table.a, outer_table.b, (SELECT COUNT(null_table.a) AS aggFunctionAlias FROM null_table WHERE null_table.a = outer_table.a) AS subqueryAlias FROM outer_table ORDER BY a DESC NULLS FIRST, b DESC NULLS FIRST, subqueryAlias DESC NULLS FIRST;",
    // SPARK-46743
    "SELECT outer_table.a, outer_table.b, (SELECT COUNT(null_table.a) AS aggFunctionAlias FROM null_table INNER JOIN join_table ON null_table.a = join_table.a WHERE null_table.a = outer_table.a) AS subqueryAlias FROM outer_table ORDER BY a DESC NULLS FIRST, b DESC NULLS FIRST, subqueryAlias DESC NULLS FIRST;",
    // SPARK-46743
    "SELECT outer_table.a, outer_table.b, (SELECT COUNT(null_table.a) AS aggFunctionAlias FROM null_table LEFT OUTER JOIN join_table ON null_table.a = join_table.a WHERE null_table.a = outer_table.a) AS subqueryAlias FROM outer_table ORDER BY a DESC NULLS FIRST, b DESC NULLS FIRST, subqueryAlias DESC NULLS FIRST;",
    // SPARK-46743
    "SELECT outer_table.a, outer_table.b, (SELECT COUNT(null_table.a) AS aggFunctionAlias FROM null_table RIGHT OUTER JOIN join_table ON null_table.a = join_table.a WHERE null_table.a = outer_table.a) AS subqueryAlias FROM outer_table ORDER BY a DESC NULLS FIRST, b DESC NULLS FIRST, subqueryAlias DESC NULLS FIRST;",
    // SPARK-46743
    "SELECT outer_table.a, outer_table.b, (SELECT COUNT(innerSubqueryAlias.a) AS aggFunctionAlias FROM (SELECT null_table.a, null_table.b FROM null_table INTERSECT SELECT join_table.a, join_table.b FROM join_table) AS innerSubqueryAlias WHERE innerSubqueryAlias.a = outer_table.a) AS subqueryAlias FROM outer_table ORDER BY a DESC NULLS FIRST, b DESC NULLS FIRST, subqueryAlias DESC NULLS FIRST;",
    // SPARK-46743
    "SELECT outer_table.a, outer_table.b, (SELECT COUNT(innerSubqueryAlias.a) AS aggFunctionAlias FROM (SELECT null_table.a, null_table.b FROM null_table EXCEPT SELECT join_table.a, join_table.b FROM join_table) AS innerSubqueryAlias WHERE innerSubqueryAlias.a = outer_table.a) AS subqueryAlias FROM outer_table ORDER BY a DESC NULLS FIRST, b DESC NULLS FIRST, subqueryAlias DESC NULLS FIRST;"
  )
  // scalastyle:on line.size.limit
}
