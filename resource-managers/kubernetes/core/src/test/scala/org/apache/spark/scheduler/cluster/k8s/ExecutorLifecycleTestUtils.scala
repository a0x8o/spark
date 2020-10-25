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
package org.apache.spark.scheduler.cluster.k8s

import java.time.Instant

import io.fabric8.kubernetes.api.model.{ContainerBuilder, Pod, PodBuilder}

import org.apache.spark.deploy.k8s.Constants._
import org.apache.spark.deploy.k8s.SparkPod

object ExecutorLifecycleTestUtils {

  val TEST_SPARK_APP_ID = "spark-app-id"

  def failedExecutorWithoutDeletion(executorId: Long): Pod = {
    new PodBuilder(podWithAttachedContainerForId(executorId))
      .editOrNewStatus()
        .withPhase("failed")
        .withStartTime(Instant.now.toString)
        .addNewContainerStatus()
          .withName("spark-executor")
          .withImage("k8s-spark")
          .withNewState()
            .withNewTerminated()
              .withMessage("Failed")
              .withExitCode(1)
              .endTerminated()
            .endState()
          .endContainerStatus()
        .addNewContainerStatus()
          .withName("spark-executor-sidecar")
          .withImage("k8s-spark-sidecar")
          .withNewState()
            .withNewTerminated()
              .withMessage("Failed")
              .withExitCode(1)
              .endTerminated()
            .endState()
          .endContainerStatus()
        .withMessage("Executor failed.")
        .withReason("Executor failed because of a thrown error.")
        .endStatus()
      .build()
  }

  def pendingExecutor(executorId: Long): Pod = {
    new PodBuilder(podWithAttachedContainerForId(executorId))
      .editOrNewStatus()
        .withPhase("pending")
        .withStartTime(Instant.now.toString)
        .endStatus()
      .build()
  }

  def runningExecutor(executorId: Long): Pod = {
    new PodBuilder(podWithAttachedContainerForId(executorId))
      .editOrNewStatus()
        .withPhase("running")
        .withStartTime(Instant.now.toString)
        .endStatus()
      .build()
  }

  /**
   * [SPARK-30821]
   * This creates a pod with one container in running state and one container in failed
   * state (terminated with non-zero exit code). This pod is used for unit-testing the
   * spark.kubernetes.executor.checkAllContainers Spark Conf.
   */
  def runningExecutorWithFailedContainer(executorId: Long): Pod = {
    new PodBuilder(podWithAttachedContainerForId(executorId))
      .editOrNewStatus()
        .withPhase("running")
        .addNewContainerStatus()
          .withNewState()
            .withNewTerminated()
              .withExitCode(1)
            .endTerminated()
          .endState()
        .endContainerStatus()
        .addNewContainerStatus()
          .withNewState()
            .withNewRunning()
            .endRunning()
          .endState()
        .endContainerStatus()
      .endStatus()
      .build()
  }

  def succeededExecutor(executorId: Long): Pod = {
    new PodBuilder(podWithAttachedContainerForId(executorId))
      .editOrNewStatus()
        .withPhase("succeeded")
        .endStatus()
      .build()
  }

  def deletedExecutor(executorId: Long): Pod = {
    new PodBuilder(podWithAttachedContainerForId(executorId))
      .editOrNewMetadata()
        .withDeletionTimestamp("523012521")
        .endMetadata()
      .build()
  }

  def unknownExecutor(executorId: Long): Pod = {
    new PodBuilder(podWithAttachedContainerForId(executorId))
      .editOrNewStatus()
        .withPhase("unknown")
        .endStatus()
      .build()
  }

  def podWithAttachedContainerForId(executorId: Long): Pod = {
    val sparkPod = executorPodWithId(executorId)
    val podWithAttachedContainer = new PodBuilder(sparkPod.pod)
      .editOrNewSpec()
        .addToContainers(sparkPod.container)
        .endSpec()
      .build()
    podWithAttachedContainer
  }

  def executorPodWithId(executorId: Long): SparkPod = {
    val pod = new PodBuilder()
      .withNewMetadata()
        .withName(s"spark-executor-$executorId")
        .addToLabels(SPARK_APP_ID_LABEL, TEST_SPARK_APP_ID)
        .addToLabels(SPARK_ROLE_LABEL, SPARK_POD_EXECUTOR_ROLE)
        .addToLabels(SPARK_EXECUTOR_ID_LABEL, executorId.toString)
      .endMetadata()
      .editOrNewSpec()
        .withRestartPolicy("Never")
      .endSpec()
      .build()
    val container = new ContainerBuilder()
      .withName("spark-executor")
      .withImage("k8s-spark")
      .build()
    SparkPod(pod, container)
  }
}
