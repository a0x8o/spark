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

package org.apache.spark.sql.connector.catalog

import java.util

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.{NoSuchPartitionException, PartitionsAlreadyExistException}
import org.apache.spark.sql.connector.{BufferedRows, InMemoryAtomicPartitionTable, InMemoryTableCatalog}
import org.apache.spark.sql.connector.expressions.{LogicalExpressions, NamedReference}
import org.apache.spark.sql.types.{IntegerType, StringType, StructType}
import org.apache.spark.sql.util.CaseInsensitiveStringMap

class SupportsAtomicPartitionManagementSuite extends SparkFunSuite {

  private val ident: Identifier = Identifier.of(Array("ns"), "test_table")

  def ref(name: String): NamedReference = LogicalExpressions.parseReference(name)

  private val catalog: InMemoryTableCatalog = {
    val newCatalog = new InMemoryTableCatalog
    newCatalog.initialize("test", CaseInsensitiveStringMap.empty())
    newCatalog.createTable(
      ident,
      new StructType()
        .add("id", IntegerType)
        .add("data", StringType)
        .add("dt", StringType),
      Array(LogicalExpressions.identity(ref("dt"))),
      util.Collections.emptyMap[String, String])
    newCatalog
  }

  private def hasPartitions(table: SupportsPartitionManagement): Boolean = {
    !table.listPartitionIdentifiers(Array.empty, InternalRow.empty).isEmpty
  }

  test("createPartitions") {
    val table = catalog.loadTable(ident)
    val partTable = new InMemoryAtomicPartitionTable(
      table.name(), table.schema(), table.partitioning(), table.properties())
    assert(!hasPartitions(partTable))

    val partIdents = Array(InternalRow.apply("3"), InternalRow.apply("4"))
    partTable.createPartitions(
      partIdents,
      Array(new util.HashMap[String, String](), new util.HashMap[String, String]()))
    assert(hasPartitions(partTable))
    assert(partTable.partitionExists(InternalRow.apply("3")))
    assert(partTable.partitionExists(InternalRow.apply("4")))

    partTable.dropPartition(InternalRow.apply("3"))
    partTable.dropPartition(InternalRow.apply("4"))
    assert(!hasPartitions(partTable))
  }

  test("createPartitions failed if partition already exists") {
    val table = catalog.loadTable(ident)
    val partTable = new InMemoryAtomicPartitionTable(
      table.name(), table.schema(), table.partitioning(), table.properties())
    assert(!hasPartitions(partTable))

    val partIdent = InternalRow.apply("4")
    partTable.createPartition(partIdent, new util.HashMap[String, String]())
    assert(hasPartitions(partTable))
    assert(partTable.partitionExists(partIdent))

    val partIdents = Array(InternalRow.apply("3"), InternalRow.apply("4"))
    assertThrows[PartitionsAlreadyExistException](
      partTable.createPartitions(
        partIdents,
        Array(new util.HashMap[String, String](), new util.HashMap[String, String]())))
    assert(!partTable.partitionExists(InternalRow.apply("3")))

    partTable.dropPartition(partIdent)
    assert(!hasPartitions(partTable))
  }

  test("dropPartitions") {
    val table = catalog.loadTable(ident)
    val partTable = new InMemoryAtomicPartitionTable(
      table.name(), table.schema(), table.partitioning(), table.properties())
    assert(!hasPartitions(partTable))

    val partIdents = Array(InternalRow.apply("3"), InternalRow.apply("4"))
    partTable.createPartitions(
      partIdents,
      Array(new util.HashMap[String, String](), new util.HashMap[String, String]()))
    assert(hasPartitions(partTable))
    assert(partTable.partitionExists(InternalRow.apply("3")))
    assert(partTable.partitionExists(InternalRow.apply("4")))

    partTable.dropPartitions(partIdents)
    assert(!hasPartitions(partTable))
  }

  test("purgePartitions") {
    val table = catalog.loadTable(ident)
    val partTable = new InMemoryAtomicPartitionTable(
      table.name(), table.schema(), table.partitioning(), table.properties())
    val partIdents = Array(InternalRow.apply("3"), InternalRow.apply("4"))
    partTable.createPartitions(
      partIdents,
      Array(new util.HashMap[String, String](), new util.HashMap[String, String]()))
    val errMsg = intercept[UnsupportedOperationException] {
      partTable.purgePartitions(partIdents)
    }.getMessage
    assert(errMsg.contains("purge is not supported"))
  }

  test("dropPartitions failed if partition not exists") {
    val table = catalog.loadTable(ident)
    val partTable = new InMemoryAtomicPartitionTable(
      table.name(), table.schema(), table.partitioning(), table.properties())
    assert(!hasPartitions(partTable))

    val partIdent = InternalRow.apply("4")
    partTable.createPartition(partIdent, new util.HashMap[String, String]())
    assert(partTable.listPartitionIdentifiers(Array.empty, InternalRow.empty).length == 1)

    val partIdents = Array(InternalRow.apply("3"), InternalRow.apply("4"))
    assert(!partTable.dropPartitions(partIdents))
    assert(partTable.partitionExists(partIdent))

    partTable.dropPartition(partIdent)
    assert(!hasPartitions(partTable))
  }

  test("truncatePartitions") {
    val table = catalog.loadTable(ident)
    val partTable = new InMemoryAtomicPartitionTable(
      table.name(), table.schema(), table.partitioning(), table.properties())
    assert(!hasPartitions(partTable))

    partTable.createPartitions(
      Array(InternalRow("3"), InternalRow("4"), InternalRow("5")),
      Array.tabulate(3)(_ => new util.HashMap[String, String]()))
    assert(partTable.listPartitionIdentifiers(Array.empty, InternalRow.empty).length == 3)

    partTable.withData(Array(
      new BufferedRows("3").withRow(InternalRow(0, "abc", "3")),
      new BufferedRows("4").withRow(InternalRow(1, "def", "4")),
      new BufferedRows("5").withRow(InternalRow(2, "zyx", "5"))
    ))

    partTable.truncatePartitions(Array(InternalRow("3"), InternalRow("4")))
    assert(partTable.listPartitionIdentifiers(Array.empty, InternalRow.empty).length == 3)
    assert(partTable.rows === InternalRow(2, "zyx", "5") :: Nil)

    // Truncate non-existing partition
    val errMsg = intercept[NoSuchPartitionException] {
      partTable.truncatePartitions(Array(InternalRow("5"), InternalRow("6")))
    }.getMessage
    assert(errMsg.contains("Partition not found in table test.ns.test_table: 6 -> dt"))
    assert(partTable.rows === InternalRow(2, "zyx", "5") :: Nil)
  }
}
