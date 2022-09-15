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

package org.apache.spark

import java.io.FileNotFoundException
import java.sql.{SQLException, SQLFeatureNotSupportedException}
import java.time.DateTimeException
import java.util.ConcurrentModificationException

import scala.collection.JavaConverters._

import org.apache.hadoop.fs.FileAlreadyExistsException

class SparkException(
    message: String,
    cause: Throwable,
    errorClass: Option[String],
    errorSubClass: Option[String],
    messageParameters: Map[String, String],
    context: Array[QueryContext] = Array.empty)
  extends Exception(message, cause) with SparkThrowable {

   def this(
       message: String,
       cause: Throwable,
       errorClass: Option[String],
       messageParameters: Map[String, String]) =
     this(
       message = message,
       cause = cause,
       errorClass = errorClass,
       errorSubClass = None,
       messageParameters = messageParameters)

  def this(message: String, cause: Throwable) =
    this(message = message, cause = cause, errorClass = None, errorSubClass = None,
      messageParameters = Map.empty)

  def this(message: String) =
    this(message = message, cause = null)

  def this(
      errorClass: String,
      messageParameters: Map[String, String],
      cause: Throwable,
      context: Array[QueryContext],
      summary: String) =
    this(
      message = SparkThrowableHelper.getMessage(errorClass, null, messageParameters, summary),
      cause = cause,
      errorClass = Some(errorClass),
      errorSubClass = None,
      messageParameters = messageParameters,
      context)

  def this(errorClass: String, messageParameters: Map[String, String], cause: Throwable) =
    this(
      message = SparkThrowableHelper.getMessage(errorClass, null, messageParameters),
      cause = cause,
      errorClass = Some(errorClass),
      errorSubClass = None,
      messageParameters = messageParameters)

  def this(
      errorClass: String,
      errorSubClass: String,
      messageParameters: Map[String, String],
      cause: Throwable) =
    this(
      message = SparkThrowableHelper.getMessage(errorClass, errorSubClass, messageParameters),
      cause = cause,
      errorClass = Some(errorClass),
      errorSubClass = Some(errorSubClass),
      messageParameters = messageParameters)

  override def getMessageParameters: java.util.Map[String, String] = messageParameters.asJava

  override def getErrorClass: String = errorClass.orNull
  override def getErrorSubClass: String = errorSubClass.orNull
  override def getQueryContext: Array[QueryContext] = context
}

object SparkException {
  def internalError(msg: String): SparkException = {
    new SparkException(
      errorClass = "INTERNAL_ERROR",
      messageParameters = Map("message" -> msg),
      cause = null)
  }

  def internalError(msg: String, cause: Throwable): SparkException = {
    new SparkException(
      errorClass = "INTERNAL_ERROR",
      messageParameters = Map("message" -> msg),
      cause = cause)
  }
}

/**
 * Exception thrown when execution of some user code in the driver process fails, e.g.
 * accumulator update fails or failure in takeOrdered (user supplies an Ordering implementation
 * that can be misbehaving.
 */
private[spark] class SparkDriverExecutionException(cause: Throwable)
  extends SparkException("Execution error", cause)

/**
 * Exception thrown when the main user code is run as a child process (e.g. pyspark) and we want
 * the parent SparkSubmit process to exit with the same exit code.
 */
private[spark] case class SparkUserAppException(exitCode: Int)
  extends SparkException(s"User application exited with $exitCode")

/**
 * Exception thrown when the relative executor to access is dead.
 */
private[spark] case class ExecutorDeadException(message: String)
  extends SparkException(message)

/**
 * Exception thrown when Spark returns different result after upgrading to a new version.
 */
private[spark] class SparkUpgradeException(
    errorClass: String,
    errorSubClass: Option[String] = None,
    messageParameters: Map[String, String],
    cause: Throwable)
  extends RuntimeException(
    SparkThrowableHelper.getMessage(errorClass, errorSubClass.orNull, messageParameters), cause)
  with SparkThrowable {

  override def getMessageParameters: java.util.Map[String, String] = messageParameters.asJava

  override def getErrorClass: String = errorClass
  override def getErrorSubClass: String = errorSubClass.orNull}

/**
 * Arithmetic exception thrown from Spark with an error class.
 */
private[spark] class SparkArithmeticException(
    errorClass: String,
    errorSubClass: Option[String] = None,
    messageParameters: Map[String, String],
    context: Array[QueryContext],
    summary: String)
  extends ArithmeticException(
    SparkThrowableHelper.getMessage(errorClass, errorSubClass.orNull, messageParameters, summary))
  with SparkThrowable {

  override def getMessageParameters: java.util.Map[String, String] = messageParameters.asJava

  override def getErrorClass: String = errorClass
  override def getErrorSubClass: String = errorSubClass.orNull
  override def getQueryContext: Array[QueryContext] = context
}

