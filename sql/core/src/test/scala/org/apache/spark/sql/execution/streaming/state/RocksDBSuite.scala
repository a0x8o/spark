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

import java.io._
import java.nio.charset.Charset

import scala.language.implicitConversions

import org.apache.commons.io.FileUtils
import org.apache.hadoop.conf.Configuration

import org.apache.spark._
import org.apache.spark.sql.catalyst.util.quietly
import org.apache.spark.sql.execution.streaming.CreateAtomicTestManager
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.util.{ThreadUtils, Utils}

class RocksDBSuite extends SparkFunSuite {

  test("RocksDB: get, put, iterator, commit, load") {
    def testOps(compactOnCommit: Boolean): Unit = {
      val remoteDir = Utils.createTempDir().toString
      new File(remoteDir).delete()  // to make sure that the directory gets created

      val conf = RocksDBConf().copy(compactOnCommit = compactOnCommit)
      withDB(remoteDir, conf = conf) { db =>
        assert(db.get("a") === null)
        assert(iterator(db).isEmpty)

        db.put("a", "1")
        assert(toStr(db.get("a")) === "1")
        db.commit()
      }

      withDB(remoteDir, conf = conf, version = 0) { db =>
        // version 0 can be loaded again
        assert(toStr(db.get("a")) === null)
        assert(iterator(db).isEmpty)
      }

      withDB(remoteDir, conf = conf, version = 1) { db =>
        // version 1 data recovered correctly
        assert(toStr(db.get("a")) === "1")
        assert(db.iterator().map(toStr).toSet === Set(("a", "1")))

        // make changes but do not commit version 2
        db.put("b", "2")
        assert(toStr(db.get("b")) === "2")
        assert(db.iterator().map(toStr).toSet === Set(("a", "1"), ("b", "2")))
      }

      withDB(remoteDir, conf = conf, version = 1) { db =>
        // version 1 data not changed
        assert(toStr(db.get("a")) === "1")
        assert(db.get("b") === null)
        assert(db.iterator().map(toStr).toSet === Set(("a", "1")))

        // commit version 2
        db.put("b", "2")
        assert(toStr(db.get("b")) === "2")
        db.commit()
        assert(db.iterator().map(toStr).toSet === Set(("a", "1"), ("b", "2")))
      }

      withDB(remoteDir, conf = conf, version = 1) { db =>
        // version 1 data not changed
        assert(toStr(db.get("a")) === "1")
        assert(db.get("b") === null)
      }

      withDB(remoteDir, conf = conf, version = 2) { db =>
        // version 2 can be loaded again
        assert(toStr(db.get("b")) === "2")
        assert(db.iterator().map(toStr).toSet === Set(("a", "1"), ("b", "2")))

        db.load(1)
        assert(toStr(db.get("b")) === null)
        assert(db.iterator().map(toStr).toSet === Set(("a", "1")))
      }
    }

    for (compactOnCommit <- Seq(false, true)) {
      withClue(s"compactOnCommit = $compactOnCommit") {
        testOps(compactOnCommit)
      }
    }
  }

  test("RocksDB: cleanup old files") {
    val remoteDir = Utils.createTempDir().toString
    val conf = RocksDBConf().copy(compactOnCommit = true, minVersionsToRetain = 10)

    def versionsPresent: Seq[Long] = {
      remoteDir.listFiles.filter(_.getName.endsWith(".zip"))
        .map(_.getName.stripSuffix(".zip"))
        .map(_.toLong)
        .sorted
    }

    withDB(remoteDir, conf = conf) { db =>
      // Generate versions without cleaning up
      for (version <- 1 to 50) {
        db.put(version.toString, version.toString)  // update "1" -> "1", "2" -> "2", ...
        db.commit()
      }

      // Clean up and verify version files and SST files were deleted
      require(versionsPresent === (1L to 50L))
      val sstDir = new File(remoteDir, "SSTs")
      val numSstFiles = listFiles(sstDir).length
      db.cleanup()
      assert(versionsPresent === (41L to 50L))
      assert(listFiles(sstDir).length < numSstFiles)

      // Verify data in retained vesions.
      versionsPresent.foreach { version =>
        db.load(version)
        val data = db.iterator().map(toStr).toSet
        assert(data === (1L to version).map(_.toString).map(x => x -> x).toSet)
      }
    }
  }

