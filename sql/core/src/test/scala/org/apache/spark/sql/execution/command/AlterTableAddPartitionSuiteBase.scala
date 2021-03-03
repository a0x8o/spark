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

import org.apache.spark.sql.{AnalysisException, QueryTest}
import org.apache.spark.sql.catalyst.analysis.PartitionsAlreadyExistException
import org.apache.spark.sql.internal.SQLConf

/**
 * This base suite contains unified tests for the `ALTER TABLE .. ADD PARTITION` command that
 * check V1 and V2 table catalogs. The tests that cannot run for all supported catalogs are
 * located in more specific test suites:
 *
 *   - V2 table catalog tests:
 *     `org.apache.spark.sql.execution.command.v2.AlterTableAddPartitionSuite`
 *   - V1 table catalog tests:
 *     `org.apache.spark.sql.execution.command.v1.AlterTableAddPartitionSuiteBase`
 *     - V1 In-Memory catalog:
 *       `org.apache.spark.sql.execution.command.v1.AlterTableAddPartitionSuite`
 *     - V1 Hive External catalog:
 *       `org.apache.spark.sql.hive.execution.command.AlterTableAddPartitionSuite`
 */
trait AlterTableAddPartitionSuiteBase extends QueryTest with DDLCommandTestUtils {
  override val command = "ALTER TABLE .. ADD PARTITION"

  test("one partition") {
    withNamespaceAndTable("ns", "tbl") { t =>
      sql(s"CREATE TABLE $t (id bigint, data string) $defaultUsing PARTITIONED BY (id)")
      Seq("", "IF NOT EXISTS").foreach { exists =>
        sql(s"ALTER TABLE $t ADD $exists PARTITION (id=1) LOCATION 'loc'")

        checkPartitions(t, Map("id" -> "1"))
        checkLocation(t, Map("id" -> "1"), "loc")
      }
    }
  }

  test("multiple partitions") {
    withNamespaceAndTable("ns", "tbl") { t =>
      sql(s"CREATE TABLE $t (id bigint, data string) $defaultUsing PARTITIONED BY (id)")
      Seq("", "IF NOT EXISTS").foreach { exists =>
        sql(s"""
          |ALTER TABLE $t ADD $exists
          |PARTITION (id=1) LOCATION 'loc'
          |PARTITION (id=2) LOCATION 'loc1'""".stripMargin)

        checkPartitions(t, Map("id" -> "1"), Map("id" -> "2"))
        checkLocation(t, Map("id" -> "1"), "loc")
        checkLocation(t, Map("id" -> "2"), "loc1")
      }
    }
  }

  test("multi-part partition") {
    withNamespaceAndTable("ns", "tbl") { t =>
      sql(s"CREATE TABLE $t (id bigint, a int, b string) $defaultUsing PARTITIONED BY (a, b)")
      Seq("", "IF NOT EXISTS").foreach { exists =>
        sql(s"ALTER TABLE $t ADD $exists PARTITION (a=2, b='abc')")

        checkPartitions(t, Map("a" -> "2", "b" -> "abc"))
      }
    }
  }

  test("table to alter does not exist") {
    withNamespaceAndTable("ns", "does_not_exist") { t =>
      val errMsg = intercept[AnalysisException] {
        sql(s"ALTER TABLE $t ADD IF NOT EXISTS PARTITION (a='4', b='9')")
      }.getMessage
      assert(errMsg.contains("Table not found"))
    }
  }

  test("case sensitivity in resolving partition specs") {
    withNamespaceAndTable("ns", "tbl") { t =>
      spark.sql(s"CREATE TABLE $t (id bigint, data string) $defaultUsing PARTITIONED BY (id)")
      withSQLConf(SQLConf.CASE_SENSITIVE.key -> "true") {
        val errMsg = intercept[AnalysisException] {
          spark.sql(s"ALTER TABLE $t ADD PARTITION (ID=1) LOCATION 'loc1'")
        }.getMessage
        assert(errMsg.contains("ID is not a valid partition column"))
      }
      withSQLConf(SQLConf.CASE_SENSITIVE.key -> "false") {
        spark.sql(s"ALTER TABLE $t ADD PARTITION (ID=1) LOCATION 'loc1'")
        checkPartitions(t, Map("id" -> "1"))
        checkLocation(t, Map("id" -> "1"), "loc1")
      }
    }
  }

