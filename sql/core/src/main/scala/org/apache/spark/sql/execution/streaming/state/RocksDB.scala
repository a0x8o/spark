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

import java.io.File
import java.util.Locale
import javax.annotation.concurrent.GuardedBy

import scala.collection.{mutable, Map}
import scala.collection.JavaConverters._
import scala.ref.WeakReference
import scala.util.Try

import org.apache.hadoop.conf.Configuration
import org.json4s.NoTypeHints
import org.json4s.jackson.Serialization
import org.rocksdb.{RocksDB => NativeRocksDB, _}

import org.apache.spark.TaskContext
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.util.CaseInsensitiveMap
import org.apache.spark.util.{NextIterator, Utils}

/**
 * Class representing a RocksDB instance that checkpoints version of data to DFS.
 * After a set of updates, a new version can be committed by calling `commit()`.
 * Any past version can be loaded by calling `load(version)`.
 *
 * @note This class is not thread-safe, so use it only from one thread.
 * @see [[RocksDBFileManager]] to see how the files are laid out in local disk and DFS.
 * @param dfsRootDir  Remote directory where checkpoints are going to be written
 * @param conf         Configuration for RocksDB
 * @param localRootDir Root directory in local disk that is used to working and checkpointing dirs
 * @param hadoopConf   Hadoop configuration for talking to the remote file system
 * @param loggingId    Id that will be prepended in logs for isolating concurrent RocksDBs
 */