  test("RocksDB: handle commit failures and aborts") {
    val hadoopConf = new Configuration()
    hadoopConf.set(
      SQLConf.STREAMING_CHECKPOINT_FILE_MANAGER_CLASS.parent.key,
      classOf[CreateAtomicTestManager].getName)
    val remoteDir = Utils.createTempDir().getAbsolutePath
    val conf = RocksDBConf().copy(compactOnCommit = true)
    withDB(remoteDir, conf = conf, hadoopConf = hadoopConf) { db =>
      // Disable failure of output stream and generate versions
      CreateAtomicTestManager.shouldFailInCreateAtomic = false
      for (version <- 1 to 10) {
        db.put(version.toString, version.toString) // update "1" -> "1", "2" -> "2", ...
        db.commit()
      }
      val version10Data = (1L to 10).map(_.toString).map(x => x -> x).toSet

      // Fail commit for next version and verify that reloading resets the files
      CreateAtomicTestManager.shouldFailInCreateAtomic = true
      db.put("11", "11")
      intercept[IOException] { quietly { db.commit() } }
      assert(db.load(10).iterator().map(toStr).toSet === version10Data)
      CreateAtomicTestManager.shouldFailInCreateAtomic = false

      // Abort commit for next version and verify that reloading resets the files
      db.load(10)
      db.put("11", "11")
      db.rollback()
      assert(db.load(10).iterator().map(toStr).toSet === version10Data)
    }
  }

  test("RocksDBFileManager: upload only new immutable files") {
    withTempDir { dir =>
      val dfsRootDir = dir.getAbsolutePath
      val verificationDir = Utils.createTempDir().getAbsolutePath // local dir to load checkpoints
      val fileManager = new RocksDBFileManager(
        dfsRootDir, Utils.createTempDir(), new Configuration)
      val sstDir = s"$dfsRootDir/SSTs"
      def numRemoteSSTFiles: Int = listFiles(sstDir).length
      val logDir = s"$dfsRootDir/logs"
      def numRemoteLogFiles: Int = listFiles(logDir).length

      // Verify behavior before any saved checkpoints
      assert(fileManager.getLatestVersion() === 0)

      // Try to load incorrect versions
      intercept[FileNotFoundException] {
        fileManager.loadCheckpointFromDfs(1, Utils.createTempDir())
      }

      // Save a version of checkpoint files
      val cpFiles1 = Seq(
        "sst-file1.sst" -> 10,
        "sst-file2.sst" -> 20,
        "other-file1" -> 100,
        "other-file2" -> 200,
        "archive/00001.log" -> 1000,
        "archive/00002.log" -> 2000
      )
      saveCheckpointFiles(fileManager, cpFiles1, version = 1, numKeys = 101)
      assert(fileManager.getLatestVersion() === 1)
      assert(numRemoteSSTFiles == 2) // 2 sst files copied
      assert(numRemoteLogFiles == 2) // 2 log files copied

      // Load back the checkpoint files into another local dir with existing files and verify
      generateFiles(verificationDir, Seq(
        "sst-file1.sst" -> 11, // files with same name but different sizes, should get overwritten
        "other-file1" -> 101,
        "archive/00001.log" -> 1001,
        "random-sst-file.sst" -> 100, // unnecessary files, should get deleted
        "random-other-file" -> 9,
        "00005.log" -> 101,
        "archive/00007.log" -> 101
      ))
      loadAndVerifyCheckpointFiles(fileManager, verificationDir, version = 1, cpFiles1, 101)

      // Save SAME version again with different checkpoint files and load back again to verify
      // whether files were overwritten.
      val cpFiles1_ = Seq(
        "sst-file1.sst" -> 10, // same SST file as before, should not get copied
        "sst-file2.sst" -> 25, // new SST file with same name as before, but different length
        "sst-file3.sst" -> 30, // new SST file
        "other-file1" -> 100, // same non-SST file as before, should not get copied
        "other-file2" -> 210, // new non-SST file with same name as before, but different length
        "other-file3" -> 300, // new non-SST file
        "archive/00001.log" -> 1000, // same log file as before, should not get copied
        "archive/00002.log" -> 2500, // new log file with same name as before, but different length
        "archive/00003.log" -> 3000 // new log file
      )
      saveCheckpointFiles(fileManager, cpFiles1_, version = 1, numKeys = 1001)
      assert(numRemoteSSTFiles === 4, "shouldn't copy same files again") // 2 old + 2 new SST files
      assert(numRemoteLogFiles === 4, "shouldn't copy same files again") // 2 old + 2 new log files
      loadAndVerifyCheckpointFiles(fileManager, verificationDir, version = 1, cpFiles1_, 1001)

      // Save another version and verify
      val cpFiles2 = Seq(
        "sst-file4.sst" -> 40,
        "other-file4" -> 400,
        "archive/00004.log" -> 4000
      )
      saveCheckpointFiles(fileManager, cpFiles2, version = 2, numKeys = 1501)
      assert(numRemoteSSTFiles === 5) // 1 new file over earlier 4 files
      assert(numRemoteLogFiles === 5) // 1 new file over earlier 4 files
      loadAndVerifyCheckpointFiles(fileManager, verificationDir, version = 2, cpFiles2, 1501)

      // Loading an older version should work
      loadAndVerifyCheckpointFiles(fileManager, verificationDir, version = 1, cpFiles1_, 1001)

      // Loading incorrect version should fail
      intercept[FileNotFoundException] {
        loadAndVerifyCheckpointFiles(fileManager, verificationDir, version = 3, Nil, 1001)
      }

      // Loading 0 should delete all files
      require(verificationDir.list().length > 0)
      loadAndVerifyCheckpointFiles(fileManager, verificationDir, version = 0, Nil, 0)
    }
  }

