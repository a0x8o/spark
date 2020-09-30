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

package org.apache.spark.util

import java.io.FileNotFoundException

import scala.collection.mutable

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs._
import org.apache.hadoop.fs.viewfs.ViewFileSystem
import org.apache.hadoop.hdfs.DistributedFileSystem

import org.apache.spark._
import org.apache.spark.annotation.Private
import org.apache.spark.internal.Logging
import org.apache.spark.metrics.source.HiveCatalogMetrics

/**
 * Utility functions to simplify and speed-up file listing.
 */
private[spark] object HadoopFSUtils extends Logging {
  /**
   * Lists a collection of paths recursively. Picks the listing strategy adaptively depending
   * on the number of paths to list.
   *
   * This may only be called on the driver.
   *
   * @param sc Spark context used to run parallel listing.
   * @param paths Input paths to list
   * @param hadoopConf Hadoop configuration
   * @param filter Path filter used to exclude leaf files from result
   * @param isRootLevel Whether the input paths are at the root level, i.e., they are the root
   *                    paths as opposed to nested paths encountered during recursive calls of this.
   * @param ignoreMissingFiles Ignore missing files that happen during recursive listing
   *                           (e.g., due to race conditions)
   * @param ignoreLocality Whether to fetch data locality info when listing leaf files. If false,
   *                       this will return `FileStatus` without `BlockLocation` info.
   * @param parallelismThreshold The threshold to enable parallelism. If the number of input paths
   *                             is smaller than this value, this will fallback to use
   *                             sequential listing.
   * @param parallelismMax The maximum parallelism for listing. If the number of input paths is
   *                       larger than this value, parallelism will be throttled to this value
   *                       to avoid generating too many tasks.
   * @param filterFun Optional predicate on the leaf files. Files who failed the check will be
   *                  excluded from the results
   * @return for each input path, the set of discovered files for the path
   */
  def parallelListLeafFiles(
      sc: SparkContext,
      paths: Seq[Path],
      hadoopConf: Configuration,
      filter: PathFilter,
      isRootLevel: Boolean,
      ignoreMissingFiles: Boolean,
      ignoreLocality: Boolean,
      parallelismThreshold: Int,
      parallelismMax: Int,
      filterFun: Option[String => Boolean] = None): Seq[(Path, Seq[FileStatus])] = {

    // Short-circuits parallel listing when serial listing is likely to be faster.
    if (paths.size <= parallelismThreshold) {
      return paths.map { path =>
        val leafFiles = listLeafFiles(
          path,
          hadoopConf,
          filter,
          Some(sc),
          ignoreMissingFiles = ignoreMissingFiles,
          ignoreLocality = ignoreLocality,
          isRootPath = isRootLevel,
          parallelismThreshold = parallelismThreshold,
          parallelismMax = parallelismMax,
          filterFun = filterFun)
        (path, leafFiles)
      }
    }

    logInfo(s"Listing leaf files and directories in parallel under ${paths.length} paths." +
      s" The first several paths are: ${paths.take(10).mkString(", ")}.")
    HiveCatalogMetrics.incrementParallelListingJobCount(1)

    val serializableConfiguration = new SerializableConfiguration(hadoopConf)
    val serializedPaths = paths.map(_.toString)

    // Set the number of parallelism to prevent following file listing from generating many tasks
    // in case of large #defaultParallelism.
    val numParallelism = Math.min(paths.size, parallelismMax)

    val previousJobDescription = sc.getLocalProperty(SparkContext.SPARK_JOB_DESCRIPTION)
    val statusMap = try {
      val description = paths.size match {
        case 0 =>
          "Listing leaf files and directories 0 paths"
        case 1 =>
          s"Listing leaf files and directories for 1 path:<br/>${paths(0)}"
        case s =>
          s"Listing leaf files and directories for $s paths:<br/>${paths(0)}, ..."
      }
      sc.setJobDescription(description)
      sc
        .parallelize(serializedPaths, numParallelism)
        .mapPartitions { pathStrings =>
          val hadoopConf = serializableConfiguration.value
          pathStrings.map(new Path(_)).toSeq.map { path =>
            val leafFiles = listLeafFiles(
              path = path,
              hadoopConf = hadoopConf,
              filter = filter,
              contextOpt = None, // Can't execute parallel scans on workers
              ignoreMissingFiles = ignoreMissingFiles,
              ignoreLocality = ignoreLocality,
              isRootPath = isRootLevel,
              filterFun = filterFun,
              parallelismThreshold = Int.MaxValue,
              parallelismMax = 0)
            (path, leafFiles)
          }.iterator
        }.map { case (path, statuses) =>
            val serializableStatuses = statuses.map { status =>
              // Turn FileStatus into SerializableFileStatus so we can send it back to the driver
              val blockLocations = status match {
                case f: LocatedFileStatus =>
                  f.getBlockLocations.map { loc =>
                    SerializableBlockLocation(
                      loc.getNames,
                      loc.getHosts,
                      loc.getOffset,
                      loc.getLength)
                  }

                case _ =>
                  Array.empty[SerializableBlockLocation]
              }

              SerializableFileStatus(
                status.getPath.toString,
                status.getLen,
                status.isDirectory,
                status.getReplication,
                status.getBlockSize,
                status.getModificationTime,
                status.getAccessTime,
                blockLocations)
            }
            (path.toString, serializableStatuses)
        }.collect()
    } finally {
      sc.setJobDescription(previousJobDescription)
    }

    // turn SerializableFileStatus back to Status
    statusMap.map { case (path, serializableStatuses) =>
      val statuses = serializableStatuses.map { f =>
        val blockLocations = f.blockLocations.map { loc =>
          new BlockLocation(loc.names, loc.hosts, loc.offset, loc.length)
        }
        new LocatedFileStatus(
          new FileStatus(
            f.length, f.isDir, f.blockReplication, f.blockSize, f.modificationTime,
            new Path(f.path)),
          blockLocations)
      }
      (new Path(path), statuses)
    }
  }

  // scalastyle:off argcount
  /**
   * Lists a single filesystem path recursively. If a `SparkContext` object is specified, this
   * function may launch Spark jobs to parallelize listing based on `parallelismThreshold`.
   *
   * If sessionOpt is None, this may be called on executors.
   *
   * @return all children of path that match the specified filter.
   */
  private def listLeafFiles(
      path: Path,
      hadoopConf: Configuration,
      filter: PathFilter,
      contextOpt: Option[SparkContext],
      ignoreMissingFiles: Boolean,
      ignoreLocality: Boolean,
      isRootPath: Boolean,
      filterFun: Option[String => Boolean],
      parallelismThreshold: Int,
      parallelismMax: Int): Seq[FileStatus] = {

    logTrace(s"Listing $path")
    val fs = path.getFileSystem(hadoopConf)

    // Note that statuses only include FileStatus for the files and dirs directly under path,
    // and does not include anything else recursively.
    val statuses: Array[FileStatus] = try {
      fs match {
        // DistributedFileSystem overrides listLocatedStatus to make 1 single call to namenode
        // to retrieve the file status with the file block location. The reason to still fallback
        // to listStatus is because the default implementation would potentially throw a
        // FileNotFoundException which is better handled by doing the lookups manually below.
        case (_: DistributedFileSystem | _: ViewFileSystem) if !ignoreLocality =>
          val remoteIter = fs.listLocatedStatus(path)
          new Iterator[LocatedFileStatus]() {
            def next(): LocatedFileStatus = remoteIter.next
            def hasNext(): Boolean = remoteIter.hasNext
          }.toArray
        case _ => fs.listStatus(path)
      }
    } catch {
      // If we are listing a root path for SQL (e.g. a top level directory of a table), we need to
      // ignore FileNotFoundExceptions during this root level of the listing because
      //
      //  (a) certain code paths might construct an InMemoryFileIndex with root paths that
      //      might not exist (i.e. not all callers are guaranteed to have checked
      //      path existence prior to constructing InMemoryFileIndex) and,
      //  (b) we need to ignore deleted root paths during REFRESH TABLE, otherwise we break
      //      existing behavior and break the ability drop SessionCatalog tables when tables'
      //      root directories have been deleted (which breaks a number of Spark's own tests).
      //
      // If we are NOT listing a root path then a FileNotFoundException here means that the
      // directory was present in a previous level of file listing but is absent in this
      // listing, likely indicating a race condition (e.g. concurrent table overwrite or S3
      // list inconsistency).
      //
      // The trade-off in supporting existing behaviors / use-cases is that we won't be
      // able to detect race conditions involving root paths being deleted during
      // InMemoryFileIndex construction. However, it's still a net improvement to detect and
      // fail-fast on the non-root cases. For more info see the SPARK-27676 review discussion.
      case _: FileNotFoundException if isRootPath || ignoreMissingFiles =>
        logWarning(s"The directory $path was not found. Was it deleted very recently?")
        Array.empty[FileStatus]
    }

    def doFilter(statuses: Array[FileStatus]) = filterFun match {
      case Some(shouldFilterOut) =>
        statuses.filterNot(status => shouldFilterOut(status.getPath.getName))
      case None =>
        statuses
    }

    val filteredStatuses = doFilter(statuses)
    val allLeafStatuses = {
      val (dirs, topLevelFiles) = filteredStatuses.partition(_.isDirectory)
      val nestedFiles: Seq[FileStatus] = contextOpt match {
        case Some(context) if dirs.size > parallelismThreshold =>
          parallelListLeafFiles(
            context,
            dirs.map(_.getPath),
            hadoopConf = hadoopConf,
            filter = filter,
            isRootLevel = false,
            ignoreMissingFiles = ignoreMissingFiles,
            ignoreLocality = ignoreLocality,
            filterFun = filterFun,
            parallelismThreshold = parallelismThreshold,
            parallelismMax = parallelismMax
          ).flatMap(_._2)
        case _ =>
          dirs.flatMap { dir =>
            listLeafFiles(
              path = dir.getPath,
              hadoopConf = hadoopConf,
              filter = filter,
              contextOpt = contextOpt,
              ignoreMissingFiles = ignoreMissingFiles,
              ignoreLocality = ignoreLocality,
              isRootPath = false,
              filterFun = filterFun,
              parallelismThreshold = parallelismThreshold,
              parallelismMax = parallelismMax)
          }
      }
      val allFiles = topLevelFiles ++ nestedFiles
      if (filter != null) allFiles.filter(f => filter.accept(f.getPath)) else allFiles
    }

    val missingFiles = mutable.ArrayBuffer.empty[String]
    val filteredLeafStatuses = doFilter(allLeafStatuses)
    val resolvedLeafStatuses = filteredLeafStatuses.flatMap {
      case f: LocatedFileStatus =>
        Some(f)

      // NOTE:
      //
      // - Although S3/S3A/S3N file system can be quite slow for remote file metadata
      //   operations, calling `getFileBlockLocations` does no harm here since these file system
      //   implementations don't actually issue RPC for this method.
      //
      // - Here we are calling `getFileBlockLocations` in a sequential manner, but it should not
      //   be a big deal since we always use to `parallelListLeafFiles` when the number of
      //   paths exceeds threshold.
      case f if !ignoreLocality =>
        // The other constructor of LocatedFileStatus will call FileStatus.getPermission(),
        // which is very slow on some file system (RawLocalFileSystem, which is launch a
        // subprocess and parse the stdout).
        try {
          val locations = fs.getFileBlockLocations(f, 0, f.getLen).map { loc =>
            // Store BlockLocation objects to consume less memory
            if (loc.getClass == classOf[BlockLocation]) {
              loc
            } else {
              new BlockLocation(loc.getNames, loc.getHosts, loc.getOffset, loc.getLength)
            }
          }
          val lfs = new LocatedFileStatus(f.getLen, f.isDirectory, f.getReplication, f.getBlockSize,
            f.getModificationTime, 0, null, null, null, null, f.getPath, locations)
          if (f.isSymlink) {
            lfs.setSymlink(f.getSymlink)
          }
          Some(lfs)
        } catch {
          case _: FileNotFoundException if ignoreMissingFiles =>
            missingFiles += f.getPath.toString
            None
        }

      case f => Some(f)
    }

    if (missingFiles.nonEmpty) {
      logWarning(
        s"the following files were missing during file scan:\n  ${missingFiles.mkString("\n  ")}")
    }

    resolvedLeafStatuses
  }
  // scalastyle:on argcount

  /** A serializable variant of HDFS's BlockLocation. */
  private case class SerializableBlockLocation(
    names: Array[String],
    hosts: Array[String],
    offset: Long,
    length: Long)

  /** A serializable variant of HDFS's FileStatus. */
  private case class SerializableFileStatus(
    path: String,
    length: Long,
    isDir: Boolean,
    blockReplication: Short,
    blockSize: Long,
    modificationTime: Long,
    accessTime: Long,
    blockLocations: Array[SerializableBlockLocation])
}
