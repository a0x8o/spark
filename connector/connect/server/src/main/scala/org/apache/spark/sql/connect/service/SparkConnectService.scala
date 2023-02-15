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

package org.apache.spark.sql.connect.service

import java.util.concurrent.TimeUnit

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import com.google.common.base.Ticker
import com.google.common.cache.CacheBuilder
import com.google.protobuf.{Any => ProtoAny}
import com.google.rpc.{Code => RPCCode, ErrorInfo, Status => RPCStatus}
import io.grpc.{Server, Status}
import io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.StatusProto
import io.grpc.protobuf.services.ProtoReflectionService
import io.grpc.stub.StreamObserver
import org.apache.commons.lang3.StringUtils
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods.{compact, render}

import org.apache.spark.{SparkEnv, SparkException, SparkThrowable}
import org.apache.spark.api.python.PythonException
import org.apache.spark.connect.proto
import org.apache.spark.connect.proto.{AnalyzePlanRequest, AnalyzePlanResponse, ExecutePlanRequest, ExecutePlanResponse, SparkConnectServiceGrpc}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{Dataset, SparkSession}
import org.apache.spark.sql.connect.common.DataTypeProtoConverter
import org.apache.spark.sql.connect.config.Connect.CONNECT_GRPC_BINDING_PORT
import org.apache.spark.sql.connect.planner.SparkConnectPlanner
import org.apache.spark.sql.execution.{CodegenMode, CostMode, ExplainMode, ExtendedMode, FormattedMode, SimpleMode}

/**
 * The SparkConnectService implementation.
 *
 * This class implements the service stub from the generated code of GRPC.
 *
 * @param debug
 *   delegates debug behavior to the handlers.
 */
class SparkConnectService(debug: Boolean)
    extends SparkConnectServiceGrpc.SparkConnectServiceImplBase
    with Logging {

  private def allClasses(cl: Class[_]): Seq[Class[_]] = {
    val classes = ArrayBuffer.empty[Class[_]]
    if (cl != null && !cl.equals(classOf[java.lang.Object])) {
      classes.append(cl) // Includes itself.
    }

    @tailrec
    def appendSuperClasses(clazz: Class[_]): Unit = {
      if (clazz == null || clazz.equals(classOf[java.lang.Object])) return
      classes.append(clazz.getSuperclass)
      appendSuperClasses(clazz.getSuperclass)
    }

    appendSuperClasses(cl)
    classes.toSeq
  }

  private def buildStatusFromThrowable(st: Throwable): RPCStatus = {
    RPCStatus
      .newBuilder()
      .setCode(RPCCode.INTERNAL_VALUE)
      .addDetails(
        ProtoAny.pack(
          ErrorInfo
            .newBuilder()
            .setReason(st.getClass.getName)
            .setDomain("org.apache.spark")
            .putMetadata("classes", compact(render(allClasses(st.getClass).map(_.getName))))
            .build()))
      .setMessage(StringUtils.abbreviate(st.getMessage, 2048))
      .build()
  }

  private def isPythonExecutionException(se: SparkException): Boolean = {
    // See also pyspark.errors.exceptions.captured.convert_exception in PySpark.
    se.getCause != null && se.getCause
      .isInstanceOf[PythonException] && se.getCause.getStackTrace
      .exists(_.toString.contains("org.apache.spark.sql.execution.python"))
  }

  /**
   * Common exception handling function for the Analysis and Execution methods. Closes the stream
   * after the error has been sent.
   *
   * @param opType
   *   String value indicating the operation type (analysis, execution)
   * @param observer
   *   The GRPC response observer.
   * @tparam V
   * @return
   */
  private def handleError[V](
      opType: String,
      observer: StreamObserver[V]): PartialFunction[Throwable, Unit] = {
    case se: SparkException if isPythonExecutionException(se) =>
      logError(s"Error during: $opType", se)
      observer.onError(
        StatusProto.toStatusRuntimeException(buildStatusFromThrowable(se.getCause)))

    case e: Throwable if e.isInstanceOf[SparkThrowable] || NonFatal.apply(e) =>
      logError(s"Error during: $opType", e)
      observer.onError(StatusProto.toStatusRuntimeException(buildStatusFromThrowable(e)))

    case e: Throwable =>
      logError(s"Error during: $opType", e)
      observer.onError(
        Status.UNKNOWN
          .withCause(e)
          .withDescription(StringUtils.abbreviate(e.getMessage, 2048))
          .asRuntimeException())
  }

  /**
   * This is the main entry method for Spark Connect and all calls to execute a plan.
   *
   * The plan execution is delegated to the [[SparkConnectStreamHandler]]. All error handling
   * should be directly implemented in the deferred implementation. But this method catches
   * generic errors.
   *
   * @param request
   * @param responseObserver
   */
  override def executePlan(
      request: ExecutePlanRequest,
      responseObserver: StreamObserver[ExecutePlanResponse]): Unit = {
    try {
      new SparkConnectStreamHandler(responseObserver).handle(request)
    } catch handleError("execute", observer = responseObserver)
  }

  /**
   * Analyze a plan to provide metadata and debugging information.
   *
   * This method is called to generate the explain plan for a SparkConnect plan. In its simplest
   * implementation, the plan that is generated by the [[SparkConnectPlanner]] is used to build a
   * [[Dataset]] and derive the explain string from the query execution details.
   *
   * Errors during planning are returned via the [[StreamObserver]] interface.
   *
   * @param request
   * @param responseObserver
   */
  override def analyzePlan(
      request: AnalyzePlanRequest,
      responseObserver: StreamObserver[AnalyzePlanResponse]): Unit = {
    try {
      if (request.getPlan.getOpTypeCase != proto.Plan.OpTypeCase.ROOT) {
        responseObserver.onError(
          new UnsupportedOperationException(
            s"${request.getPlan.getOpTypeCase} not supported for analysis."))
      }
      val session =
        SparkConnectService
          .getOrCreateIsolatedSession(request.getUserContext.getUserId, request.getClientId)
          .session

      val explainMode = request.getExplain.getExplainMode match {
        case proto.Explain.ExplainMode.SIMPLE => SimpleMode
        case proto.Explain.ExplainMode.EXTENDED => ExtendedMode
        case proto.Explain.ExplainMode.CODEGEN => CodegenMode
        case proto.Explain.ExplainMode.COST => CostMode
        case proto.Explain.ExplainMode.FORMATTED => FormattedMode
        case _ =>
          throw new IllegalArgumentException(
            s"Explain mode unspecified. Accepted " +
              "explain modes are 'simple', 'extended', 'codegen', 'cost', 'formatted'.")
      }

      val response = handleAnalyzePlanRequest(request.getPlan.getRoot, session, explainMode)
      response.setClientId(request.getClientId)
      responseObserver.onNext(response.build())
      responseObserver.onCompleted()
    } catch handleError("analyze", observer = responseObserver)
  }

  def handleAnalyzePlanRequest(
      relation: proto.Relation,
      session: SparkSession,
      explainMode: ExplainMode): proto.AnalyzePlanResponse.Builder = {
    val logicalPlan = new SparkConnectPlanner(session).transformRelation(relation)

    val ds = Dataset.ofRows(session, logicalPlan)
    val explainString = ds.queryExecution.explainString(explainMode)

    val response = proto.AnalyzePlanResponse.newBuilder()
    response.setSchema(DataTypeProtoConverter.toConnectProtoType(ds.schema))
    response.setExplainString(explainString)
    response.setTreeString(ds.schema.treeString)
    response.setIsLocal(ds.isLocal)
    response.setIsStreaming(ds.isStreaming)
    response.addAllInputFiles(ds.inputFiles.toSeq.asJava)
  }
}

