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

package org.apache.spark.sql.internal

import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.annotation.concurrent.GuardedBy

import scala.collection.JavaConverters._
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FsUrlStreamHandlerFactory

import org.apache.spark.{SparkConf, SparkContext, SparkException}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.catalog._
import org.apache.spark.sql.execution.CacheManager
import org.apache.spark.sql.execution.streaming.StreamExecution
import org.apache.spark.sql.execution.ui.{SQLAppStatusListener, SQLAppStatusStore, SQLTab}
import org.apache.spark.sql.internal.StaticSQLConf._
import org.apache.spark.sql.streaming.StreamingQueryListener
import org.apache.spark.sql.streaming.ui.{StreamingQueryStatusListener, StreamingQueryTab}
import org.apache.spark.status.ElementTrackingStore
import org.apache.spark.util.Utils

/**
 * A class that holds all state shared across sessions in a given [[SQLContext]].
 *
 * @param sparkContext The Spark context associated with this SharedState
 * @param initialConfigs The configs from the very first created SparkSession
 */
private[sql] class SharedState(
    val sparkContext: SparkContext,
    initialConfigs: scala.collection.Map[String, String])
  extends Logging {

  SharedState.setFsUrlStreamHandlerFactory(sparkContext.conf, sparkContext.hadoopConfiguration)

  private val (conf, hadoopConf) = {
    // Load hive-site.xml into hadoopConf and determine the warehouse path which will be set into
    // both spark conf and hadoop conf avoiding be affected by any SparkSession level options
    SharedState.loadHiveConfFile(sparkContext.conf, sparkContext.hadoopConfiguration)
    val confClone = sparkContext.conf.clone()
    val hadoopConfClone = new Configuration(sparkContext.hadoopConfiguration)
    // If `SparkSession` is instantiated using an existing `SparkContext` instance and no existing
    // `SharedState`, all `SparkSession` level configurations have higher priority to generate a
    // `SharedState` instance. This will be done only once then shared across `SparkSession`s
    initialConfigs.foreach {
      case (k, _)  if k == "hive.metastore.warehouse.dir" || k == WAREHOUSE_PATH.key =>
        logWarning(s"Not allowing to set ${WAREHOUSE_PATH.key} or hive.metastore.warehouse.dir " +
          s"in SparkSession's options, it should be set statically for cross-session usages")
      case (k, v) =>
        logDebug(s"Applying initial SparkSession options to SparkConf/HadoopConf: $k -> $v")
        confClone.set(k, v)
        hadoopConfClone.set(k, v)

    }
    (confClone, hadoopConfClone)
  }

  /**
   * Class for caching query results reused in future executions.
   */
  val cacheManager: CacheManager = new CacheManager

  /** A global lock for all streaming query lifecycle tracking and management. */
  private[sql] val activeQueriesLock = new Object

  /**
   * A map of active streaming queries to the session specific StreamingQueryManager that manages
   * the lifecycle of that stream.
   */
  @GuardedBy("activeQueriesLock")
  private[sql] val activeStreamingQueries = new ConcurrentHashMap[UUID, StreamExecution]()

  /**
   * A status store to query SQL status/metrics of this Spark application, based on SQL-specific
   * [[org.apache.spark.scheduler.SparkListenerEvent]]s.
   */
  val statusStore: SQLAppStatusStore = {
    val kvStore = sparkContext.statusStore.store.asInstanceOf[ElementTrackingStore]
    val listener = new SQLAppStatusListener(conf, kvStore, live = true)
    sparkContext.listenerBus.addToStatusQueue(listener)
    val statusStore = new SQLAppStatusStore(kvStore, Some(listener))
    sparkContext.ui.foreach(new SQLTab(statusStore, _))
    statusStore
  }

  /**
   * A [[StreamingQueryListener]] for structured streaming ui, it contains all streaming query ui
   * data to show.
   */
  lazy val streamingQueryStatusListener: Option[StreamingQueryStatusListener] = {
    sparkContext.ui.flatMap { ui =>
      if (conf.get(STREAMING_UI_ENABLED)) {
        val statusListener = new StreamingQueryStatusListener(conf)
        new StreamingQueryTab(statusListener, ui)
        Some(statusListener)
      } else {
        None
      }
    }
  }

  /**
   * A catalog that interacts with external systems.
   */
  lazy val externalCatalog: ExternalCatalogWithListener = {
    val externalCatalog = SharedState.reflect[ExternalCatalog, SparkConf, Configuration](
      SharedState.externalCatalogClassName(conf), conf, hadoopConf)

    val defaultDbDefinition = CatalogDatabase(
      SessionCatalog.DEFAULT_DATABASE,
      "default database",
      CatalogUtils.stringToURI(conf.get(WAREHOUSE_PATH)),
      Map())
    // Create default database if it doesn't exist
    if (!externalCatalog.databaseExists(SessionCatalog.DEFAULT_DATABASE)) {
      // There may be another Spark application creating default database at the same time, here we
      // set `ignoreIfExists = true` to avoid `DatabaseAlreadyExists` exception.
      externalCatalog.createDatabase(defaultDbDefinition, ignoreIfExists = true)
    }

    // Wrap to provide catalog events
    val wrapped = new ExternalCatalogWithListener(externalCatalog)

    // Make sure we propagate external catalog events to the spark listener bus
    wrapped.addListener((event: ExternalCatalogEvent) => sparkContext.listenerBus.post(event))

    wrapped
  }

  /**
   * A manager for global temporary views.
   */
  lazy val globalTempViewManager: GlobalTempViewManager = {
    val globalTempDB = conf.get(GLOBAL_TEMP_DATABASE)
    if (externalCatalog.databaseExists(globalTempDB)) {
      throw new SparkException(
        s"$globalTempDB is a system preserved database, please rename your existing database " +
          "to resolve the name conflict, or set a different value for " +
          s"${GLOBAL_TEMP_DATABASE.key}, and launch your Spark application again.")
    }
    new GlobalTempViewManager(globalTempDB)
  }

  /**
   * A classloader used to load all user-added jar.
   */
  val jarClassLoader = new NonClosableMutableURLClassLoader(
    org.apache.spark.util.Utils.getContextOrSparkClassLoader)

}

