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

package org.apache.spark.sql.jdbc.v2

import java.sql.Connection

import org.apache.spark.SparkConf
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.analysis.TableAlreadyExistsException
import org.apache.spark.sql.execution.datasources.v2.jdbc.JDBCTableCatalog
import org.apache.spark.sql.jdbc.DatabaseOnDocker
import org.apache.spark.sql.types._
import org.apache.spark.tags.DockerTest

/**
 * To run this test suite for a specific version (e.g., postgres:17.0-alpine)
 * {{{
 *   ENABLE_DOCKER_INTEGRATION_TESTS=1 POSTGRES_DOCKER_IMAGE_NAME=postgres:17.0-alpine
 *     ./build/sbt -Pdocker-integration-tests "testOnly *v2.PostgresIntegrationSuite"
 * }}}
 */
@DockerTest
class PostgresIntegrationSuite extends DockerJDBCIntegrationV2Suite with V2JDBCTest {
  override val catalogName: String = "postgresql"
  override val db = new DatabaseOnDocker {
    override val imageName = sys.env.getOrElse("POSTGRES_DOCKER_IMAGE_NAME", "postgres:17.0-alpine")
    override val env = Map(
      "POSTGRES_PASSWORD" -> "rootpass"
    )
    override val usesIpc = false
    override val jdbcPort = 5432
    override def getJdbcUrl(ip: String, port: Int): String =
      s"jdbc:postgresql://$ip:$port/postgres?user=postgres&password=rootpass"
  }
  override def sparkConf: SparkConf = super.sparkConf
    .set("spark.sql.catalog.postgresql", classOf[JDBCTableCatalog].getName)
    .set("spark.sql.catalog.postgresql.url", db.getJdbcUrl(dockerIp, externalPort))
    .set("spark.sql.catalog.postgresql.pushDownTableSample", "true")
    .set("spark.sql.catalog.postgresql.pushDownLimit", "true")
    .set("spark.sql.catalog.postgresql.pushDownAggregate", "true")
    .set("spark.sql.catalog.postgresql.pushDownOffset", "true")

  override def tablePreparation(connection: Connection): Unit = {
    connection.prepareStatement(
      "CREATE TABLE employee (dept INTEGER, name VARCHAR(32), salary NUMERIC(20, 2)," +
        " bonus double precision)").executeUpdate()
    connection.prepareStatement(
      s"""CREATE TABLE pattern_testing_table (
         |pattern_testing_col VARCHAR(50)
         |)
                   """.stripMargin
    ).executeUpdate()
    connection.prepareStatement(
      "CREATE TABLE datetime (name VARCHAR(32), date1 DATE, time1 TIMESTAMP)")
      .executeUpdate()
  }

  override def dataPreparation(connection: Connection): Unit = {
    super.dataPreparation(connection)
    connection.prepareStatement("INSERT INTO datetime VALUES " +
      "('amy', '2022-05-19', '2022-05-19 00:00:00')").executeUpdate()
    connection.prepareStatement("INSERT INTO datetime VALUES " +
      "('alex', '2022-05-18', '2022-05-18 00:00:00')").executeUpdate()
  }

  override def testUpdateColumnType(tbl: String): Unit = {
    sql(s"CREATE TABLE $tbl (ID INTEGER)")
    var t = spark.table(tbl)
    var expectedSchema = new StructType()
      .add("ID", IntegerType, true, defaultMetadata(IntegerType))
    assert(t.schema === expectedSchema)
    sql(s"ALTER TABLE $tbl ALTER COLUMN id TYPE STRING")
    t = spark.table(tbl)
    expectedSchema = new StructType()
      .add("ID", StringType, true, defaultMetadata())
    assert(t.schema === expectedSchema)
    // Update column type from STRING to INTEGER
    val sql1 = s"ALTER TABLE $tbl ALTER COLUMN id TYPE INTEGER"
    checkError(
      exception = intercept[AnalysisException] {
        sql(sql1)
      },
      condition = "NOT_SUPPORTED_CHANGE_COLUMN",
      parameters = Map(
        "originType" -> "\"STRING\"",
        "newType" -> "\"INT\"",
        "newName" -> "`ID`",
        "originName" -> "`ID`",
        "table" -> s"`$catalogName`.`alt_table`"),
      context = ExpectedContext(fragment = sql1, start = 0, stop = 60)
    )
  }