  test("RocksDBFileManager: error writing [version].zip cancels the output stream") {
    quietly {
      val hadoopConf = new Configuration()
      hadoopConf.set(
        SQLConf.STREAMING_CHECKPOINT_FILE_MANAGER_CLASS.parent.key,
        classOf[CreateAtomicTestManager].getName)
      val dfsRootDir = Utils.createTempDir().getAbsolutePath
      val fileManager = new RocksDBFileManager(dfsRootDir, Utils.createTempDir(), hadoopConf)
      val cpFiles = Seq("sst-file1.sst" -> 10, "sst-file2.sst" -> 20, "other-file1" -> 100)
      CreateAtomicTestManager.shouldFailInCreateAtomic = true
      intercept[IOException] {
        saveCheckpointFiles(fileManager, cpFiles, version = 1, numKeys = 101)
      }
      assert(CreateAtomicTestManager.cancelCalledInCreateAtomic)
    }
  }

  test("disallow concurrent updates to the same RocksDB instance") {
    quietly {
      withDB(
        Utils.createTempDir().toString,
        conf = RocksDBConf().copy(lockAcquireTimeoutMs = 20)) { db =>
        // DB has been loaded so current thread has alread acquired the lock on the RocksDB instance

        db.load(0)  // Current thread should be able to load again

        // Another thread should not be able to load while current thread is using it
        val ex = intercept[IllegalStateException] {
          ThreadUtils.runInNewThread("concurrent-test-thread-1") { db.load(0) }
        }
        // Assert that the error message contains the stack trace
        assert(ex.getMessage.contains("Thread holding the lock has trace:"))
        assert(ex.getMessage.contains("runInNewThread"))

        // Commit should release the instance allowing other threads to load new version
        db.commit()
        ThreadUtils.runInNewThread("concurrent-test-thread-2") {
          db.load(1)
          db.commit()
        }

        // Another thread should not be able to load while current thread is using it
        db.load(2)
        intercept[IllegalStateException] {
          ThreadUtils.runInNewThread("concurrent-test-thread-2") { db.load(2) }
        }

        // Rollback should release the instance allowing other threads to load new version
        db.rollback()
        ThreadUtils.runInNewThread("concurrent-test-thread-3") {
          db.load(1)
          db.commit()
        }
      }
    }
  }

