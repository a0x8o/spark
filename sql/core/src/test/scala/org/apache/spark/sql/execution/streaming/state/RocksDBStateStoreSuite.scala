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

package org.apache.spark.sql.execution.streaming.state

import java.util.UUID

import scala.util.Random

import org.apache.hadoop.conf.Configuration
import org.scalatest.BeforeAndAfter

import org.apache.spark.{SparkConf, SparkUnsupportedOperationException}
import org.apache.spark.io.CompressionCodec
import org.apache.spark.sql.LocalSparkSession.withSparkSession
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.{GenericInternalRow, UnsafeProjection}
import org.apache.spark.sql.catalyst.util.quietly
import org.apache.spark.sql.execution.streaming.StatefulOperatorStateInfo
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._
import org.apache.spark.tags.ExtendedSQLTest
import org.apache.spark.unsafe.Platform
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.util.Utils

@ExtendedSQLTest
class RocksDBStateStoreSuite extends StateStoreSuiteBase[RocksDBStateStoreProvider]
  with AlsoTestWithChangelogCheckpointingEnabled
  with SharedSparkSession
  with BeforeAndAfter {

  before {
    StateStore.stop()
    require(!StateStore.isMaintenanceRunning)
  }

  after {
    StateStore.stop()
    require(!StateStore.isMaintenanceRunning)
  }

  import StateStoreTestsHelper._

  testWithColumnFamilies(s"version encoding",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    import RocksDBStateStoreProvider._

    tryWithProviderResource(newStoreProvider(colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)
      val keyRow = dataToKeyRow("a", 0)
      val valueRow = dataToValueRow(1)
      store.put(keyRow, valueRow)
      val iter = provider.rocksDB.iterator()
      assert(iter.hasNext)
      val kv = iter.next()

      // Verify the version encoded in first byte of the key and value byte arrays
      assert(Platform.getByte(kv.key, Platform.BYTE_ARRAY_OFFSET) === STATE_ENCODING_VERSION)
      assert(Platform.getByte(kv.value, Platform.BYTE_ARRAY_OFFSET) === STATE_ENCODING_VERSION)
    }
  }

  test("RocksDB confs are passed correctly from SparkSession to db instance") {
    val sparkConf = new SparkConf().setMaster("local").setAppName(this.getClass.getSimpleName)
    withSparkSession(SparkSession.builder().config(sparkConf).getOrCreate()) { spark =>
      // Set the session confs that should be passed into RocksDB
      val testConfs = Seq(
        ("spark.sql.streaming.stateStore.providerClass",
          classOf[RocksDBStateStoreProvider].getName),
        (RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + ".compactOnCommit", "true"),
        (RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + ".changelogCheckpointing.enabled", "true"),
        (RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + ".lockAcquireTimeoutMs", "10"),
        (RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + ".maxOpenFiles", "1000"),
        (RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + ".maxWriteBufferNumber", "3"),
        (RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + ".writeBufferSizeMB", "16"),
        (RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + ".allowFAllocate", "false"),
        (RocksDBConf.ROCKSDB_SQL_CONF_NAME_PREFIX + ".compression", "zstd"),
        (SQLConf.STATE_STORE_ROCKSDB_FORMAT_VERSION.key, "4")
      )
      testConfs.foreach { case (k, v) => spark.conf.set(k, v) }

      // Prepare test objects for running task on state store
      val testRDD = spark.sparkContext.makeRDD[String](Seq("a"), 1)
      val testSchema = StructType(Seq(StructField("key", StringType, true)))
      val testStateInfo = StatefulOperatorStateInfo(
        checkpointLocation = Utils.createTempDir().getAbsolutePath,
        queryRunId = UUID.randomUUID, operatorId = 0, storeVersion = 0, numPartitions = 5)

      // Create state store in a task and get the RocksDBConf from the instantiated RocksDB instance
      val rocksDBConfInTask: RocksDBConf = testRDD.mapPartitionsWithStateStore[RocksDBConf](
        spark.sqlContext, testStateInfo, testSchema, testSchema,
        NoPrefixKeyStateEncoderSpec(testSchema)) {
          (store: StateStore, _: Iterator[String]) =>
            // Use reflection to get RocksDB instance
            val dbInstanceMethod =
              store.getClass.getMethods.filter(_.getName.contains("dbInstance")).head
            Iterator(dbInstanceMethod.invoke(store).asInstanceOf[RocksDB].conf)
        }.collect().head

      // Verify the confs are same as those configured in the session conf
      assert(rocksDBConfInTask.compactOnCommit == true)
      assert(rocksDBConfInTask.enableChangelogCheckpointing == true)
      assert(rocksDBConfInTask.lockAcquireTimeoutMs == 10L)
      assert(rocksDBConfInTask.formatVersion == 4)
      assert(rocksDBConfInTask.maxOpenFiles == 1000)
      assert(rocksDBConfInTask.maxWriteBufferNumber == 3)
      assert(rocksDBConfInTask.writeBufferSizeMB == 16L)
      assert(rocksDBConfInTask.allowFAllocate == false)
      assert(rocksDBConfInTask.compression == "zstd")
    }
  }

  testWithColumnFamilies("rocksdb file manager metrics exposed",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    import RocksDBStateStoreProvider._
    def getCustomMetric(metrics: StateStoreMetrics,
      customMetric: StateStoreCustomMetric): Long = {
      val metricPair = metrics.customMetrics.find(_._1.name == customMetric.name)
      assert(metricPair.isDefined)
      metricPair.get._2
    }

    withSQLConf(SQLConf.STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT.key -> "1") {
      tryWithProviderResource(newStoreProvider(colFamiliesEnabled)) { provider =>
        val store = provider.getStore(0)
        // Verify state after updating
        put(store, "a", 0, 1)
        assert(get(store, "a", 0) === Some(1))
        assert(store.commit() === 1)
        provider.doMaintenance()
        assert(store.hasCommitted)
        val storeMetrics = store.metrics
        assert(storeMetrics.numKeys === 1)
        // SPARK-46249 - In the case of changelog checkpointing, the snapshot upload happens in
        // the context of the background maintenance thread. The file manager metrics are updated
        // here and will be available as part of the next metrics update. So we cannot rely on
        // the file manager metrics to be available here for this version.
        if (!isChangelogCheckpointingEnabled) {
          assert(getCustomMetric(storeMetrics, CUSTOM_METRIC_FILES_COPIED) > 0L)
          assert(getCustomMetric(storeMetrics, CUSTOM_METRIC_FILES_REUSED) == 0L)
          assert(getCustomMetric(storeMetrics, CUSTOM_METRIC_BYTES_COPIED) > 0L)
          assert(getCustomMetric(storeMetrics, CUSTOM_METRIC_ZIP_FILE_BYTES_UNCOMPRESSED) > 0L)
        }
      }
    }
  }

  testWithColumnFamilies("rocksdb range scan validation - invalid num columns",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    // zero ordering cols
    val ex1 = intercept[SparkUnsupportedOperationException] {
      tryWithProviderResource(newStoreProvider(keySchemaWithRangeScan,
        RangeKeyScanStateEncoderSpec(keySchemaWithRangeScan, 0),
        colFamiliesEnabled)) { provider =>
        provider.getStore(0)
      }
    }
    checkError(
      ex1,
      errorClass = "STATE_STORE_INCORRECT_NUM_ORDERING_COLS_FOR_RANGE_SCAN",
      parameters = Map(
        "numOrderingCols" -> "0"
      ),
      matchPVals = true
    )

    // ordering cols greater than schema cols
    val ex2 = intercept[SparkUnsupportedOperationException] {
      tryWithProviderResource(newStoreProvider(keySchemaWithRangeScan,
        RangeKeyScanStateEncoderSpec(keySchemaWithRangeScan, keySchemaWithRangeScan.length + 1),
        colFamiliesEnabled)) { provider =>
        provider.getStore(0)
      }
    }
    checkError(
      ex2,
      errorClass = "STATE_STORE_INCORRECT_NUM_ORDERING_COLS_FOR_RANGE_SCAN",
      parameters = Map(
        "numOrderingCols" -> (keySchemaWithRangeScan.length + 1).toString
      ),
      matchPVals = true
    )
  }

  testWithColumnFamilies("rocksdb range scan validation - variable sized columns",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    val keySchemaWithVariableSizeCols: StructType = StructType(
      Seq(StructField("key1", StringType, false), StructField("key2", StringType, false)))

    val ex = intercept[SparkUnsupportedOperationException] {
      tryWithProviderResource(newStoreProvider(keySchemaWithVariableSizeCols,
        RangeKeyScanStateEncoderSpec(keySchemaWithVariableSizeCols, 1),
        colFamiliesEnabled)) { provider =>
        provider.getStore(0)
      }
    }
    checkError(
      ex,
      errorClass = "STATE_STORE_VARIABLE_SIZE_ORDERING_COLS_NOT_SUPPORTED",
      parameters = Map(
        "fieldName" -> keySchemaWithVariableSizeCols.fields(0).name,
        "index" -> "0"
      ),
      matchPVals = true
    )
  }

  testWithColumnFamilies("rocksdb range scan validation - null type columns",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    val keySchemaWithNullTypeCols: StructType = StructType(
      Seq(StructField("key1", NullType, false), StructField("key2", StringType, false)))

    val ex = intercept[SparkUnsupportedOperationException] {
      tryWithProviderResource(newStoreProvider(keySchemaWithNullTypeCols,
        RangeKeyScanStateEncoderSpec(keySchemaWithNullTypeCols, 1),
        colFamiliesEnabled)) { provider =>
        provider.getStore(0)
      }
    }
    checkError(
      ex,
      errorClass = "STATE_STORE_NULL_TYPE_ORDERING_COLS_NOT_SUPPORTED",
      parameters = Map(
        "fieldName" -> keySchemaWithNullTypeCols.fields(0).name,
        "index" -> "0"
      ),
      matchPVals = true
    )
  }

  testWithColumnFamilies("rocksdb range scan - fixed size non-ordering columns",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    tryWithProviderResource(newStoreProvider(keySchemaWithRangeScan,
      RangeKeyScanStateEncoderSpec(keySchemaWithRangeScan, 1), colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      // use non-default col family if column families are enabled
      val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(cfName,
          keySchemaWithRangeScan, valueSchema,
          RangeKeyScanStateEncoderSpec(keySchemaWithRangeScan, 1))
      }

      val timerTimestamps = Seq(931L, 8000L, 452300L, 4200L, -1L, 90L, 1L, 2L, 8L,
        -230L, -14569L, -92L, -7434253L, 35L, 6L, 9L, -323L, 5L)
      timerTimestamps.foreach { ts =>
        // non-timestamp col is of fixed size
        val keyRow = dataToKeyRowWithRangeScan(ts, "a")
        val valueRow = dataToValueRow(1)
        store.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store.get(keyRow, cfName)) === 1)
      }

      val result = store.iterator(cfName).map { kv =>
        val key = keyRowWithRangeScanToData(kv.key)
        key._1
      }.toSeq
      assert(result === timerTimestamps.sorted)
      store.commit()

      // test with a different set of power of 2 timestamps
      val store1 = provider.getStore(1)
      val timerTimestamps1 = Seq(-32L, -64L, -256L, 64L, 32L, 1024L, 4096L, 0L)
      timerTimestamps1.foreach { ts =>
        // non-timestamp col is of fixed size
        val keyRow = dataToKeyRowWithRangeScan(ts, "a")
        val valueRow = dataToValueRow(1)
        store1.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store1.get(keyRow, cfName)) === 1)
      }

      val result1 = store1.iterator(cfName).map { kv =>
        val key = keyRowWithRangeScanToData(kv.key)
        key._1
      }.toSeq
      assert(result1 === (timerTimestamps ++ timerTimestamps1).sorted)
    }
  }

  testWithColumnFamilies("rocksdb range scan - variable size non-ordering columns with " +
    "double type values are supported",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    val testSchema: StructType = StructType(
      Seq(StructField("key1", DoubleType, false),
        StructField("key2", StringType, false)))

    val schemaProj = UnsafeProjection.create(Array[DataType](DoubleType, StringType))
    tryWithProviderResource(newStoreProvider(testSchema,
      RangeKeyScanStateEncoderSpec(testSchema, 1), colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(cfName,
          testSchema, valueSchema,
          RangeKeyScanStateEncoderSpec(testSchema, 1))
      }

      // Verify that the sort ordering here is as follows:
      // -NaN, -Infinity, -ve values, -0, 0, +0, +ve values, +Infinity, +NaN
      val timerTimestamps: Seq[Double] = Seq(6894.32, 345.2795, -23.24, 24.466,
        7860.0, 4535.55, 423.42, -5350.355, 0.0, 0.001, 0.233, -53.255, -66.356, -244.452,
        96456466.3536677, 14421434453.43524562, Double.NaN, Double.PositiveInfinity,
        Double.NegativeInfinity, -Double.NaN, +0.0, -0.0,
        // A different representation of NaN than the Java constants
        java.lang.Double.longBitsToDouble(0x7ff80abcdef54321L),
        java.lang.Double.longBitsToDouble(0xfff80abcdef54321L))
      timerTimestamps.foreach { ts =>
        // non-timestamp col is of variable size
        val keyRow = schemaProj.apply(new GenericInternalRow(Array[Any](ts,
          UTF8String.fromString(Random.alphanumeric.take(Random.nextInt(20) + 1).mkString))))
        val valueRow = dataToValueRow(1)
        store.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store.get(keyRow, cfName)) === 1)
      }

      // We expect to find NaNs at the beginning and end of the sorted list
      var nanIndexSet = Set(0, 1, timerTimestamps.size - 2, timerTimestamps.size - 1)
      val result = store.iterator(cfName).zipWithIndex.map { case (kv, idx) =>
        val keyRow = kv.key
        val key = (keyRow.getDouble(0), keyRow.getString(1))
        if (key._1.isNaN) {
          assert(nanIndexSet.contains(idx))
          nanIndexSet -= idx
        }
        key._1
      }.toSeq

      assert(nanIndexSet.isEmpty)
      assert(result.filter(!_.isNaN) === timerTimestamps.sorted.filter(!_.isNaN))
      store.commit()
    }
  }

  testWithColumnFamilies("rocksdb range scan - variable size non-ordering columns",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    tryWithProviderResource(newStoreProvider(keySchemaWithRangeScan,
      RangeKeyScanStateEncoderSpec(keySchemaWithRangeScan, 1), colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(cfName,
          keySchemaWithRangeScan, valueSchema,
          RangeKeyScanStateEncoderSpec(keySchemaWithRangeScan, 1))
      }

      val timerTimestamps = Seq(931L, 8000L, 452300L, 4200L, 90L, 1L, 2L, 8L, 3L, 35L, 6L, 9L, 5L,
        -24L, -999L, -2L, -61L, -9808344L, -1020L)
      timerTimestamps.foreach { ts =>
        // non-timestamp col is of variable size
        val keyRow = dataToKeyRowWithRangeScan(ts,
          Random.alphanumeric.take(Random.nextInt(20) + 1).mkString)
        val valueRow = dataToValueRow(1)
        store.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store.get(keyRow, cfName)) === 1)
      }

      val result = store.iterator(cfName).map { kv =>
        val key = keyRowWithRangeScanToData(kv.key)
        key._1
      }.toSeq
      assert(result === timerTimestamps.sorted)
      store.commit()

      // test with a different set of power of 2 timestamps
      val store1 = provider.getStore(1)
      val timerTimestamps1 = Seq(64L, 32L, 1024L, 4096L, 0L, -512L, -8192L, -16L)
      timerTimestamps1.foreach { ts =>
        // non-timestamp col is of fixed size
        val keyRow = dataToKeyRowWithRangeScan(ts,
          Random.alphanumeric.take(Random.nextInt(20) + 1).mkString)
        val valueRow = dataToValueRow(1)
        store1.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store1.get(keyRow, cfName)) === 1)
      }

      val result1 = store1.iterator(cfName).map { kv =>
        val key = keyRowWithRangeScanToData(kv.key)
        key._1
      }.toSeq
      assert(result1 === (timerTimestamps ++ timerTimestamps1).sorted)
    }
  }

  testWithColumnFamilies("rocksdb range scan multiple ordering columns - variable size " +
    s"non-ordering columns",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    val testSchema: StructType = StructType(
      Seq(StructField("key1", LongType, false),
        StructField("key2", IntegerType, false),
        StructField("key3", StringType, false)))

    val schemaProj = UnsafeProjection.create(Array[DataType](LongType, IntegerType, StringType))

    tryWithProviderResource(newStoreProvider(testSchema,
      RangeKeyScanStateEncoderSpec(testSchema, 2), colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(cfName,
          testSchema, valueSchema,
          RangeKeyScanStateEncoderSpec(testSchema, 2))
      }

      val timerTimestamps = Seq((931L, 10), (8000L, 40), (452300L, 1), (4200L, 68), (90L, 2000),
        (1L, 27), (1L, 394), (1L, 5), (3L, 980),
        (-1L, 232), (-1L, 3455), (-6109L, 921455), (-9808344L, 1), (-1020L, 2),
        (35L, 2112), (6L, 90118), (9L, 95118), (6L, 87210), (-4344L, 2323), (-3122L, 323))
      timerTimestamps.foreach { ts =>
        // order by long col first and then by int col
        val keyRow = schemaProj.apply(new GenericInternalRow(Array[Any](ts._1, ts._2,
          UTF8String.fromString(Random.alphanumeric.take(Random.nextInt(20) + 1).mkString))))
        val valueRow = dataToValueRow(1)
        store.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store.get(keyRow, cfName)) === 1)
      }

      val result = store.iterator(cfName).map { kv =>
        val keyRow = kv.key
        val key = (keyRow.getLong(0), keyRow.getInt(1), keyRow.getString(2))
        (key._1, key._2)
      }.toSeq
      assert(result === timerTimestamps.sorted)
    }
  }

  testWithColumnFamilies("rocksdb range scan multiple ordering columns - variable size " +
    s"non-ordering columns with null values in first ordering column",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    val testSchema: StructType = StructType(
      Seq(StructField("key1", LongType, true),
        StructField("key2", IntegerType, true),
        StructField("key3", StringType, false)))

    val schemaProj = UnsafeProjection.create(Array[DataType](LongType, IntegerType, StringType))

    tryWithProviderResource(newStoreProvider(testSchema,
      RangeKeyScanStateEncoderSpec(testSchema, 2), colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(cfName,
          testSchema, valueSchema,
          RangeKeyScanStateEncoderSpec(testSchema, 2))
      }

      val timerTimestamps = Seq((931L, 10), (null, 40), (452300L, 1),
        (4200L, 68), (90L, 2000), (1L, 27), (1L, 394), (1L, 5), (3L, 980), (35L, 2112),
        (6L, 90118), (9L, 95118), (6L, 87210), (null, 113), (null, 28), (null, -23), (null, -5534),
        (-67450L, 2434), (-803L, 3422))
      timerTimestamps.foreach { ts =>
        // order by long col first and then by int col
        val keyRow = schemaProj.apply(new GenericInternalRow(Array[Any](ts._1, ts._2,
          UTF8String.fromString(Random.alphanumeric.take(Random.nextInt(20) + 1).mkString))))
        val valueRow = dataToValueRow(1)
        store.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store.get(keyRow, cfName)) === 1)
      }

      // verify that the expected null cols are seen
      val nullRows = store.iterator(cfName).filter { kv =>
        val keyRow = kv.key
        keyRow.isNullAt(0)
      }
      assert(nullRows.size === 5)

      // filter out the null rows and verify the rest
      val result: Seq[(Long, Int)] = store.iterator(cfName).filter { kv =>
        val keyRow = kv.key
        !keyRow.isNullAt(0)
      }.map { kv =>
        val keyRow = kv.key
        val key = (keyRow.getLong(0), keyRow.getInt(1), keyRow.getString(2))
        (key._1, key._2)
      }.toSeq

      val timerTimestampsWithoutNulls = Seq((931L, 10), (452300L, 1),
        (4200L, 68), (90L, 2000), (1L, 27), (1L, 394), (1L, 5), (3L, 980), (35L, 2112),
        (6L, 90118), (9L, 95118), (6L, 87210), (-67450L, 2434), (-803L, 3422))

      assert(result === timerTimestampsWithoutNulls.sorted)

      // verify that the null cols are seen in the correct order filtering for nulls
      val nullRowsWithOrder = store.iterator(cfName).filter { kv =>
        val keyRow = kv.key
        keyRow.isNullAt(0)
      }.map { kv =>
        val keyRow = kv.key
        keyRow.getInt(1)
      }.toSeq

      assert(nullRowsWithOrder === Seq(-5534, -23, 28, 40, 113))

      store.abort()

      val store1 = provider.getStore(0)
      if (colFamiliesEnabled) {
        store1.createColFamilyIfAbsent(cfName,
          testSchema, valueSchema,
          RangeKeyScanStateEncoderSpec(testSchema, 2))
      }

      val timerTimestamps1 = Seq((null, 3), (null, 1), (null, 32),
        (null, 113), (null, 40872), (null, -675456), (null, -924), (null, -666),
        (null, 66))
      timerTimestamps1.foreach { ts =>
        // order by long col first and then by int col
        val keyRow = schemaProj.apply(new GenericInternalRow(Array[Any](ts._1, ts._2,
          UTF8String.fromString(Random.alphanumeric.take(Random.nextInt(20) + 1).mkString))))
        val valueRow = dataToValueRow(1)
        store1.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store1.get(keyRow, cfName)) === 1)
      }

      // verify that ordering for non-null columns on the right in still maintained
      val result1: Seq[Int] = store.iterator(cfName).map { kv =>
        val keyRow = kv.key
        keyRow.getInt(1)
      }.toSeq

      assert(result1 === timerTimestamps1.map(_._2).sorted)
    }
  }

  testWithColumnFamilies("rocksdb range scan multiple ordering columns - variable size " +
    s"non-ordering columns with null values in second ordering column",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    val testSchema: StructType = StructType(
      Seq(StructField("key1", LongType, true),
        StructField("key2", IntegerType, true),
        StructField("key3", StringType, false)))

    val schemaProj = UnsafeProjection.create(Array[DataType](LongType, IntegerType, StringType))

    tryWithProviderResource(newStoreProvider(testSchema,
      RangeKeyScanStateEncoderSpec(testSchema, 2), colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(cfName,
          testSchema, valueSchema,
          RangeKeyScanStateEncoderSpec(testSchema, 2))
      }

      val timerTimestamps = Seq((931L, 10), (40L, null), (452300L, 1),
        (-133L, null), (-344555L, 2424), (-4342499L, null),
        (4200L, 68), (90L, 2000), (1L, 27), (1L, 394), (1L, 5), (3L, 980), (35L, 2112),
        (6L, 90118), (9L, 95118), (6L, 87210), (113L, null), (100L, null))
      timerTimestamps.foreach { ts =>
        // order by long col first and then by int col
        val keyRow = schemaProj.apply(new GenericInternalRow(Array[Any](ts._1, ts._2,
          UTF8String.fromString(Random.alphanumeric.take(Random.nextInt(20) + 1).mkString))))
        val valueRow = dataToValueRow(1)
        store.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store.get(keyRow, cfName)) === 1)
      }

      // verify that the expected null cols are seen
      val nullRows = store.iterator(cfName).filter { kv =>
        val keyRow = kv.key
        keyRow.isNullAt(1)
      }
      assert(nullRows.size === 5)

      // the ordering based on first col which has non-null values should be preserved
      val result: Seq[(Long, Int)] = store.iterator(cfName)
        .map { kv =>
          val keyRow = kv.key
          val key = (keyRow.getLong(0), keyRow.getInt(1), keyRow.getString(2))
          (key._1, key._2)
        }.toSeq
      assert(result.map(_._1) === timerTimestamps.map(_._1).sorted)
    }
  }

  testWithColumnFamilies("rocksdb range scan byte ordering column - variable size " +
    s"non-ordering columns",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    val testSchema: StructType = StructType(
      Seq(StructField("key1", ByteType, false),
        StructField("key2", IntegerType, false),
        StructField("key3", StringType, false)))

    val schemaProj = UnsafeProjection.create(Array[DataType](ByteType, IntegerType, StringType))

    tryWithProviderResource(newStoreProvider(testSchema,
      RangeKeyScanStateEncoderSpec(testSchema, 2), colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(cfName,
          testSchema, valueSchema,
          RangeKeyScanStateEncoderSpec(testSchema, 2))
      }

      val timerTimestamps: Seq[(Byte, Int)] = Seq((0x33, 10), (0x1A, 40), (0x1F, 1), (0x01, 68),
        (0x7F, 2000), (0x01, 27), (0x01, 394), (0x01, 5), (0x03, 980), (0x35, 2112),
        (0x11, -190), (0x1A, -69), (0x01, -344245), (0x31, -901),
        (0x06, 90118), (0x09, 95118), (0x06, 87210))
      timerTimestamps.foreach { ts =>
        // order by byte col first and then by int col
        val keyRow = schemaProj.apply(new GenericInternalRow(Array[Any](ts._1, ts._2,
          UTF8String.fromString(Random.alphanumeric.take(Random.nextInt(20) + 1).mkString))))
        val valueRow = dataToValueRow(1)
        store.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store.get(keyRow, cfName)) === 1)
      }

      val result: Seq[(Byte, Int)] = store.iterator(cfName).map { kv =>
        val keyRow = kv.key
        val key = (keyRow.getByte(0), keyRow.getInt(1), keyRow.getString(2))
        (key._1, key._2)
      }.toSeq
      assert(result === timerTimestamps.sorted)
    }
  }

  testWithColumnFamilies("rocksdb range scan - ordering cols and key schema cols are same",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    // use the same schema as value schema for single col key schema
    tryWithProviderResource(newStoreProvider(valueSchema,
      RangeKeyScanStateEncoderSpec(valueSchema, 1), colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)
      val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(cfName,
          valueSchema, valueSchema,
          RangeKeyScanStateEncoderSpec(valueSchema, 1))
      }

      val timerTimestamps = Seq(931, 8000, 452300, 4200,
        -3545, -343, 133, -90, -8014490, -79247,
        90, 1, 2, 8, 3, 35, 6, 9, 5, -233)
      timerTimestamps.foreach { ts =>
        // non-timestamp col is of variable size
        val keyRow = dataToValueRow(ts)
        val valueRow = dataToValueRow(1)
        store.put(keyRow, valueRow, cfName)
        assert(valueRowToData(store.get(keyRow, cfName)) === 1)
      }

      val result = store.iterator(cfName).map { kv =>
        valueRowToData(kv.key)
      }.toSeq
      assert(result === timerTimestamps.sorted)

      // also check for prefix scan
      timerTimestamps.foreach { ts =>
        val prefix = dataToValueRow(ts)
        val result = store.prefixScan(prefix, cfName).map { kv =>
          assert(valueRowToData(kv.value) === 1)
          valueRowToData(kv.key)
        }.toSeq
        assert(result.size === 1)
      }
    }
  }

  testWithColumnFamilies("rocksdb range scan - with prefix scan",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>

    tryWithProviderResource(newStoreProvider(keySchemaWithRangeScan,
      RangeKeyScanStateEncoderSpec(keySchemaWithRangeScan, 1), colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)

      val cfName = if (colFamiliesEnabled) "testColFamily" else "default"
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(cfName,
          keySchemaWithRangeScan, valueSchema,
          RangeKeyScanStateEncoderSpec(keySchemaWithRangeScan, 1))
      }

      val timerTimestamps = Seq(931L, -1331L, 8000L, 1L, -244L, -8350L, -55L)
      timerTimestamps.zipWithIndex.foreach { case (ts, idx) =>
        (1 to idx + 1).foreach { keyVal =>
          val keyRow = dataToKeyRowWithRangeScan(ts, keyVal.toString)
          val valueRow = dataToValueRow(1)
          store.put(keyRow, valueRow, cfName)
          assert(valueRowToData(store.get(keyRow, cfName)) === 1)
        }
      }

      timerTimestamps.zipWithIndex.foreach { case (ts, idx) =>
        val prefix = dataToPrefixKeyRowWithRangeScan(ts)
        val result = store.prefixScan(prefix, cfName).map { kv =>
          assert(valueRowToData(kv.value) === 1)
          val key = keyRowWithRangeScanToData(kv.key)
          key._2
        }.toSeq
        assert(result.size === idx + 1)
      }
    }
  }

  testWithColumnFamilies("rocksdb key and value schema encoders for column families",
    TestWithBothChangelogCheckpointingEnabledAndDisabled) { colFamiliesEnabled =>
    val testColFamily = "testState"

    tryWithProviderResource(newStoreProvider(colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)
      if (colFamiliesEnabled) {
        store.createColFamilyIfAbsent(testColFamily,
          keySchema, valueSchema, NoPrefixKeyStateEncoderSpec(keySchema))
        val keyRow1 = dataToKeyRow("a", 0)
        val valueRow1 = dataToValueRow(1)
        store.put(keyRow1, valueRow1, colFamilyName = testColFamily)
        assert(valueRowToData(store.get(keyRow1, colFamilyName = testColFamily)) === 1)
        store.remove(keyRow1, colFamilyName = testColFamily)
        assert(store.get(keyRow1, colFamilyName = testColFamily) === null)
      }
      val keyRow2 = dataToKeyRow("b", 0)
      val valueRow2 = dataToValueRow(2)
      store.put(keyRow2, valueRow2)
      assert(valueRowToData(store.get(keyRow2)) === 2)
      store.remove(keyRow2)
      assert(store.get(keyRow2) === null)
    }
  }

  test("validate rocksdb values iterator correctness") {
    withSQLConf(SQLConf.STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT.key -> "1") {
      tryWithProviderResource(newStoreProvider(useColumnFamilies = true,
        useMultipleValuesPerKey = true)) { provider =>
        val store = provider.getStore(0)
        // Verify state after updating
        put(store, "a", 0, 1)

        val iterator0 = store.valuesIterator(dataToKeyRow("a", 0))

        assert(iterator0.hasNext)
        assert(valueRowToData(iterator0.next()) === 1)
        assert(!iterator0.hasNext)

        merge(store, "a", 0, 2)
        merge(store, "a", 0, 3)

        val iterator1 = store.valuesIterator(dataToKeyRow("a", 0))

        (1 to 3).map { i =>
          assert(iterator1.hasNext)
          assert(valueRowToData(iterator1.next()) === i)
        }

        assert(!iterator1.hasNext)

        remove(store, _._1 == "a")
        val iterator2 = store.valuesIterator(dataToKeyRow("a", 0))
        assert(!iterator2.hasNext)

        assert(get(store, "a", 0).isEmpty)
      }
    }
  }

  override def newStoreProvider(): RocksDBStateStoreProvider = {
    newStoreProvider(StateStoreId(newDir(), Random.nextInt(), 0))
  }

  def newStoreProvider(storeId: StateStoreId): RocksDBStateStoreProvider = {
    newStoreProvider(storeId, NoPrefixKeyStateEncoderSpec(keySchema))
  }

  override def newStoreProvider(storeId: StateStoreId, useColumnFamilies: Boolean):
    RocksDBStateStoreProvider = {
    newStoreProvider(storeId, NoPrefixKeyStateEncoderSpec(keySchema),
    useColumnFamilies = useColumnFamilies)
  }

  override def newStoreProvider(useColumnFamilies: Boolean): RocksDBStateStoreProvider = {
    newStoreProvider(StateStoreId(newDir(), Random.nextInt(), 0),
      NoPrefixKeyStateEncoderSpec(keySchema),
      useColumnFamilies = useColumnFamilies)
  }

  def newStoreProvider(useColumnFamilies: Boolean,
      useMultipleValuesPerKey: Boolean): RocksDBStateStoreProvider = {
    newStoreProvider(StateStoreId(newDir(), Random.nextInt(), 0),
      NoPrefixKeyStateEncoderSpec(keySchema),
      useColumnFamilies = useColumnFamilies,
      useMultipleValuesPerKey = useMultipleValuesPerKey
    )
  }

  def newStoreProvider(storeId: StateStoreId, conf: Configuration): RocksDBStateStoreProvider = {
    newStoreProvider(storeId, NoPrefixKeyStateEncoderSpec(keySchema), conf = conf)
  }

  override def newStoreProvider(
      keySchema: StructType,
      keyStateEncoderSpec: KeyStateEncoderSpec,
      useColumnFamilies: Boolean): RocksDBStateStoreProvider = {
    newStoreProvider(StateStoreId(newDir(), Random.nextInt(), 0),
      keyStateEncoderSpec = keyStateEncoderSpec,
      keySchema = keySchema,
      useColumnFamilies = useColumnFamilies)
  }

  def newStoreProvider(
      storeId: StateStoreId,
      keyStateEncoderSpec: KeyStateEncoderSpec,
      keySchema: StructType = keySchema,
      sqlConf: Option[SQLConf] = None,
      conf: Configuration = new Configuration,
      useColumnFamilies: Boolean = false,
      useMultipleValuesPerKey: Boolean = false): RocksDBStateStoreProvider = {
    val provider = new RocksDBStateStoreProvider()
    provider.init(
      storeId,
      keySchema,
      valueSchema,
      keyStateEncoderSpec,
      useColumnFamilies,
      new StateStoreConf(sqlConf.getOrElse(SQLConf.get)),
      conf,
      useMultipleValuesPerKey)
    provider
  }

  override def getLatestData(
      storeProvider: RocksDBStateStoreProvider,
      useColumnFamilies: Boolean = false): Set[((String, Int), Int)] = {
    getData(storeProvider, version = -1, useColumnFamilies)
  }

  override def getData(
      provider: RocksDBStateStoreProvider,
      version: Int = -1,
      useColumnFamilies: Boolean = false): Set[((String, Int), Int)] = {
    tryWithProviderResource(newStoreProvider(provider.stateStoreId,
      useColumnFamilies)) { reloadedProvider =>
      val versionToRead = if (version < 0) reloadedProvider.latestVersion else version
      reloadedProvider.getStore(versionToRead).iterator().map(rowPairToDataPair).toSet
    }
  }

  override def newStoreProvider(
    minDeltasForSnapshot: Int,
    numOfVersToRetainInMemory: Int): RocksDBStateStoreProvider = {
    newStoreProvider(StateStoreId(newDir(), Random.nextInt(), 0),
      NoPrefixKeyStateEncoderSpec(keySchema),
      sqlConf = Some(getDefaultSQLConf(minDeltasForSnapshot, numOfVersToRetainInMemory)))
  }

  override def getDefaultSQLConf(
    minDeltasForSnapshot: Int,
    numOfVersToRetainInMemory: Int): SQLConf = {
    val sqlConf = SQLConf.get.clone()
    sqlConf.setConfString(
      SQLConf.STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT.key, minDeltasForSnapshot.toString)
    sqlConf
  }

  override def testQuietly(name: String)(f: => Unit): Unit = {
    test(name) {
      quietly {
        f
      }
    }
  }

  override def testWithAllCodec(name: String)(func: Boolean => Any): Unit = {
    Seq(true, false).foreach { colFamiliesEnabled =>
      codecsInShortName.foreach { codecName =>
        super.test(s"$name - with codec $codecName - colFamiliesEnabled=$colFamiliesEnabled") {
          withSQLConf(SQLConf.STATE_STORE_COMPRESSION_CODEC.key -> codecName) {
            func(colFamiliesEnabled)
          }
        }
      }

      CompressionCodec.ALL_COMPRESSION_CODECS.foreach { codecName =>
        super.test(s"$name - with codec $codecName - colFamiliesEnabled=$colFamiliesEnabled") {
          withSQLConf(SQLConf.STATE_STORE_COMPRESSION_CODEC.key -> codecName) {
            func(colFamiliesEnabled)
          }
        }
      }
    }
  }
}

