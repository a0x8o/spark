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

import java.io.File
import java.util.IllegalFormatException

import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION
import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import org.apache.commons.io.IOUtils

import org.apache.spark.SparkThrowableHelper._

/**
 * Test suite for Spark Throwables.
 */
class SparkThrowableSuite extends SparkFunSuite {

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  def checkIfUnique(ss: Seq[Any]): Unit = {
    val dups = ss.groupBy(identity).mapValues(_.size).filter(_._2 > 1).keys.toSeq
    assert(dups.isEmpty)
  }

  def checkCondition(ss: Seq[String], fx: String => Boolean): Unit = {
    ss.foreach { s =>
      assert(fx(s))
    }
  }

  test("No duplicate error classes") {
    // Enabling this feature incurs performance overhead (20-30%)
    val mapper = JsonMapper.builder()
      .addModule(DefaultScalaModule)
      .enable(STRICT_DUPLICATE_DETECTION)
      .build()
    mapper.readValue(errorClassesUrl, new TypeReference[Map[String, ErrorInfo]]() {})
  }

  test("Error classes are correctly formatted") {
    val errorClassFileContents = IOUtils.toString(errorClassesUrl.openStream())
    val mapper = JsonMapper.builder()
      .addModule(DefaultScalaModule)
      .enable(SerializationFeature.INDENT_OUTPUT)
      .build()
    val rewrittenString = mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
      .setSerializationInclusion(Include.NON_ABSENT)
      .writeValueAsString(errorClassToInfoMap)
    assert(rewrittenString == errorClassFileContents)
  }

  test("SQLSTATE invariants") {
    val sqlStates = errorClassToInfoMap.values.toSeq.flatMap(_.sqlState)
    checkCondition(sqlStates, s => s.length == 5)
  }

  test("Message format invariants") {
    val messageFormats = errorClassToInfoMap.values.toSeq.map(_.messageFormat)
    checkCondition(messageFormats, s => s != null)
    checkIfUnique(messageFormats)
  }

  test("Round trip") {
    val tmpFile = File.createTempFile("rewritten", ".json")
    val mapper = JsonMapper.builder()
      .addModule(DefaultScalaModule)
      .enable(SerializationFeature.INDENT_OUTPUT)
      .build()
    mapper.writeValue(tmpFile, errorClassToInfoMap)
    val rereadErrorClassToInfoMap = mapper.readValue(
      tmpFile, new TypeReference[Map[String, ErrorInfo]]() {})
    assert(rereadErrorClassToInfoMap == errorClassToInfoMap)
  }

  test("Check if error class is missing") {
    val ex1 = intercept[IllegalArgumentException] {
      getMessage("", Array.empty)
    }
    assert(ex1.getMessage == "Cannot find error class ''")

    val ex2 = intercept[IllegalArgumentException] {
      getMessage("LOREM_IPSUM", Array.empty)
    }
    assert(ex2.getMessage == "Cannot find error class 'LOREM_IPSUM'")
  }

  test("Check if message parameters match message format") {
    // Requires 2 args
    intercept[IllegalFormatException] {
      getMessage("MISSING_COLUMN", Array.empty)
    }

    // Does not fail with too many args (expects 0 args)
    assert(getMessage("DIVIDE_BY_ZERO", Array("foo", "bar")) == "divide by zero")
  }

  test("Error message is formatted") {
    assert(getMessage("MISSING_COLUMN", Array("foo", "bar")) ==
      "cannot resolve 'foo' given input columns: [bar]")
  }

  test("Try catching legacy SparkError") {
    try {
      throw new SparkException("Arbitrary legacy message")
    } catch {
      case e: SparkThrowable =>
        assert(e.getErrorClass == null)
        assert(e.getSqlState == null)
      case _: Throwable =>
        // Should not end up here
        assert(false)
    }
  }

  test("Try catching SparkError with error class") {
    try {
      throw new SparkException(
        errorClass = "WRITING_JOB_ABORTED",
        messageParameters = Array.empty,
        cause = null)
    } catch {
      case e: SparkThrowable =>
        assert(e.getErrorClass == "WRITING_JOB_ABORTED")
        assert(e.getSqlState == "40000")
      case _: Throwable =>
        // Should not end up here
        assert(false)
    }
  }
}