/**
 * Unsupported operation exception thrown from Spark with an error class.
 */
private[spark] class SparkUnsupportedOperationException(
    errorClass: String,
    errorSubClass: Option[String] = None,
    messageParameters: Map[String, String])
  extends UnsupportedOperationException(
    SparkThrowableHelper.getMessage(errorClass, errorSubClass.orNull, messageParameters))
  with SparkThrowable {

  def this(
      errorClass: String,
      errorSubClass: String,
      messageParameters: Map[String, String]) =
    this(
      errorClass = errorClass,
      errorSubClass = Some(errorSubClass),
      messageParameters = messageParameters)

  override def getMessageParameters: java.util.Map[String, String] = messageParameters.asJava

  override def getErrorClass: String = errorClass
  override def getErrorSubClass: String = errorSubClass.orNull
}

/**
 * Class not found exception thrown from Spark with an error class.
 */
private[spark] class SparkClassNotFoundException(
    errorClass: String,
    errorSubClass: Option[String] = None,
    messageParameters: Map[String, String],
    cause: Throwable = null)
  extends ClassNotFoundException(
    SparkThrowableHelper.getMessage(errorClass, errorSubClass.orNull, messageParameters), cause)
  with SparkThrowable {

  override def getMessageParameters: java.util.Map[String, String] = messageParameters.asJava

  override def getErrorClass: String = errorClass
  override def getErrorSubClass: String = errorSubClass.orNull}

/**
 * Concurrent modification exception thrown from Spark with an error class.
 */
private[spark] class SparkConcurrentModificationException(
    errorClass: String,
    errorSubClass: Option[String] = None,
    messageParameters: Map[String, String],
    cause: Throwable = null)
  extends ConcurrentModificationException(
    SparkThrowableHelper.getMessage(errorClass, errorSubClass.orNull, messageParameters), cause)
  with SparkThrowable {

  override def getMessageParameters: java.util.Map[String, String] = messageParameters.asJava

  override def getErrorClass: String = errorClass
  override def getErrorSubClass: String = errorSubClass.orNull}

/**
 * Datetime exception thrown from Spark with an error class.
 */
private[spark] class SparkDateTimeException(
    errorClass: String,
    errorSubClass: Option[String] = None,
    messageParameters: Map[String, String],
    context: Array[QueryContext],
    summary: String)
  extends DateTimeException(
    SparkThrowableHelper.getMessage(errorClass, errorSubClass.orNull, messageParameters, summary))
  with SparkThrowable {

  override def getMessageParameters: java.util.Map[String, String] = messageParameters.asJava

  override def getErrorClass: String = errorClass
  override def getErrorSubClass: String = errorSubClass.orNull
  override def getQueryContext: Array[QueryContext] = context
}

/**
 * Hadoop file already exists exception thrown from Spark with an error class.
 */
private[spark] class SparkFileAlreadyExistsException(
    errorClass: String,
    errorSubClass: Option[String] = None,
    messageParameters: Map[String, String])
  extends FileAlreadyExistsException(
    SparkThrowableHelper.getMessage(errorClass, errorSubClass.orNull, messageParameters))
  with SparkThrowable {

  override def getMessageParameters: java.util.Map[String, String] = messageParameters.asJava

  override def getErrorClass: String = errorClass
  override def getErrorSubClass: String = errorSubClass.orNull}

/**
 * File not found exception thrown from Spark with an error class.
 */
private[spark] class SparkFileNotFoundException(
    errorClass: String,
    errorSubClass: Option[String] = None,
    messageParameters: Map[String, String])
  extends FileNotFoundException(
    SparkThrowableHelper.getMessage(errorClass, errorSubClass.orNull, messageParameters))
  with SparkThrowable {

  override def getMessageParameters: java.util.Map[String, String] = messageParameters.asJava

  override def getErrorClass: String = errorClass
  override def getErrorSubClass: String = errorSubClass.orNull}

/**
 * Number format exception thrown from Spark with an error class.
 */
private[spark] class SparkNumberFormatException(
    errorClass: String,
    errorSubClass: Option[String] = None,
    messageParameters: Map[String, String],
    context: Array[QueryContext],
    summary: String)
  extends NumberFormatException(
    SparkThrowableHelper.getMessage(errorClass, errorSubClass.orNull, messageParameters, summary))
  with SparkThrowable {

  override def getMessageParameters: java.util.Map[String, String] = messageParameters.asJava

  override def getErrorClass: String = errorClass
  override def getErrorSubClass: String = errorSubClass.orNull
  override def getQueryContext: Array[QueryContext] = context
}

/**
 * Illegal argument exception thrown from Spark with an error class.
 */