object SharedState extends Logging {
  @volatile private var fsUrlStreamHandlerFactoryInitialized = false

  private def setFsUrlStreamHandlerFactory(conf: SparkConf, hadoopConf: Configuration): Unit = {
    if (!fsUrlStreamHandlerFactoryInitialized &&
        conf.get(DEFAULT_URL_STREAM_HANDLER_FACTORY_ENABLED)) {
      synchronized {
        if (!fsUrlStreamHandlerFactoryInitialized) {
          try {
            URL.setURLStreamHandlerFactory(new FsUrlStreamHandlerFactory(hadoopConf))
            fsUrlStreamHandlerFactoryInitialized = true
          } catch {
            case NonFatal(_) =>
              logWarning("URL.setURLStreamHandlerFactory failed to set FsUrlStreamHandlerFactory")
          }
        }
      }
    }
  }

  private val HIVE_EXTERNAL_CATALOG_CLASS_NAME = "org.apache.spark.sql.hive.HiveExternalCatalog"

  private def externalCatalogClassName(conf: SparkConf): String = {
    conf.get(CATALOG_IMPLEMENTATION) match {
      case "hive" => HIVE_EXTERNAL_CATALOG_CLASS_NAME
      case "in-memory" => classOf[InMemoryCatalog].getCanonicalName
    }
  }

  /**
   * Helper method to create an instance of [[T]] using a single-arg constructor that
   * accepts an [[Arg1]] and an [[Arg2]].
   */
  private def reflect[T, Arg1 <: AnyRef, Arg2 <: AnyRef](
      className: String,
      ctorArg1: Arg1,
      ctorArg2: Arg2)(
      implicit ctorArgTag1: ClassTag[Arg1],
      ctorArgTag2: ClassTag[Arg2]): T = {
    try {
      val clazz = Utils.classForName(className)
      val ctor = clazz.getDeclaredConstructor(ctorArgTag1.runtimeClass, ctorArgTag2.runtimeClass)
      val args = Array[AnyRef](ctorArg1, ctorArg2)
      ctor.newInstance(args: _*).asInstanceOf[T]
    } catch {
      case NonFatal(e) =>
        throw new IllegalArgumentException(s"Error while instantiating '$className':", e)
    }
  }

  /**
   * Load hive-site.xml into hadoopConf and determine the warehouse path we want to use, based on
   * the config from both hive and Spark SQL. Finally set the warehouse config value to sparkConf.
   */
  def loadHiveConfFile(
      sparkConf: SparkConf,
      hadoopConf: Configuration): Unit = {
    val hiveWarehouseKey = "hive.metastore.warehouse.dir"
    val configFile = Utils.getContextOrSparkClassLoader.getResource("hive-site.xml")
    if (configFile != null) {
      logInfo(s"loading hive config file: $configFile")
      val hadoopConfTemp = new Configuration()
      hadoopConfTemp.addResource(configFile)
      hadoopConfTemp.asScala.foreach { entry =>
        hadoopConf.setIfUnset(entry.getKey, entry.getValue)
      }
    }
    // hive.metastore.warehouse.dir only stay in hadoopConf
    sparkConf.remove(hiveWarehouseKey)
    // Set the Hive metastore warehouse path to the one we use
    val hiveWarehouseDir = hadoopConf.get(hiveWarehouseKey)
    val warehousePath = if (hiveWarehouseDir != null && !sparkConf.contains(WAREHOUSE_PATH.key)) {
      // If hive.metastore.warehouse.dir is set and spark.sql.warehouse.dir is not set,
      // we will respect the value of hive.metastore.warehouse.dir.
      sparkConf.set(WAREHOUSE_PATH.key, hiveWarehouseDir)
      logInfo(s"${WAREHOUSE_PATH.key} is not set, but $hiveWarehouseKey is set. Setting" +
        s" ${WAREHOUSE_PATH.key} to the value of $hiveWarehouseKey ('$hiveWarehouseDir').")
      hiveWarehouseDir
    } else {
      // If spark.sql.warehouse.dir is set, we will override hive.metastore.warehouse.dir using
      // the value of spark.sql.warehouse.dir.
      // When neither spark.sql.warehouse.dir nor hive.metastore.warehouse.dir is set
      // we will set hive.metastore.warehouse.dir to the default value of spark.sql.warehouse.dir.
      val sparkWarehouseDir = sparkConf.get(WAREHOUSE_PATH)
      logInfo(s"Setting $hiveWarehouseKey ('$hiveWarehouseDir') to the value of " +
        s"${WAREHOUSE_PATH.key} ('$sparkWarehouseDir').")
      hadoopConf.set(hiveWarehouseKey, sparkWarehouseDir)
      sparkWarehouseDir
    }
    logInfo(s"Warehouse path is '$warehousePath'.")
  }
}