/**
 * Object used for referring to SparkSessions in the SessionCache.
 *
 * @param userId
 * @param session
 */
case class SessionHolder(userId: String, sessionId: String, session: SparkSession)

/**
 * Static instance of the SparkConnectService.
 *
 * Used to start the overall SparkConnect service and provides global state to manage the
 * different SparkSession from different users connecting to the cluster.
 */
object SparkConnectService {

  private val CACHE_SIZE = 100

  private val CACHE_TIMEOUT_SECONDS = 3600

  // Type alias for the SessionCacheKey. Right now this is a String but allows us to switch to a
  // different or complex type easily.
  private type SessionCacheKey = (String, String)

  private[connect] var server: Server = _

  // For testing purpose, it's package level private.
  private[connect] lazy val localPort = {
    assert(server != null)
    // Return the actual local port being used. This can be different from the csonfigured port
    // when the server binds to the port 0 as an example.
    server.getPort
  }

  private val userSessionMapping =
    cacheBuilder(CACHE_SIZE, CACHE_TIMEOUT_SECONDS).build[SessionCacheKey, SessionHolder]()

  // Simple builder for creating the cache of Sessions.
  private def cacheBuilder(cacheSize: Int, timeoutSeconds: Int): CacheBuilder[Object, Object] = {
    var cacheBuilder = CacheBuilder.newBuilder().ticker(Ticker.systemTicker())
    if (cacheSize >= 0) {
      cacheBuilder = cacheBuilder.maximumSize(cacheSize)
    }
    if (timeoutSeconds >= 0) {
      cacheBuilder.expireAfterAccess(timeoutSeconds, TimeUnit.SECONDS)
    }
    cacheBuilder
  }

  /**
   * Based on the `key` find or create a new SparkSession.
   */
  def getOrCreateIsolatedSession(userId: String, sessionId: String): SessionHolder = {
    userSessionMapping.get(
      (userId, sessionId),
      () => {
        SessionHolder(userId, sessionId, newIsolatedSession())
      })
  }

  private def newIsolatedSession(): SparkSession = {
    SparkSession.active.newSession()
  }

  /**
   * Starts the GRPC Serivce.
   */
  private def startGRPCService(): Unit = {
    val debugMode = SparkEnv.get.conf.getBoolean("spark.connect.grpc.debug.enabled", true)
    val port = SparkEnv.get.conf.get(CONNECT_GRPC_BINDING_PORT)
    val sb = NettyServerBuilder
      .forPort(port)
      .addService(new SparkConnectService(debugMode))

    // Add all registered interceptors to the server builder.
    SparkConnectInterceptorRegistry.chainInterceptors(sb)

    // If debug mode is configured, load the ProtoReflection service so that tools like
    // grpcurl can introspect the API for debugging.
    if (debugMode) {
      sb.addService(ProtoReflectionService.newInstance())
    }
    server = sb.build
    server.start()
  }

  // Starts the service
  def start(): Unit = {
    startGRPCService()
  }

  def stop(timeout: Option[Long] = None, unit: Option[TimeUnit] = None): Unit = {
    if (server != null) {
      if (timeout.isDefined && unit.isDefined) {
        server.shutdown()
        server.awaitTermination(timeout.get, unit.get)
      } else {
        server.shutdownNow()
      }
    }
  }
}