class RocksDB(
    dfsRootDir: String,
    val conf: RocksDBConf,
    localRootDir: File = Utils.createTempDir(),
    hadoopConf: Configuration = new Configuration,
    loggingId: String = "") extends Logging {

  RocksDBLoader.loadLibrary()

  // Java wrapper objects linking to native RocksDB objects
  private val readOptions = new ReadOptions()  // used for gets
  private val writeOptions = new WriteOptions().setSync(true)  // wait for batched write to complete
  private val flushOptions = new FlushOptions().setWaitForFlush(true)  // wait for flush to complete
  private val writeBatch = new WriteBatchWithIndex(true)  // overwrite multiple updates to a key

  private val bloomFilter = new BloomFilter()
  private val tableFormatConfig = new BlockBasedTableConfig()
  tableFormatConfig.setBlockSize(conf.blockSizeKB * 1024)
  tableFormatConfig.setBlockCache(new LRUCache(conf.blockCacheSizeMB * 1024 * 1024))
  tableFormatConfig.setFilterPolicy(bloomFilter)

  private val dbOptions = new Options() // options to open the RocksDB
  dbOptions.setCreateIfMissing(true)
  dbOptions.setTableFormatConfig(tableFormatConfig)
  private val dbLogger = createLogger() // for forwarding RocksDB native logs to log4j
  dbOptions.setStatistics(new Statistics())
  private val nativeStats = dbOptions.statistics()

  private val workingDir = createTempDir("workingDir")
  private val fileManager = new RocksDBFileManager(
    dfsRootDir, createTempDir("fileManager"), hadoopConf, loggingId = loggingId)
  private val byteArrayPair = new ByteArrayPair()
  private val commitLatencyMs = new mutable.HashMap[String, Long]()
  private val acquireLock = new Object

  @volatile private var db: NativeRocksDB = _
  @volatile private var loadedVersion = -1L   // -1 = nothing valid is loaded
  @volatile private var numKeysOnLoadedVersion = 0L
  @volatile private var numKeysOnWritingVersion = 0L
  @volatile private var fileManagerMetrics = RocksDBFileManagerMetrics.EMPTY_METRICS

  @GuardedBy("acquireLock")
  @volatile private var acquiredThreadInfo: AcquiredThreadInfo = _

  private val prefixScanReuseIter =
    new java.util.concurrent.ConcurrentHashMap[Long, RocksIterator]()

  /**
   * Load the given version of data in a native RocksDB instance.
   * Note that this will copy all the necessary file from DFS to local disk as needed,
   * and possibly restart the native RocksDB instance.
   */
  def load(version: Long): RocksDB = {
    assert(version >= 0)
    acquire()
    logInfo(s"Loading $version")
    try {
      if (loadedVersion != version) {
        closeDB()
        val metadata = fileManager.loadCheckpointFromDfs(version, workingDir)
        openDB()
        numKeysOnWritingVersion = metadata.numKeys
        numKeysOnLoadedVersion = metadata.numKeys
        loadedVersion = version
        fileManagerMetrics = fileManager.latestLoadCheckpointMetrics
      }
      writeBatch.clear()
      logInfo(s"Loaded $version")
    } catch {
      case t: Throwable =>
        loadedVersion = -1  // invalidate loaded data
        throw t
    }
    this
  }

  /**
   * Get the value for the given key if present, or null.
   * @note This will return the last written value even if it was uncommitted.
   */
  def get(key: Array[Byte]): Array[Byte] = {
    writeBatch.getFromBatchAndDB(db, readOptions, key)
  }

  /**
   * Put the given value for the given key and return the last written value.
   * @note This update is not committed to disk until commit() is called.
   */
  def put(key: Array[Byte], value: Array[Byte]): Array[Byte] = {
    val oldValue = writeBatch.getFromBatchAndDB(db, readOptions, key)
    writeBatch.put(key, value)
    if (oldValue == null) {
      numKeysOnWritingVersion += 1
    }
    oldValue
  }

  /**
   * Remove the key if present, and return the previous value if it was present (null otherwise).
   * @note This update is not committed to disk until commit() is called.
   */
  def remove(key: Array[Byte]): Array[Byte] = {
    val value = writeBatch.getFromBatchAndDB(db, readOptions, key)
    if (value != null) {
      writeBatch.remove(key)
      numKeysOnWritingVersion -= 1
    }
    value
  }

  /**
   * Get an iterator of all committed and uncommitted key-value pairs.
   */
  def iterator(): Iterator[ByteArrayPair] = {
    val iter = writeBatch.newIteratorWithBase(db.newIterator())
    logInfo(s"Getting iterator from version $loadedVersion")
    iter.seekToFirst()

    // Attempt to close this iterator if there is a task failure, or a task interruption.
    // This is a hack because it assumes that the RocksDB is running inside a task.
    Option(TaskContext.get()).foreach { tc =>
      tc.addTaskCompletionListener[Unit] { _ => iter.close() }
    }

    new NextIterator[ByteArrayPair] {
      override protected def getNext(): ByteArrayPair = {
        if (iter.isValid) {
          byteArrayPair.set(iter.key, iter.value)
          iter.next()
          byteArrayPair
        } else {
          finished = true
          iter.close()
          null
        }
      }
      override protected def close(): Unit = { iter.close() }
    }
  }

  def prefixScan(prefix: Array[Byte]): Iterator[ByteArrayPair] = {
    val threadId = Thread.currentThread().getId
    val iter = prefixScanReuseIter.computeIfAbsent(threadId, tid => {
      val it = writeBatch.newIteratorWithBase(db.newIterator())
      logInfo(s"Getting iterator from version $loadedVersion for prefix scan on " +
        s"thread ID $tid")
      it
    })

    iter.seek(prefix)

    new NextIterator[ByteArrayPair] {
      override protected def getNext(): ByteArrayPair = {
        if (iter.isValid && iter.key().take(prefix.length).sameElements(prefix)) {
          byteArrayPair.set(iter.key, iter.value)
          iter.next()
          byteArrayPair
        } else {
          finished = true
          null
        }
      }

      override protected def close(): Unit = {}
    }
  }

  /**
   * Commit all the updates made as a version to DFS. The steps it needs to do to commits are:
   * - Write all the updates to the native RocksDB
   * - Flush all changes to disk
   * - Create a RocksDB checkpoint in a new local dir
   * - Sync the checkpoint dir files to DFS
   */
  def commit(): Long = {
    val newVersion = loadedVersion + 1
    val checkpointDir = createTempDir("checkpoint")
    try {
      // Make sure the directory does not exist. Native RocksDB fails if the directory to
      // checkpoint exists.
      Utils.deleteRecursively(checkpointDir)

      logInfo(s"Writing updates for $newVersion")
      val writeTimeMs = timeTakenMs { db.write(writeOptions, writeBatch) }

      logInfo(s"Flushing updates for $newVersion")
      val flushTimeMs = timeTakenMs { db.flush(flushOptions) }

      val compactTimeMs = if (conf.compactOnCommit) {
        logInfo("Compacting")
        timeTakenMs { db.compactRange() }
      } else 0
      logInfo("Pausing background work")

      val pauseTimeMs = timeTakenMs {
        db.pauseBackgroundWork() // To avoid files being changed while committing
      }

      logInfo(s"Creating checkpoint for $newVersion in $checkpointDir")
      val checkpointTimeMs = timeTakenMs {
        val cp = Checkpoint.create(db)
        cp.createCheckpoint(checkpointDir.toString)
      }

      logInfo(s"Syncing checkpoint for $newVersion to DFS")
      val fileSyncTimeMs = timeTakenMs {
        fileManager.saveCheckpointToDfs(checkpointDir, newVersion, numKeysOnWritingVersion)
      }
      numKeysOnLoadedVersion = numKeysOnWritingVersion
      loadedVersion = newVersion
      fileManagerMetrics = fileManager.latestSaveCheckpointMetrics
      commitLatencyMs ++= Map(
        "writeBatch" -> writeTimeMs,
        "flush" -> flushTimeMs,
        "compact" -> compactTimeMs,
        "pause" -> pauseTimeMs,
        "checkpoint" -> checkpointTimeMs,
        "fileSync" -> fileSyncTimeMs
      )
      logInfo(s"Committed $newVersion, stats = ${metrics.json}")
      loadedVersion
    } catch {
      case t: Throwable =>
        loadedVersion = -1  // invalidate loaded version
        throw t
    } finally {
      db.continueBackgroundWork()
      silentDeleteRecursively(checkpointDir, s"committing $newVersion")
      release()
    }
  }

  /**
   * Drop uncommitted changes, and roll back to previous version.
   */
  def rollback(): Unit = {
    prefixScanReuseIter.entrySet().asScala.foreach(_.getValue.close())
    prefixScanReuseIter.clear()
    writeBatch.clear()
    numKeysOnWritingVersion = numKeysOnLoadedVersion
    release()
    logInfo(s"Rolled back to $loadedVersion")
  }

  def cleanup(): Unit = {
    val cleanupTime = timeTakenMs {
      fileManager.deleteOldVersions(conf.minVersionsToRetain)
    }
    logInfo(s"Cleaned old data, time taken: $cleanupTime ms")
  }

  /** Release all resources */
  def close(): Unit = {
    prefixScanReuseIter.entrySet().asScala.foreach(_.getValue.close())
    prefixScanReuseIter.clear()
    try {
      closeDB()

      // Release all resources related to native RockDB objects
      writeBatch.clear()
      writeBatch.close()
      readOptions.close()
      writeOptions.close()
      flushOptions.close()
      dbOptions.close()
      dbLogger.close()
      silentDeleteRecursively(localRootDir, "closing RocksDB")
    } catch {
      case e: Exception =>
        logWarning("Error closing RocksDB", e)
    }
  }

  /** Get the latest version available in the DFS */
  def getLatestVersion(): Long = fileManager.getLatestVersion()

  /** Get current instantaneous statistics */
  def metrics: RocksDBMetrics = {
    import HistogramType._
    val totalSSTFilesBytes = getDBProperty("rocksdb.total-sst-files-size")
    val readerMemUsage = getDBProperty("rocksdb.estimate-table-readers-mem")
    val memTableMemUsage = getDBProperty("rocksdb.size-all-mem-tables")
    val nativeOps = Seq("get" -> DB_GET, "put" -> DB_WRITE).toMap
    val nativeOpsLatencyMicros = nativeOps.mapValues { typ =>
      RocksDBNativeHistogram(nativeStats.getHistogramData(typ))
    }

    RocksDBMetrics(
      numKeysOnLoadedVersion,
      numKeysOnWritingVersion,
      readerMemUsage + memTableMemUsage,
      totalSSTFilesBytes,
      nativeOpsLatencyMicros.toMap,
      commitLatencyMs,
      bytesCopied = fileManagerMetrics.bytesCopied,
      filesCopied = fileManagerMetrics.filesCopied,
      filesReused = fileManagerMetrics.filesReused,
      zipFileBytesUncompressed = fileManagerMetrics.zipFileBytesUncompressed)
  }

  private def acquire(): Unit = acquireLock.synchronized {
    val newAcquiredThreadInfo = AcquiredThreadInfo()
    val waitStartTime = System.currentTimeMillis
    def timeWaitedMs = System.currentTimeMillis - waitStartTime
    def isAcquiredByDifferentThread = acquiredThreadInfo != null &&
      acquiredThreadInfo.threadRef.get.isDefined &&
      newAcquiredThreadInfo.threadRef.get.get.getId != acquiredThreadInfo.threadRef.get.get.getId

    while (isAcquiredByDifferentThread && timeWaitedMs < conf.lockAcquireTimeoutMs) {
      acquireLock.wait(10)
    }
    if (isAcquiredByDifferentThread) {
      val stackTraceOutput = acquiredThreadInfo.threadRef.get.get.getStackTrace.mkString("\n")
      val msg = s"RocksDB instance could not be acquired by $newAcquiredThreadInfo as it " +
        s"was not released by $acquiredThreadInfo after $timeWaitedMs ms.\n" +
        s"Thread holding the lock has trace: $stackTraceOutput"
      logError(msg)
      throw new IllegalStateException(s"$loggingId: $msg")
    } else {
      acquiredThreadInfo = newAcquiredThreadInfo
      // Add a listener to always release the lock when the task (if active) completes
      Option(TaskContext.get).foreach(_.addTaskCompletionListener[Unit] { _ => this.release() })
      logInfo(s"RocksDB instance was acquired by $acquiredThreadInfo")
    }
  }

  private def release(): Unit = acquireLock.synchronized {
    acquiredThreadInfo = null
    acquireLock.notifyAll()
  }

  private def getDBProperty(property: String): Long = {
    db.getProperty(property).toLong
  }

  private def openDB(): Unit = {
    assert(db == null)
    db = NativeRocksDB.open(dbOptions, workingDir.toString)
    logInfo(s"Opened DB with conf ${conf}")
  }

  private def closeDB(): Unit = {
    if (db != null) {
      db.close()
      db = null
    }
  }

  /** Create a native RocksDB logger that forwards native logs to log4j with correct log levels. */
  private def createLogger(): Logger = {
    val dbLogger = new Logger(dbOptions) {
      override def log(infoLogLevel: InfoLogLevel, logMsg: String) = {
        // Map DB log level to log4j levels
        // Warn is mapped to info because RocksDB warn is too verbose
        // (e.g. dumps non-warning stuff like stats)
        val loggingFunc: ( => String) => Unit = infoLogLevel match {
          case InfoLogLevel.FATAL_LEVEL | InfoLogLevel.ERROR_LEVEL => logError(_)
          case InfoLogLevel.WARN_LEVEL | InfoLogLevel.INFO_LEVEL => logInfo(_)
          case InfoLogLevel.DEBUG_LEVEL => logDebug(_)
          case _ => logTrace(_)
        }
        loggingFunc(s"[NativeRocksDB-${infoLogLevel.getValue}] $logMsg")
      }
    }

    var dbLogLevel = InfoLogLevel.ERROR_LEVEL
    if (log.isWarnEnabled) dbLogLevel = InfoLogLevel.WARN_LEVEL
    if (log.isInfoEnabled) dbLogLevel = InfoLogLevel.INFO_LEVEL
    if (log.isDebugEnabled) dbLogLevel = InfoLogLevel.DEBUG_LEVEL
    dbOptions.setLogger(dbLogger)
    dbOptions.setInfoLogLevel(dbLogLevel)
    logInfo(s"Set RocksDB native logging level to $dbLogLevel")
    dbLogger
  }

  /** Create a temp directory inside the local root directory */
  private def createTempDir(prefix: String): File = {
    Utils.createDirectory(localRootDir.getAbsolutePath, prefix)
  }

  /** Attempt to delete recursively, and log the error if any */
  private def silentDeleteRecursively(file: File, msg: String): Unit = {
    try {
      Utils.deleteRecursively(file)
    } catch {
      case e: Exception =>
        logWarning(s"Error recursively deleting local dir $file while $msg", e)
    }
  }

  /** Records the duration of running `body` for the next query progress update. */
  protected def timeTakenMs(body: => Unit): Long = Utils.timeTakenMs(body)._2

  override protected def logName: String = s"${super.logName} $loggingId"
}