private[spark] class SparkIllegalArgumentException(
    errorClass: String,
    errorSubClass: Option[String] = None,
    messageParameters: Map[String, String],
    context: Array[QueryContext] = Array.empty,
    summary: String = "")
  extends IllegalArgumentException(
    SparkThrowableHelper.getMessage(errorClass, errorSubClass.orNull, messageParameters, summary))
  with SparkThrowable {

  override def getMessageParameters: java.util.Map[String, String] = messageParameters.asJava

  override def getErrorClass: String = errorClass
  override def getErrorSubClass: String = errorSubClass.orNull
  override def getQueryContext: Array[QueryContext] = context
}

private[spark] class SparkRuntimeException(
    errorClass: String,
    errorSubClass: Option[String] = None,
    messageParameters: Map[String, String],
    cause: Throwable = null,
    context: Array[QueryContext] = Array.empty,
    summary: String = "")
  extends RuntimeException(
    SparkThrowableHelper.getMessage(errorClass, errorSubClass.orNull, messageParameters, summary),
    cause)
  with SparkThrowable {

  def this(
      errorClass: String,
      errorSubClass: String,
      messageParameters: Map[String, String],
      cause: Throwable,
      context: Array[QueryContext])
    = this(
      errorClass = errorClass,
      errorSubClass = Some(errorSubClass),
      messageParameters = messageParameters,
      cause = cause,
      context = context)

  def this(
      errorClass: String,
      errorSubClass: String,
      messageParameters: Map[String, String])
    = this(
      errorClass = errorClass,
      errorSubClass = Some(errorSubClass),
      messageParameters = messageParameters,
      cause = null,
      context = Array.empty[QueryContext])

  override def getMessageParameters: java.util.Map[String, String] = messageParameters.asJava

  override def getErrorClass: String = errorClass
  override def getErrorSubClass: String = errorSubClass.orNull
  override def getQueryContext: Array[QueryContext] = context
}

/**
 * Security exception thrown from Spark with an error class.
 */
private[spark] class SparkSecurityException(
    errorClass: String,
    errorSubClass: Option[String] = None,
    messageParameters: Map[String, String])
  extends SecurityException(
    SparkThrowableHelper.getMessage(errorClass, errorSubClass.orNull, messageParameters))
  with SparkThrowable {

  override def getMessageParameters: java.util.Map[String, String] = messageParameters.asJava

  override def getErrorClass: String = errorClass
  override def getErrorSubClass: String = errorSubClass.orNull
}

/**
 * Array index out of bounds exception thrown from Spark with an error class.
 */
private[spark] class SparkArrayIndexOutOfBoundsException(
    errorClass: String,
    errorSubClass: Option[String] = None,
    messageParameters: Map[String, String],
    context: Array[QueryContext],
    summary: String)
  extends ArrayIndexOutOfBoundsException(
    SparkThrowableHelper.getMessage(errorClass, errorSubClass.orNull, messageParameters, summary))
  with SparkThrowable {

  override def getMessageParameters: java.util.Map[String, String] = messageParameters.asJava

  override def getErrorClass: String = errorClass
  override def getErrorSubClass: String = errorSubClass.orNull
  override def getQueryContext: Array[QueryContext] = context
}

/**
 * SQL exception thrown from Spark with an error class.
 */
private[spark] class SparkSQLException(
    errorClass: String,
    errorSubClass: Option[String],
    messageParameters: Map[String, String])
  extends SQLException(
    SparkThrowableHelper.getMessage(errorClass, errorSubClass.orNull, messageParameters))
  with SparkThrowable {

  def this(errorClass: String, messageParameters: Map[String, String]) =
    this(
      errorClass = errorClass,
      errorSubClass = None,
      messageParameters = messageParameters)

  override def getMessageParameters: java.util.Map[String, String] = messageParameters.asJava

  override def getErrorClass: String = errorClass
  override def getErrorSubClass: String = errorSubClass.orNull
}

/**
 * SQL feature not supported exception thrown from Spark with an error class.
 */
private[spark] class SparkSQLFeatureNotSupportedException(
    errorClass: String,
    errorSubClass: Option[String] = None,
    messageParameters: Map[String, String])
  extends SQLFeatureNotSupportedException(
    SparkThrowableHelper.getMessage(errorClass, errorSubClass.orNull, messageParameters))
  with SparkThrowable {

  def this(
      errorClass: String,
      errorSubClass: String,
      messageParameters: Map[String, String]) =
    this(
      errorClass = errorClass,
      errorSubClass = Some(errorSubClass),
      messageParameters = messageParameters)

  override def getMessageParameters: java.util.Map[String, String] = messageParameters.asJava

  override def getErrorClass: String = errorClass
  override def getErrorSubClass: String = errorSubClass.orNull
}
