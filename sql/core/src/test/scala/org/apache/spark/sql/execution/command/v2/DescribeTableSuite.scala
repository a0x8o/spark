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

package org.apache.spark.sql.execution.command.v2

import org.apache.spark.sql.{AnalysisException, QueryTest, Row}
import org.apache.spark.sql.connector.catalog.TableCatalog
import org.apache.spark.sql.execution.command
import org.apache.spark.sql.types.StringType
import org.apache.spark.util.Utils

/**
 * The class contains tests for the `DESCRIBE TABLE` command to check V2 table catalogs.
 */
class DescribeTableSuite extends command.DescribeTableSuiteBase with CommandSuiteBase {

  test("Describing a partition is not supported") {
    withNamespaceAndTable("ns", "table") { tbl =>
      spark.sql(s"CREATE TABLE $tbl (id bigint, data string) $defaultUsing " +
        "PARTITIONED BY (id)")
      val e = intercept[AnalysisException] {
        sql(s"DESCRIBE TABLE $tbl PARTITION (id = 1)")
      }
      assert(e.message === "DESCRIBE does not support partition for v2 tables.")
    }
  }

  test("DESCRIBE TABLE of a partitioned table by nested columns") {
    withNamespaceAndTable("ns", "table") { tbl =>
      sql(s"CREATE TABLE $tbl (s struct<id:INT, a:BIGINT>, data string) " +
        s"$defaultUsing PARTITIONED BY (s.id, s.a)")
      val descriptionDf = sql(s"DESCRIBE TABLE $tbl")
      QueryTest.checkAnswer(
        descriptionDf.filter("col_name != 'Created Time'"),
        Seq(
          Row("data", "string", null),
          Row("s", "struct<id:int,a:bigint>", null),
          Row("# Partition Information", "", ""),
          Row("# col_name", "data_type", "comment"),
          Row("s.id", "int", null),
          Row("s.a", "bigint", null)))
    }
  }

  test("DESCRIBE TABLE EXTENDED of a partitioned table") {
    withNamespaceAndTable("ns", "table") { tbl =>
      spark.sql(s"CREATE TABLE $tbl (id bigint, data string) $defaultUsing" +
        " PARTITIONED BY (id)" +
        " TBLPROPERTIES ('bar'='baz')" +
        " COMMENT 'this is a test table'" +
        " LOCATION 'file:/tmp/testcat/table_name'")
      val descriptionDf = spark.sql(s"DESCRIBE TABLE EXTENDED $tbl")
      assert(descriptionDf.schema.map(field => (field.name, field.dataType)) === Seq(
        ("col_name", StringType),
        ("data_type", StringType),
        ("comment", StringType)))
      QueryTest.checkAnswer(
        descriptionDf,
        Seq(
          Row("id", "bigint", null),
          Row("data", "string", null),
          Row("# Partition Information", "", ""),
          Row("# col_name", "data_type", "comment"),
          Row("id", "bigint", null),
          Row("", "", ""),
          Row("# Metadata Columns", "", ""),
          Row("index", "int", "Metadata column used to conflict with a data column"),
          Row("_partition", "string", "Partition key used to store the row"),
          Row("", "", ""),
          Row("# Detailed Table Information", "", ""),
          Row("Name", tbl, ""),
          Row("Type", "MANAGED", ""),
          Row("Comment", "this is a test table", ""),
          Row("Location", "file:/tmp/testcat/table_name", ""),
          Row("Provider", "_", ""),
          Row(TableCatalog.PROP_OWNER.capitalize, Utils.getCurrentUserName(), ""),
          Row("Table Properties", "[bar=baz]", "")))
    }
  }
}
