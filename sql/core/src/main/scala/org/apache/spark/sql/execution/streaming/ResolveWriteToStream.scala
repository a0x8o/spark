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

package org.apache.spark.sql.execution.streaming

import java.util.UUID

import scala.util.control.NonFatal

import org.apache.hadoop.fs.Path

import org.apache.spark.SparkException
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.SQLConfHelper
import org.apache.spark.sql.catalyst.analysis.UnsupportedOperationChecker
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.streaming.{WriteToStream, WriteToStreamStatement}
import org.apache.spark.sql.connector.catalog.SupportsWrite
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.util.Utils

/**
 * Replaces logical [[WriteToStreamStatement]] operator with an [[WriteToStream]] operator.
 */
object ResolveWriteToStream extends Rule[LogicalPlan] with SQLConfHelper {
  def apply(plan: LogicalPlan): LogicalPlan = plan.resolveOperators {
    case s: WriteToStreamStatement =>
      val (resolvedCheckpointLocation, deleteCheckpointOnStop) = resolveCheckpointLocation(s)

      if (conf.adaptiveExecutionEnabled) {
        logWarning(s"${SQLConf.ADAPTIVE_EXECUTION_ENABLED.key} " +
          "is not supported in streaming DataFrames/Datasets and will be disabled.")
      }

      if (conf.isUnsupportedOperationCheckEnabled) {
        if (s.sink.isInstanceOf[SupportsWrite] && s.isContinuousTrigger) {
          UnsupportedOperationChecker.checkForContinuous(s.inputQuery, s.outputMode)
        } else {
          UnsupportedOperationChecker.checkForStreaming(s.inputQuery, s.outputMode)
        }
      }

      WriteToStream(
        s.userSpecifiedName.orNull,
        resolvedCheckpointLocation,
        s.sink,
        s.outputMode,
        deleteCheckpointOnStop,
        s.inputQuery)
  }

  def resolveCheckpointLocation(s: WriteToStreamStatement): (String, Boolean) = {
    var deleteCheckpointOnStop = false
    val checkpointLocation = s.userSpecifiedCheckpointLocation.map { userSpecified =>
      new Path(userSpecified).toString
    }.orElse {
      conf.checkpointLocation.map { location =>
        new Path(location, s.userSpecifiedName.getOrElse(UUID.randomUUID().toString)).toString
      }
    }.getOrElse {
      if (s.useTempCheckpointLocation) {
        deleteCheckpointOnStop = true
        val tempDir = Utils.createTempDir(namePrefix = s"temporary").getCanonicalPath
        logWarning("Temporary checkpoint location created which is deleted normally when" +
          s" the query didn't fail: $tempDir. If it's required to delete it under any" +
          s" circumstances, please set ${SQLConf.FORCE_DELETE_TEMP_CHECKPOINT_LOCATION.key} to" +
          s" true. Important to know deleting temp checkpoint folder is best effort.")
        tempDir
      } else {
        throw new AnalysisException(
          "checkpointLocation must be specified either " +
            """through option("checkpointLocation", ...) or """ +
            s"""SparkSession.conf.set("${SQLConf.CHECKPOINT_LOCATION.key}", ...)""")
      }
    }
    // If offsets have already been created, we trying to resume a query.
    if (!s.recoverFromCheckpointLocation) {
      val checkpointPath = new Path(checkpointLocation, "offsets")
      val fs = checkpointPath.getFileSystem(s.hadoopConf)
      if (fs.exists(checkpointPath)) {
        throw new AnalysisException(
          s"This query does not support recovering from checkpoint location. " +
            s"Delete $checkpointPath to start over.")
      }
    }

    val resolvedCheckpointRoot = {
      val checkpointPath = new Path(checkpointLocation)
      val fs = checkpointPath.getFileSystem(s.hadoopConf)
      if (conf.getConf(SQLConf.STREAMING_CHECKPOINT_ESCAPED_PATH_CHECK_ENABLED)
        && StreamExecution.containsSpecialCharsInPath(checkpointPath)) {
        // In Spark 2.4 and earlier, the checkpoint path is escaped 3 times (3 `Path.toUri.toString`
        // calls). If this legacy checkpoint path exists, we will throw an error to tell the user
        // how to migrate.
        val legacyCheckpointDir =
        new Path(new Path(checkpointPath.toUri.toString).toUri.toString).toUri.toString
        val legacyCheckpointDirExists =
          try {
            fs.exists(new Path(legacyCheckpointDir))
          } catch {
            case NonFatal(e) =>
              // We may not have access to this directory. Don't fail the query if that happens.
              logWarning(e.getMessage, e)
              false
          }
        if (legacyCheckpointDirExists) {
          throw new SparkException(
            s"""Error: we detected a possible problem with the location of your checkpoint and you
               |likely need to move it before restarting this query.
               |
               |Earlier version of Spark incorrectly escaped paths when writing out checkpoints for
               |structured streaming. While this was corrected in Spark 3.0, it appears that your
               |query was started using an earlier version that incorrectly handled the checkpoint
               |path.
               |
               |Correct Checkpoint Directory: $checkpointPath
               |Incorrect Checkpoint Directory: $legacyCheckpointDir
               |
               |Please move the data from the incorrect directory to the correct one, delete the
               |incorrect directory, and then restart this query. If you believe you are receiving
               |this message in error, you can disable it with the SQL conf
               |${SQLConf.STREAMING_CHECKPOINT_ESCAPED_PATH_CHECK_ENABLED.key}."""
              .stripMargin)
        }
      }
      val checkpointDir = checkpointPath.makeQualified(fs.getUri, fs.getWorkingDirectory)
      fs.mkdirs(checkpointDir)
      checkpointDir.toString
    }
    logInfo(s"Checkpoint root $checkpointLocation resolved to $resolvedCheckpointRoot.")
    (resolvedCheckpointRoot, deleteCheckpointOnStop)
  }
}