  test("ensure concurrent access lock is released after Spark task completes") {
    val conf = new SparkConf().setAppName("test").setMaster("local")
    val sc = new SparkContext(conf)

    try {
      RocksDBSuite.withSingletonDB {
        // Load a RocksDB instance, that is, get a lock inside a task and then fail
        quietly {
          intercept[Exception] {
            sc.makeRDD[Int](1 to 1, 1).map { i =>
              RocksDBSuite.singleton.load(0)
              throw new Exception("fail this task to test lock release")
            }.count()
          }
        }

        // Test whether you can load again, that is, will it successfully lock again
        RocksDBSuite.singleton.load(0)
      }
    } finally {
      sc.stop()
    }
  }

  ignore("ensure that concurrent update and cleanup consistent versions") {
    quietly {
      val numThreads = 20
      val numUpdatesInEachThread = 20
      val remoteDir = Utils.createTempDir().toString
      @volatile var exception: Exception = null
      val updatingThreads = Array.fill(numThreads) {
        new Thread() {
          override def run(): Unit = {
            try {
              for (version <- 0 to numUpdatesInEachThread) {
                withDB(
                  remoteDir,
                  version = version) { db =>
                  val prevValue = Option(toStr(db.get("a"))).getOrElse("0").toInt
                  db.put("a", (prevValue + 1).toString)
                  db.commit()
                }
              }
            } catch {
              case e: Exception =>
                val newException = new Exception(s"ThreadId ${this.getId} failed", e)
                if (exception != null) {
                  exception = newException
                }
                throw e
            }
          }
        }
      }
      val cleaningThread = new Thread() {
        override def run(): Unit = {
          try {
            withDB(remoteDir, conf = RocksDBConf().copy(compactOnCommit = true)) { db =>
              while (!this.isInterrupted) {
                db.cleanup()
                Thread.sleep(1)
              }
            }
          } catch {
            case e: Exception =>
              val newException = new Exception(s"ThreadId ${this.getId} failed", e)
              if (exception != null) {
                exception = newException
              }
              throw e
          }
        }
      }
      updatingThreads.foreach(_.start())
      cleaningThread.start()
      updatingThreads.foreach(_.join())
      cleaningThread.interrupt()
      cleaningThread.join()
      if (exception != null) {
        fail(exception)
      }
      withDB(remoteDir, numUpdatesInEachThread) { db =>
        assert(toStr(db.get("a")) === numUpdatesInEachThread.toString)
      }
    }
  }

  test("checkpoint metadata serde roundtrip") {
    def checkJsonRoundtrip(metadata: RocksDBCheckpointMetadata, json: String): Unit = {
      assert(metadata.json == json)
      withTempDir { dir =>
        val file = new File(dir, "json")
        FileUtils.write(file, s"v1\n$json", Charset.defaultCharset)
        assert(metadata == RocksDBCheckpointMetadata.readFromFile(file))
      }
    }
    val sstFiles = Seq(RocksDBSstFile("00001.sst", "00001-uuid.sst", 12345678901234L))
    val logFiles = Seq(RocksDBLogFile("00001.log", "00001-uuid.log", 12345678901234L))

    // scalastyle:off line.size.limit
    // should always include sstFiles and numKeys
    checkJsonRoundtrip(
      RocksDBCheckpointMetadata(Seq.empty, 0L),
      """{"sstFiles":[],"numKeys":0}"""
    )
    // shouldn't include the "logFiles" field in json when it's empty
    checkJsonRoundtrip(
      RocksDBCheckpointMetadata(sstFiles, 12345678901234L),
      """{"sstFiles":[{"localFileName":"00001.sst","dfsSstFileName":"00001-uuid.sst","sizeBytes":12345678901234}],"numKeys":12345678901234}"""
    )
    checkJsonRoundtrip(
      RocksDBCheckpointMetadata(sstFiles, logFiles, 12345678901234L),
      """{"sstFiles":[{"localFileName":"00001.sst","dfsSstFileName":"00001-uuid.sst","sizeBytes":12345678901234}],"logFiles":[{"localFileName":"00001.log","dfsLogFileName":"00001-uuid.log","sizeBytes":12345678901234}],"numKeys":12345678901234}""")
    // scalastyle:on line.size.limit
  }