  test("SPARK-33521: universal type conversions of partition values") {
    withNamespaceAndTable("ns", "tbl") { t =>
      sql(s"""
        |CREATE TABLE $t (
        |  id int,
        |  part0 tinyint,
        |  part1 smallint,
        |  part2 int,
        |  part3 bigint,
        |  part4 float,
        |  part5 double,
        |  part6 string,
        |  part7 boolean,
        |  part8 date,
        |  part9 timestamp
        |) $defaultUsing
        |PARTITIONED BY (part0, part1, part2, part3, part4, part5, part6, part7, part8, part9)
        |""".stripMargin)
      val partSpec = """
        |  part0 = -1,
        |  part1 = 0,
        |  part2 = 1,
        |  part3 = 2,
        |  part4 = 3.14,
        |  part5 = 3.14,
        |  part6 = 'abc',
        |  part7 = true,
        |  part8 = '2020-11-23',
        |  part9 = '2020-11-23 22:13:10.123456'
        |""".stripMargin
      sql(s"ALTER TABLE $t ADD PARTITION ($partSpec)")
      val expected = Map(
        "part0" -> "-1",
        "part1" -> "0",
        "part2" -> "1",
        "part3" -> "2",
        "part4" -> "3.14",
        "part5" -> "3.14",
        "part6" -> "abc",
        "part7" -> "true",
        "part8" -> "2020-11-23",
        "part9" -> "2020-11-23 22:13:10.123456")
      checkPartitions(t, expected)
      sql(s"ALTER TABLE $t DROP PARTITION ($partSpec)")
      checkPartitions(t) // no partitions
    }
  }

  test("SPARK-33676: not fully specified partition spec") {
    withNamespaceAndTable("ns", "tbl") { t =>
      sql(s"""
        |CREATE TABLE $t (id bigint, part0 int, part1 string)
        |$defaultUsing
        |PARTITIONED BY (part0, part1)""".stripMargin)
      val errMsg = intercept[AnalysisException] {
        sql(s"ALTER TABLE $t ADD PARTITION (part0 = 1)")
      }.getMessage
      assert(errMsg.contains("Partition spec is invalid. " +
        "The spec (part0) must match the partition spec (part0, part1)"))
    }
  }

  test("partition already exists") {
    withNamespaceAndTable("ns", "tbl") { t =>
      sql(s"CREATE TABLE $t (id bigint, data string) $defaultUsing PARTITIONED BY (id)")
      sql(s"ALTER TABLE $t ADD PARTITION (id=2) LOCATION 'loc1'")

      val errMsg = intercept[PartitionsAlreadyExistException] {
        sql(s"ALTER TABLE $t ADD PARTITION (id=1) LOCATION 'loc'" +
          " PARTITION (id=2) LOCATION 'loc1'")
      }.getMessage
      assert(errMsg.contains("The following partitions already exists"))

      sql(s"ALTER TABLE $t ADD IF NOT EXISTS PARTITION (id=1) LOCATION 'loc'" +
        " PARTITION (id=2) LOCATION 'loc1'")
      checkPartitions(t, Map("id" -> "1"), Map("id" -> "2"))
    }
  }

  test("SPARK-33474: Support typed literals as partition spec values") {
    withNamespaceAndTable("ns", "tbl") { t =>
      sql(s"CREATE TABLE $t(name STRING, part DATE) USING PARQUET PARTITIONED BY (part)")
      sql(s"ALTER TABLE $t ADD PARTITION(part = date'2020-01-01')")
      checkPartitions(t, Map("part" ->"2020-01-01"))
    }
  }
}