  override def testCreateTableWithProperty(tbl: String): Unit = {
    sql(s"CREATE TABLE $tbl (ID INT)" +
      s" TBLPROPERTIES('TABLESPACE'='pg_default')")
    val t = spark.table(tbl)
    val expectedSchema = new StructType()
      .add("ID", IntegerType, true, defaultMetadata(IntegerType))
    assert(t.schema === expectedSchema)
  }

  override def supportsTableSample: Boolean = true

  override def supportsIndex: Boolean = true

  override def indexOptions: String = "FILLFACTOR=70"

  test("SPARK-42964: SQLState: 42P07 - duplicated table") {
    val t1 = s"$catalogName.t1"
    val t2 = s"$catalogName.t2"
    withTable(t1, t2) {
      sql(s"CREATE TABLE $t1(c int)")
      sql(s"CREATE TABLE $t2(c int)")
      checkError(
        exception = intercept[TableAlreadyExistsException](sql(s"ALTER TABLE $t1 RENAME TO t2")),
        condition = "TABLE_OR_VIEW_ALREADY_EXISTS",
        parameters = Map("relationName" -> "`t2`")
      )
    }
  }

  override def testDatetime(tbl: String): Unit = {
    val df1 = sql(s"SELECT name FROM $tbl WHERE " +
      "dayofyear(date1) > 100 AND dayofmonth(date1) > 10 ")
    checkFilterPushed(df1)
    val rows1 = df1.collect()
    assert(rows1.length === 2)
    assert(rows1(0).getString(0) === "amy")
    assert(rows1(1).getString(0) === "alex")

    val df2 = sql(s"SELECT name FROM $tbl WHERE year(date1) = 2022 AND quarter(date1) = 2")
    checkFilterPushed(df2)
    val rows2 = df2.collect()
    assert(rows2.length === 2)
    assert(rows2(0).getString(0) === "amy")
    assert(rows2(1).getString(0) === "alex")

    val df3 = sql(s"SELECT name FROM $tbl WHERE second(time1) = 0 AND month(date1) = 5")
    checkFilterPushed(df3)
    val rows3 = df3.collect()
    assert(rows3.length === 2)
    assert(rows3(0).getString(0) === "amy")
    assert(rows3(1).getString(0) === "alex")

    val df4 = sql(s"SELECT name FROM $tbl WHERE hour(time1) = 0 AND minute(time1) = 0")
    checkFilterPushed(df4)
    val rows4 = df4.collect()
    assert(rows4.length === 2)
    assert(rows4(0).getString(0) === "amy")
    assert(rows4(1).getString(0) === "alex")

    val df5 = sql(s"SELECT name FROM $tbl WHERE " +
      "extract(WEEk from date1) > 10 AND extract(YEAROFWEEK from date1) = 2022")
    checkFilterPushed(df5)
    val rows5 = df5.collect()
    assert(rows5.length === 2)
    assert(rows5(0).getString(0) === "amy")
    assert(rows5(1).getString(0) === "alex")

    val df6 = sql(s"SELECT name FROM $tbl WHERE date_add(date1, 1) = date'2022-05-20' " +
      "AND datediff(date1, '2022-05-10') > 0")
    checkFilterPushed(df6, false)
    val rows6 = df6.collect()
    assert(rows6.length === 1)
    assert(rows6(0).getString(0) === "amy")

    val df7 = sql(s"SELECT name FROM $tbl WHERE weekday(date1) = 2")
    checkFilterPushed(df7)
    val rows7 = df7.collect()
    assert(rows7.length === 1)
    assert(rows7(0).getString(0) === "alex")

    val df8 = sql(s"SELECT name FROM $tbl WHERE dayofweek(date1) = 4")
    checkFilterPushed(df8)
    val rows8 = df8.collect()
    assert(rows8.length === 1)
    assert(rows8(0).getString(0) === "alex")

    val df9 = sql(s"SELECT name FROM $tbl WHERE " +
      "dayofyear(date1) > 100 order by dayofyear(date1) limit 1")
    checkFilterPushed(df9)
    val rows9 = df9.collect()
    assert(rows9.length === 1)
    assert(rows9(0).getString(0) === "alex")

    // Postgres does not support
    val df10 = sql(s"SELECT name FROM $tbl WHERE trunc(date1, 'week') = date'2022-05-16'")
    checkFilterPushed(df10, false)
    val rows10 = df10.collect()
    assert(rows10.length === 2)
    assert(rows10(0).getString(0) === "amy")
    assert(rows10(1).getString(0) === "alex")
  }
}