/** Mutable and reusable pair of byte arrays */
class ByteArrayPair(var key: Array[Byte] = null, var value: Array[Byte] = null) {
  def set(key: Array[Byte], value: Array[Byte]): ByteArrayPair = {
    this.key = key
    this.value = value
    this
  }
}


/**
 * Configurations for optimizing RocksDB
 * @param compactOnCommit Whether to compact RocksDB data before commit / checkpointing
 */
case class RocksDBConf(
    minVersionsToRetain: Int,
    compactOnCommit: Boolean,
    pauseBackgroundWorkForCommit: Boolean,
    blockSizeKB: Long,
    blockCacheSizeMB: Long,
    lockAcquireTimeoutMs: Long)

object RocksDBConf {
  /** Common prefix of all confs in SQLConf that affects RocksDB */
  val ROCKSDB_CONF_NAME_PREFIX = "spark.sql.streaming.stateStore.rocksdb"

  private case class ConfEntry(name: String, default: String) {
    def fullName: String = s"$ROCKSDB_CONF_NAME_PREFIX.${name}".toLowerCase(Locale.ROOT)
  }

  // Configuration that specifies whether to compact the RocksDB data every time data is committed
  private val COMPACT_ON_COMMIT_CONF = ConfEntry("compactOnCommit", "false")
  private val PAUSE_BG_WORK_FOR_COMMIT_CONF = ConfEntry("pauseBackgroundWorkForCommit", "true")
  private val BLOCK_SIZE_KB_CONF = ConfEntry("blockSizeKB", "4")
  private val BLOCK_CACHE_SIZE_MB_CONF = ConfEntry("blockCacheSizeMB", "8")
  private val LOCK_ACQUIRE_TIMEOUT_MS_CONF = ConfEntry("lockAcquireTimeoutMs", "60000")

