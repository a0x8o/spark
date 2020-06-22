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

package org.apache.spark.sql.catalyst.util

import java.text.SimpleDateFormat
import java.time.{LocalDate, ZoneId}
import java.util.{Date, Locale}

import org.apache.commons.lang3.time.FastDateFormat

import org.apache.spark.sql.catalyst.util.DateTimeUtils._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.internal.SQLConf.LegacyBehaviorPolicy._

sealed trait DateFormatter extends Serializable {
  def parse(s: String): Int // returns days since epoch

  def format(days: Int): String
  def format(date: Date): String
  def format(localDate: LocalDate): String

  def validatePatternString(): Unit
}

class Iso8601DateFormatter(
    pattern: String,
    zoneId: ZoneId,
    locale: Locale,
    legacyFormat: LegacyDateFormats.LegacyDateFormat,
    isParsing: Boolean)
  extends DateFormatter with DateTimeFormatterHelper {

  @transient
  private lazy val formatter = getOrCreateFormatter(pattern, locale, isParsing)

  @transient
  private lazy val legacyFormatter = DateFormatter.getLegacyFormatter(
    pattern, zoneId, locale, legacyFormat)

  override def parse(s: String): Int = {
    val specialDate = convertSpecialDate(s.trim, zoneId)
    specialDate.getOrElse {
      try {
        val localDate = toLocalDate(formatter.parse(s))
        localDateToDays(localDate)
      } catch checkParsedDiff(s, legacyFormatter.parse)
    }
  }

  override def format(localDate: LocalDate): String = {
    try {
      localDate.format(formatter)
    } catch checkFormattedDiff(toJavaDate(localDateToDays(localDate)),
      (d: Date) => format(d))
  }

  override def format(days: Int): String = {
    format(LocalDate.ofEpochDay(days))
  }

  override def format(date: Date): String = {
    legacyFormatter.format(date)
  }

  override def validatePatternString(): Unit = {
    try {
      formatter
    } catch checkLegacyFormatter(pattern, legacyFormatter.validatePatternString)
  }
}

trait LegacyDateFormatter extends DateFormatter {
  def parseToDate(s: String): Date

  override def parse(s: String): Int = {
    fromJavaDate(new java.sql.Date(parseToDate(s).getTime))
  }

  override def format(days: Int): String = {
    format(DateTimeUtils.toJavaDate(days))
  }

  override def format(localDate: LocalDate): String = {
    format(localDateToDays(localDate))
  }
}

class LegacyFastDateFormatter(pattern: String, locale: Locale) extends LegacyDateFormatter {
  @transient
  private lazy val fdf = FastDateFormat.getInstance(pattern, locale)
  override def parseToDate(s: String): Date = fdf.parse(s)
  override def format(d: Date): String = fdf.format(d)
  override def validatePatternString(): Unit = fdf
}

class LegacySimpleDateFormatter(pattern: String, locale: Locale) extends LegacyDateFormatter {
  @transient
  private lazy val sdf = new SimpleDateFormat(pattern, locale)
  override def parseToDate(s: String): Date = sdf.parse(s)
  override def format(d: Date): String = sdf.format(d)
  override def validatePatternString(): Unit = sdf

}

object DateFormatter {
  import LegacyDateFormats._

  val defaultLocale: Locale = Locale.US

  val defaultPattern: String = "yyyy-MM-dd"

  private def getFormatter(
      format: Option[String],
      zoneId: ZoneId,
      locale: Locale = defaultLocale,
      legacyFormat: LegacyDateFormat = LENIENT_SIMPLE_DATE_FORMAT,
      isParsing: Boolean): DateFormatter = {
    val pattern = format.getOrElse(defaultPattern)
    if (SQLConf.get.legacyTimeParserPolicy == LEGACY) {
      getLegacyFormatter(pattern, zoneId, locale, legacyFormat)
    } else {
      val df = new Iso8601DateFormatter(pattern, zoneId, locale, legacyFormat, isParsing)
      df.validatePatternString()
      df
    }
  }

  def getLegacyFormatter(
      pattern: String,
      zoneId: ZoneId,
      locale: Locale,
      legacyFormat: LegacyDateFormat): DateFormatter = {
    legacyFormat match {
      case FAST_DATE_FORMAT =>
        new LegacyFastDateFormatter(pattern, locale)
      case SIMPLE_DATE_FORMAT | LENIENT_SIMPLE_DATE_FORMAT =>
        new LegacySimpleDateFormatter(pattern, locale)
    }
  }

  def apply(
      format: String,
      zoneId: ZoneId,
      locale: Locale,
      legacyFormat: LegacyDateFormat,
      isParsing: Boolean): DateFormatter = {
    getFormatter(Some(format), zoneId, locale, legacyFormat, isParsing)
  }

  def apply(format: String, zoneId: ZoneId, isParsing: Boolean = false): DateFormatter = {
    getFormatter(Some(format), zoneId, isParsing = isParsing)
  }

  def apply(zoneId: ZoneId): DateFormatter = {
    getFormatter(None, zoneId, isParsing = false)
  }
}