  def withDB[T](
      remoteDir: String,
      version: Int = 0,
      conf: RocksDBConf = RocksDBConf().copy(compactOnCommit = false, minVersionsToRetain = 100),
      hadoopConf: Configuration = new Configuration())(
      func: RocksDB => T): T = {
    var db: RocksDB = null
    try {
      db = new RocksDB(
        remoteDir, conf = conf, hadoopConf = hadoopConf,
        loggingId = s"[Thread-${Thread.currentThread.getId}]")
      db.load(version)
      func(db)
    } finally {
      if (db != null) {
        db.close()
      }
    }
  }

  def generateFiles(dir: String, fileToLengths: Seq[(String, Int)]): Unit = {
    fileToLengths.foreach { case (fileName, length) =>
      val file = new File(dir, fileName)
      FileUtils.write(file, "a" * length)
    }
  }

  def saveCheckpointFiles(
      fileManager: RocksDBFileManager,
      fileToLengths: Seq[(String, Int)],
      version: Int,
      numKeys: Int): Unit = {
    val checkpointDir = Utils.createTempDir().getAbsolutePath // local dir to create checkpoints
    generateFiles(checkpointDir, fileToLengths)
    fileManager.saveCheckpointToDfs(checkpointDir, version, numKeys)
  }

  def loadAndVerifyCheckpointFiles(
      fileManager: RocksDBFileManager,
      verificationDir: String,
      version: Int,
      expectedFiles: Seq[(String, Int)],
      expectedNumKeys: Int): Unit = {
    val metadata = fileManager.loadCheckpointFromDfs(version, verificationDir)
    val filesAndLengths =
      listFiles(verificationDir).map(f => f.getName -> f.length).toSet ++
      listFiles(verificationDir + "/archive").map(f => s"archive/${f.getName}" -> f.length()).toSet
    assert(filesAndLengths === expectedFiles.toSet)
    assert(metadata.numKeys === expectedNumKeys)
  }

  implicit def toFile(path: String): File = new File(path)

  implicit def toArray(str: String): Array[Byte] = if (str != null) str.getBytes else null

  implicit def toStr(bytes: Array[Byte]): String = if (bytes != null) new String(bytes) else null

  def toStr(kv: ByteArrayPair): (String, String) = (toStr(kv.key), toStr(kv.value))

  def iterator(db: RocksDB): Iterator[(String, String)] = db.iterator().map(toStr)

  def listFiles(file: File): Seq[File] = {
    if (!file.exists()) return Seq.empty
    file.listFiles.filter(file => !file.getName.endsWith("crc") && !file.isDirectory)
  }

  def listFiles(file: String): Seq[File] = listFiles(new File(file))
}

object RocksDBSuite {
  @volatile var singleton: RocksDB = _

  def withSingletonDB[T](func: => T): T = {
    try {
      singleton = new RocksDB(
        dfsRootDir = Utils.createTempDir().getAbsolutePath,
        conf = RocksDBConf().copy(compactOnCommit = false, minVersionsToRetain = 100),
        hadoopConf = new Configuration(),
        loggingId = s"[Thread-${Thread.currentThread.getId}]")

      func
    } finally {
      if (singleton != null) {
        singleton.close()
        singleton = null
      }
    }
  }
}
