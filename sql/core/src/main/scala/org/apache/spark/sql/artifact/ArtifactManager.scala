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

package org.apache.spark.sql.artifact

import java.io.File
import java.net.{URI, URL, URLClassLoader}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.CopyOnWriteArrayList

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

import org.apache.commons.io.{FilenameUtils, FileUtils}
import org.apache.hadoop.fs.{LocalFileSystem, Path => FSPath}

import org.apache.spark.{JobArtifactSet, JobArtifactState, SparkEnv, SparkUnsupportedOperationException}
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config.{CONNECT_SCALA_UDF_STUB_PREFIXES, EXECUTOR_USER_CLASS_PATH_FIRST}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.artifact.util.ArtifactUtils
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.storage.{CacheId, StorageLevel}
import org.apache.spark.util.{ChildFirstURLClassLoader, StubClassLoader, Utils}

/**
 * This class handles the storage of artifacts as well as preparing the artifacts for use.
 *
 * Artifacts belonging to different SparkSessions are isolated from each other with the help of the
 * `sessionUUID`.
 *
 * Jars and classfile artifacts are stored under "jars", "classes" and "pyfiles" sub-directories
 * respectively while other types of artifacts are stored under the root directory for that
 * particular SparkSession.
 *
 * @param session The object used to hold the Spark Connect session state.
 */
class ArtifactManager(session: SparkSession) extends Logging {
  import ArtifactManager._

  // The base directory where all artifacts are stored.
  protected def artifactRootPath: Path = artifactRootDirectory

  private[artifact] lazy val artifactRootURI: String = SparkEnv
    .get
    .rpcEnv
    .fileServer
    .addDirectoryIfAbsent(ARTIFACT_DIRECTORY_PREFIX, artifactRootPath.toFile)

  // The base directory/URI where all artifacts are stored for this `sessionUUID`.
  protected[artifact] val (artifactPath, artifactURI): (Path, String) =
    (ArtifactUtils.concatenatePaths(artifactRootPath, session.sessionUUID),
      s"$artifactRootURI/${session.sessionUUID}")

  // The base directory/URI where all class file artifacts are stored for this `sessionUUID`.
  protected[artifact] val (classDir, classURI): (Path, String) =
    (ArtifactUtils.concatenatePaths(artifactPath, "classes"), s"$artifactURI/classes/")

  protected[artifact] val state: JobArtifactState =
    JobArtifactState(session.sessionUUID, Option(classURI))

  def withResources[T](f: => T): T = {
    Utils.withContextClassLoader(classloader) {
      JobArtifactSet.withActiveJobArtifactState(state) {
        f
      }
    }
  }

  protected val jarsList = new CopyOnWriteArrayList[Path]
  protected val pythonIncludeList = new CopyOnWriteArrayList[String]

  /**
   * Get the URLs of all jar artifacts.
   */
  def getAddedJars: Seq[URL] = jarsList.asScala.map(_.toUri.toURL).toSeq

  /**
   * Get the py-file names added to this SparkSession.
   *
   * @return
   */
  def getPythonIncludes: Seq[String] = pythonIncludeList.asScala.toSeq