  def apply(storeConf: StateStoreConf): RocksDBConf = {
    val confs = CaseInsensitiveMap[String](storeConf.confs)

    def getBooleanConf(conf: ConfEntry): Boolean = {
      Try { confs.getOrElse(conf.fullName, conf.default).toBoolean } getOrElse {
        throw new IllegalArgumentException(s"Invalid value for '${conf.fullName}', must be boolean")
      }
    }

    def getPositiveLongConf(conf: ConfEntry): Long = {
      Try { confs.getOrElse(conf.fullName, conf.default).toLong } filter { _ >= 0 } getOrElse {
        throw new IllegalArgumentException(
          s"Invalid value for '${conf.fullName}', must be a positive integer")
      }
    }

    RocksDBConf(
      storeConf.minVersionsToRetain,
      getBooleanConf(COMPACT_ON_COMMIT_CONF),
      getBooleanConf(PAUSE_BG_WORK_FOR_COMMIT_CONF),
      getPositiveLongConf(BLOCK_SIZE_KB_CONF),
      getPositiveLongConf(BLOCK_CACHE_SIZE_MB_CONF),
      getPositiveLongConf(LOCK_ACQUIRE_TIMEOUT_MS_CONF))
  }

  def apply(): RocksDBConf = apply(new StateStoreConf())
}

/** Class to represent stats from each commit. */
case class RocksDBMetrics(
    numCommittedKeys: Long,
    numUncommittedKeys: Long,
    memUsageBytes: Long,
    totalSSTFilesBytes: Long,
    nativeOpsLatencyMicros: Map[String, RocksDBNativeHistogram],
    lastCommitLatencyMs: Map[String, Long],
    filesCopied: Long,
    bytesCopied: Long,
    filesReused: Long,
    zipFileBytesUncompressed: Option[Long]) {
  def json: String = Serialization.write(this)(RocksDBMetrics.format)
}

object RocksDBMetrics {
  val format = Serialization.formats(NoTypeHints)
}

/** Class to wrap RocksDB's native histogram */
case class RocksDBNativeHistogram(
    avg: Double, stddev: Double, median: Double, p95: Double, p99: Double) {
  def json: String = Serialization.write(this)(RocksDBMetrics.format)
}

object RocksDBNativeHistogram {
  def apply(nativeHist: HistogramData): RocksDBNativeHistogram = {
    RocksDBNativeHistogram(
      nativeHist.getAverage,
      nativeHist.getStandardDeviation,
      nativeHist.getMedian,
      nativeHist.getPercentile95,
      nativeHist.getPercentile99)
  }
}

case class AcquiredThreadInfo() {
  val threadRef: WeakReference[Thread] = new WeakReference[Thread](Thread.currentThread())
  val tc: TaskContext = TaskContext.get()

  override def toString(): String = {
    val taskStr = if (tc != null) {
      val taskDetails =
        s"${tc.partitionId}.${tc.attemptNumber} in stage ${tc.stageId}, TID ${tc.taskAttemptId}"
      s", task: $taskDetails"
    } else ""

    s"[ThreadId: ${threadRef.get.map(_.getId)}$taskStr]"
  }
}