  protected def moveFile(source: Path, target: Path, allowOverwrite: Boolean = false): Unit = {
    Files.createDirectories(target.getParent)
    if (allowOverwrite) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    } else {
      Files.move(source, target)
    }
  }

  /**
   * Add and prepare a staged artifact (i.e an artifact that has been rebuilt locally from bytes
   * over the wire) for use.
   *
   * @param remoteRelativePath
   * @param serverLocalStagingPath
   * @param fragment
   */
  def addArtifact(
      remoteRelativePath: Path,
      serverLocalStagingPath: Path,
      fragment: Option[String]): Unit = JobArtifactSet.withActiveJobArtifactState(state) {
    require(!remoteRelativePath.isAbsolute)
    if (remoteRelativePath.startsWith(s"cache${File.separator}")) {
      val tmpFile = serverLocalStagingPath.toFile
      Utils.tryWithSafeFinallyAndFailureCallbacks {
        val blockManager = session.sparkContext.env.blockManager
        val blockId = CacheId(
          sessionUUID = session.sessionUUID,
          hash = remoteRelativePath.toString.stripPrefix(s"cache${File.separator}"))
        val updater = blockManager.TempFileBasedBlockStoreUpdater(
          blockId = blockId,
          level = StorageLevel.MEMORY_AND_DISK_SER,
          classTag = implicitly[ClassTag[Array[Byte]]],
          tmpFile = tmpFile,
          blockSize = tmpFile.length(),
          tellMaster = false)
        updater.save()
      }(catchBlock = { tmpFile.delete() })
    } else if (remoteRelativePath.startsWith(s"classes${File.separator}")) {
      // Move class files to the right directory.
      val target = ArtifactUtils.concatenatePaths(
        classDir,
        remoteRelativePath.toString.stripPrefix(s"classes${File.separator}"))
      // Allow overwriting class files to capture updates to classes.
      // This is required because the client currently sends all the class files in each class file
      // transfer.
      moveFile(serverLocalStagingPath, target, allowOverwrite = true)
    } else {
      val target = ArtifactUtils.concatenatePaths(artifactPath, remoteRelativePath)
      // Disallow overwriting with modified version
      if (Files.exists(target)) {
        // makes the query idempotent
        if (FileUtils.contentEquals(target.toFile, serverLocalStagingPath.toFile)) {
          return
        }

        throw new RuntimeException(s"Duplicate Artifact: $remoteRelativePath. " +
            "Artifacts cannot be overwritten.")
      }
      moveFile(serverLocalStagingPath, target)

      // This URI is for Spark file server that starts with "spark://".
      val uri = s"$artifactURI/${Utils.encodeRelativeUnixPathToURIRawPath(
          FilenameUtils.separatorsToUnix(remoteRelativePath.toString))}"

      if (remoteRelativePath.startsWith(s"jars${File.separator}")) {
        session.sparkContext.addJar(uri)
        jarsList.add(target)
      } else if (remoteRelativePath.startsWith(s"pyfiles${File.separator}")) {
        session.sparkContext.addFile(uri)
        val stringRemotePath = remoteRelativePath.toString
        if (stringRemotePath.endsWith(".zip") || stringRemotePath.endsWith(
            ".egg") || stringRemotePath.endsWith(".jar")) {
          pythonIncludeList.add(target.getFileName.toString)
        }
      } else if (remoteRelativePath.startsWith(s"archives${File.separator}")) {
        val canonicalUri =
          fragment.map(Utils.getUriBuilder(new URI(uri)).fragment).getOrElse(new URI(uri))
        session.sparkContext.addArchive(canonicalUri.toString)
      } else if (remoteRelativePath.startsWith(s"files${File.separator}")) {
        session.sparkContext.addFile(uri)
      }
    }
  }

  /**
   * Returns a [[ClassLoader]] for session-specific jar/class file resources.
   */
  def classloader: ClassLoader = {
    val urls = getAddedJars :+ classDir.toUri.toURL
    val prefixes = SparkEnv.get.conf.get(CONNECT_SCALA_UDF_STUB_PREFIXES)
    val userClasspathFirst = SparkEnv.get.conf.get(EXECUTOR_USER_CLASS_PATH_FIRST)
    val loader = if (prefixes.nonEmpty) {
      // Two things you need to know about classloader for all of this to make sense:
      // 1. A classloader needs to be able to fully define a class.
      // 2. Classes are loaded lazily. Only when a class is used the classes it references are
      //    loaded.
      // This makes stubbing a bit more complicated then you'd expect. We cannot put the stubbing
      // classloader as a fallback at the end of the loading process, because then classes that
      // have been found in one of the parent classloaders and that contain a reference to a
      // missing, to-be-stubbed missing class will still fail with classloading errors later on.
      // The way we currently fix this is by making the stubbing class loader the last classloader
      // it delegates to.
      if (userClasspathFirst) {
        // USER -> SYSTEM -> STUB
        new ChildFirstURLClassLoader(
          urls.toArray,
          StubClassLoader(Utils.getContextOrSparkClassLoader, prefixes))
      } else {
        // SYSTEM -> USER -> STUB
        new ChildFirstURLClassLoader(
          urls.toArray,
          StubClassLoader(null, prefixes),
          Utils.getContextOrSparkClassLoader)
      }
    } else {
      if (userClasspathFirst) {
        new ChildFirstURLClassLoader(urls.toArray, Utils.getContextOrSparkClassLoader)
      } else {
        new URLClassLoader(urls.toArray, Utils.getContextOrSparkClassLoader)
      }
    }

    logDebug(s"Using class loader: $loader, containing urls: $urls")
    loader
  }

  /**
   * Cleans up all resources specific to this `session`.
   */
  private[sql] def cleanUpResources(): Unit = {
    logDebug(
      s"Cleaning up resources for session with sessionUUID ${session.sessionUUID}")

    // Clean up added files
    val fileserver = SparkEnv.get.rpcEnv.fileServer
    val sparkContext = session.sparkContext
    sparkContext.addedFiles.remove(state.uuid).foreach(_.keys.foreach(fileserver.removeFile))
    sparkContext.addedArchives.remove(state.uuid).foreach(_.keys.foreach(fileserver.removeFile))
    sparkContext.addedJars.remove(state.uuid).foreach(_.keys.foreach(fileserver.removeJar))

    // Clean up cached relations
    val blockManager = sparkContext.env.blockManager
    blockManager.removeCache(session.sessionUUID)

    // Clean up artifacts folder
    FileUtils.deleteDirectory(artifactPath.toFile)
  }

  def uploadArtifactToFs(
      remoteRelativePath: Path,
      serverLocalStagingPath: Path): Unit = {
    val hadoopConf = session.sparkContext.hadoopConfiguration
    assert(
      remoteRelativePath.startsWith(
        ArtifactManager.forwardToFSPrefix + File.separator))
    val destFSPath = new FSPath(
      Paths
        .get("/")
        .resolve(remoteRelativePath.subpath(1, remoteRelativePath.getNameCount))
        .toString)
    val localPath = serverLocalStagingPath
    val fs = destFSPath.getFileSystem(hadoopConf)
    if (fs.isInstanceOf[LocalFileSystem]) {
      val allowDestLocalConf =
        session.conf.get(SQLConf.ARTIFACT_COPY_FROM_LOCAL_TO_FS_ALLOW_DEST_LOCAL)
          .getOrElse(
            session.conf.get("spark.connect.copyFromLocalToFs.allowDestLocal").contains("true"))

      if (!allowDestLocalConf) {
        // To avoid security issue, by default,
        // we don't support uploading file to local file system
        // destination path, otherwise user is able to overwrite arbitrary file
        // on spark driver node.
        // We can temporarily allow the behavior by setting spark config
        // `spark.sql.artifact.copyFromLocalToFs.allowDestLocal`
        // to `true` when starting spark driver, we should only enable it for testing
        // purpose.
        throw new SparkUnsupportedOperationException("_LEGACY_ERROR_TEMP_3161")
      }
    }
    fs.copyFromLocalFile(false, true, new FSPath(localPath.toString), destFSPath)
  }
}

object ArtifactManager extends Logging {

  val forwardToFSPrefix = "forward_to_fs"

  val ARTIFACT_DIRECTORY_PREFIX = "artifacts"

  private[artifact] lazy val artifactRootDirectory =
    Utils.createTempDir(ARTIFACT_DIRECTORY_PREFIX).toPath
}
