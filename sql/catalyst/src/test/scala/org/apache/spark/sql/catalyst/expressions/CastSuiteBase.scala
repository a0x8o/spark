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

package org.apache.spark.sql.catalyst.expressions

import java.sql.{Date, Timestamp}
import java.time.{Duration, LocalDate, LocalDateTime, Period}
import java.time.temporal.ChronoUnit
import java.util.{Calendar, TimeZone}

import scala.collection.parallel.immutable.ParVector

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.TypeCheckResult.TypeCheckFailure
import org.apache.spark.sql.catalyst.analysis.TypeCoercion.numericPrecedence
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenContext
import org.apache.spark.sql.catalyst.util.DateTimeConstants._
import org.apache.spark.sql.catalyst.util.DateTimeTestUtils._
import org.apache.spark.sql.catalyst.util.DateTimeUtils._
import org.apache.spark.sql.catalyst.util.IntervalUtils.microsToDuration
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.sql.types.DataTypeTestUtils.{dayTimeIntervalTypes, yearMonthIntervalTypes}
import org.apache.spark.unsafe.types.UTF8String

abstract class CastSuiteBase extends SparkFunSuite with ExpressionEvalHelper {

  protected def cast(v: Any, targetType: DataType, timeZoneId: Option[String] = None): CastBase

  // expected cannot be null
  protected def checkCast(v: Any, expected: Any): Unit = {
    checkEvaluation(cast(v, Literal(expected).dataType), expected)
  }

  protected def checkNullCast(from: DataType, to: DataType): Unit = {
    checkEvaluation(cast(Literal.create(null, from), to, UTC_OPT), null)
  }

  protected def verifyCastFailure(c: CastBase, optionalExpectedMsg: Option[String] = None): Unit = {
    val typeCheckResult = c.checkInputDataTypes()
    assert(typeCheckResult.isFailure)
    assert(typeCheckResult.isInstanceOf[TypeCheckFailure])
    val message = typeCheckResult.asInstanceOf[TypeCheckFailure].message

    if (optionalExpectedMsg.isDefined) {
      assert(message.contains(optionalExpectedMsg.get))
    } else if (setConfigurationHint.nonEmpty) {
      assert(message.contains("with ANSI mode on"))
      assert(message.contains(setConfigurationHint))
    } else {
      assert("cannot cast [a-zA-Z]+ to [a-zA-Z]+".r.findFirstIn(message).isDefined)
    }
  }

  protected def isAlwaysNullable: Boolean = false

  protected def setConfigurationHint: String = ""

  test("null cast") {
    import DataTypeTestUtils._

    atomicTypes.zip(atomicTypes).foreach { case (from, to) =>
      checkNullCast(from, to)
    }

    atomicTypes.foreach(dt => checkNullCast(NullType, dt))
    atomicTypes.foreach(dt => checkNullCast(dt, StringType))
    checkNullCast(StringType, BinaryType)
    checkNullCast(StringType, BooleanType)
    numericTypes.foreach(dt => checkNullCast(dt, BooleanType))

    checkNullCast(StringType, TimestampType)
    checkNullCast(DateType, TimestampType)

    checkNullCast(StringType, DateType)
    checkNullCast(TimestampType, DateType)

    checkNullCast(StringType, CalendarIntervalType)
    numericTypes.foreach(dt => checkNullCast(StringType, dt))
    numericTypes.foreach(dt => checkNullCast(BooleanType, dt))
    for (from <- numericTypes; to <- numericTypes) checkNullCast(from, to)
  }

  test("cast string to date") {
    var c = Calendar.getInstance()
    c.set(2015, 0, 1, 0, 0, 0)
    c.set(Calendar.MILLISECOND, 0)
    checkEvaluation(Cast(Literal("2015"), DateType), new Date(c.getTimeInMillis))
    c = Calendar.getInstance()
    c.set(2015, 2, 1, 0, 0, 0)
    c.set(Calendar.MILLISECOND, 0)
    checkEvaluation(Cast(Literal("2015-03"), DateType), new Date(c.getTimeInMillis))
    c = Calendar.getInstance()
    c.set(2015, 2, 18, 0, 0, 0)
    c.set(Calendar.MILLISECOND, 0)
    checkEvaluation(Cast(Literal("2015-03-18"), DateType), new Date(c.getTimeInMillis))
    checkEvaluation(Cast(Literal("2015-03-18 "), DateType), new Date(c.getTimeInMillis))
    checkEvaluation(Cast(Literal("2015-03-18 123142"), DateType), new Date(c.getTimeInMillis))
    checkEvaluation(Cast(Literal("2015-03-18T123123"), DateType), new Date(c.getTimeInMillis))
    checkEvaluation(Cast(Literal("2015-03-18T"), DateType), new Date(c.getTimeInMillis))
  }

  test("cast string to timestamp") {
    new ParVector(ALL_TIMEZONES.toVector).foreach { zid =>
      def checkCastStringToTimestamp(str: String, expected: Timestamp): Unit = {
        checkEvaluation(cast(Literal(str), TimestampType, Option(zid.getId)), expected)
      }

      val tz = TimeZone.getTimeZone(zid)
      var c = Calendar.getInstance(tz)
      c.set(2015, 0, 1, 0, 0, 0)
      c.set(Calendar.MILLISECOND, 0)
      checkCastStringToTimestamp("2015", new Timestamp(c.getTimeInMillis))
      c = Calendar.getInstance(tz)
      c.set(2015, 2, 1, 0, 0, 0)
      c.set(Calendar.MILLISECOND, 0)
      checkCastStringToTimestamp("2015-03", new Timestamp(c.getTimeInMillis))
      c = Calendar.getInstance(tz)
      c.set(2015, 2, 18, 0, 0, 0)
      c.set(Calendar.MILLISECOND, 0)
      checkCastStringToTimestamp("2015-03-18", new Timestamp(c.getTimeInMillis))
      checkCastStringToTimestamp("2015-03-18 ", new Timestamp(c.getTimeInMillis))
      checkCastStringToTimestamp("2015-03-18T", new Timestamp(c.getTimeInMillis))

      c = Calendar.getInstance(tz)
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 0)
      checkCastStringToTimestamp("2015-03-18 12:03:17", new Timestamp(c.getTimeInMillis))
      checkCastStringToTimestamp("2015-03-18T12:03:17", new Timestamp(c.getTimeInMillis))

      // If the string value includes timezone string, it represents the timestamp string
      // in the timezone regardless of the timeZoneId parameter.
      c = Calendar.getInstance(TimeZone.getTimeZone(UTC))
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 0)
      checkCastStringToTimestamp("2015-03-18T12:03:17Z", new Timestamp(c.getTimeInMillis))
      checkCastStringToTimestamp("2015-03-18 12:03:17Z", new Timestamp(c.getTimeInMillis))

      c = Calendar.getInstance(TimeZone.getTimeZone("GMT-01:00"))
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 0)
      checkCastStringToTimestamp("2015-03-18T12:03:17-1:0", new Timestamp(c.getTimeInMillis))
      checkCastStringToTimestamp("2015-03-18T12:03:17-01:00", new Timestamp(c.getTimeInMillis))

      c = Calendar.getInstance(TimeZone.getTimeZone("GMT+07:30"))
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 0)
      checkCastStringToTimestamp("2015-03-18T12:03:17+07:30", new Timestamp(c.getTimeInMillis))

      c = Calendar.getInstance(TimeZone.getTimeZone("GMT+07:03"))
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 0)
      checkCastStringToTimestamp("2015-03-18T12:03:17+7:3", new Timestamp(c.getTimeInMillis))

      // tests for the string including milliseconds.
      c = Calendar.getInstance(tz)
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 123)
      checkCastStringToTimestamp("2015-03-18 12:03:17.123", new Timestamp(c.getTimeInMillis))
      checkCastStringToTimestamp("2015-03-18T12:03:17.123", new Timestamp(c.getTimeInMillis))

      // If the string value includes timezone string, it represents the timestamp string
      // in the timezone regardless of the timeZoneId parameter.
      c = Calendar.getInstance(TimeZone.getTimeZone(UTC))
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 456)
      checkCastStringToTimestamp("2015-03-18T12:03:17.456Z", new Timestamp(c.getTimeInMillis))
      checkCastStringToTimestamp("2015-03-18 12:03:17.456Z", new Timestamp(c.getTimeInMillis))

      c = Calendar.getInstance(TimeZone.getTimeZone("GMT-01:00"))
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 123)
      checkCastStringToTimestamp("2015-03-18T12:03:17.123-1:0", new Timestamp(c.getTimeInMillis))
      checkCastStringToTimestamp("2015-03-18T12:03:17.123-01:00", new Timestamp(c.getTimeInMillis))

      c = Calendar.getInstance(TimeZone.getTimeZone("GMT+07:30"))
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 123)
      checkCastStringToTimestamp("2015-03-18T12:03:17.123+07:30", new Timestamp(c.getTimeInMillis))

      c = Calendar.getInstance(TimeZone.getTimeZone("GMT+07:03"))
      c.set(2015, 2, 18, 12, 3, 17)
      c.set(Calendar.MILLISECOND, 123)
      checkCastStringToTimestamp("2015-03-18T12:03:17.123+7:3", new Timestamp(c.getTimeInMillis))
    }
  }

  test("cast from boolean") {
    checkEvaluation(cast(true, IntegerType), 1)
    checkEvaluation(cast(false, IntegerType), 0)
    checkEvaluation(cast(true, StringType), "true")
    checkEvaluation(cast(false, StringType), "false")
    checkEvaluation(cast(cast(1, BooleanType), IntegerType), 1)
    checkEvaluation(cast(cast(0, BooleanType), IntegerType), 0)
  }

  test("cast from int") {
    checkCast(0, false)
    checkCast(1, true)
    checkCast(-5, true)
    checkCast(1, 1.toByte)
    checkCast(1, 1.toShort)
    checkCast(1, 1)
    checkCast(1, 1.toLong)
    checkCast(1, 1.0f)
    checkCast(1, 1.0)
    checkCast(123, "123")

    checkEvaluation(cast(123, DecimalType.USER_DEFAULT), Decimal(123))
    checkEvaluation(cast(123, DecimalType(3, 0)), Decimal(123))
    checkEvaluation(cast(1, LongType), 1.toLong)
  }

  test("cast from long") {
    checkCast(0L, false)
    checkCast(1L, true)
    checkCast(-5L, true)
    checkCast(1L, 1.toByte)
    checkCast(1L, 1.toShort)
    checkCast(1L, 1)
    checkCast(1L, 1.toLong)
    checkCast(1L, 1.0f)
    checkCast(1L, 1.0)
    checkCast(123L, "123")

    checkEvaluation(cast(123L, DecimalType.USER_DEFAULT), Decimal(123))
    checkEvaluation(cast(123L, DecimalType(3, 0)), Decimal(123))
  }

  test("cast from float") {
    checkCast(0.0f, false)
    checkCast(0.5f, true)
    checkCast(-5.0f, true)
    checkCast(1.5f, 1.toByte)
    checkCast(1.5f, 1.toShort)
    checkCast(1.5f, 1)
    checkCast(1.5f, 1.toLong)
    checkCast(1.5f, 1.5)
    checkCast(1.5f, "1.5")
  }

  test("cast from double") {
    checkCast(0.0, false)
    checkCast(0.5, true)
    checkCast(-5.0, true)
    checkCast(1.5, 1.toByte)
    checkCast(1.5, 1.toShort)
    checkCast(1.5, 1)
    checkCast(1.5, 1.toLong)
    checkCast(1.5, 1.5f)
    checkCast(1.5, "1.5")
  }

  test("cast from string") {
    assert(cast("abcdef", StringType).nullable === isAlwaysNullable)
    assert(cast("abcdef", BinaryType).nullable === isAlwaysNullable)
    assert(cast("abcdef", BooleanType).nullable)
    assert(cast("abcdef", TimestampType).nullable)
    assert(cast("abcdef", LongType).nullable)
    assert(cast("abcdef", IntegerType).nullable)
    assert(cast("abcdef", ShortType).nullable)
    assert(cast("abcdef", ByteType).nullable)
    assert(cast("abcdef", DecimalType.USER_DEFAULT).nullable)
    assert(cast("abcdef", DecimalType(4, 2)).nullable)
    assert(cast("abcdef", DoubleType).nullable)
    assert(cast("abcdef", FloatType).nullable)
  }

  test("data type casting") {
    val sd = "1970-01-01"
    val d = Date.valueOf(sd)
    val zts = sd + " 00:00:00"
    val sts = sd + " 00:00:02"
    val nts = sts + ".1"
    val ts = withDefaultTimeZone(UTC)(Timestamp.valueOf(nts))

    for (tz <- ALL_TIMEZONES) {
      val timeZoneId = Option(tz.getId)
      var c = Calendar.getInstance(TimeZoneUTC)
      c.set(2015, 2, 8, 2, 30, 0)
      checkEvaluation(
        cast(cast(new Timestamp(c.getTimeInMillis), StringType, timeZoneId),
          TimestampType, timeZoneId),
        millisToMicros(c.getTimeInMillis))
      c = Calendar.getInstance(TimeZoneUTC)
      c.set(2015, 10, 1, 2, 30, 0)
      checkEvaluation(
        cast(cast(new Timestamp(c.getTimeInMillis), StringType, timeZoneId),
          TimestampType, timeZoneId),
        millisToMicros(c.getTimeInMillis))
    }

    checkEvaluation(cast("abdef", StringType), "abdef")
    checkEvaluation(cast("12.65", DecimalType.SYSTEM_DEFAULT), Decimal(12.65))

    checkEvaluation(cast(cast(sd, DateType), StringType), sd)
    checkEvaluation(cast(cast(d, StringType), DateType), 0)
    checkEvaluation(cast(cast(nts, TimestampType, UTC_OPT), StringType, UTC_OPT), nts)
    checkEvaluation(
      cast(cast(ts, StringType, UTC_OPT), TimestampType, UTC_OPT),
      fromJavaTimestamp(ts))

    // all convert to string type to check
    checkEvaluation(
      cast(cast(cast(nts, TimestampType, UTC_OPT), DateType, UTC_OPT), StringType),
      sd)
    checkEvaluation(
      cast(cast(cast(ts, DateType, UTC_OPT), TimestampType, UTC_OPT), StringType, UTC_OPT),
      zts)

    checkEvaluation(cast(cast("abdef", BinaryType), StringType), "abdef")

    checkEvaluation(cast(cast(cast(cast(
      cast(cast("5", ByteType), ShortType), IntegerType), FloatType), DoubleType), LongType),
      5.toLong)

    checkEvaluation(cast("23", DoubleType), 23d)
    checkEvaluation(cast("23", IntegerType), 23)
    checkEvaluation(cast("23", FloatType), 23f)
    checkEvaluation(cast("23", DecimalType.USER_DEFAULT), Decimal(23))
    checkEvaluation(cast("23", ByteType), 23.toByte)
    checkEvaluation(cast("23", ShortType), 23.toShort)
    checkEvaluation(cast(123, IntegerType), 123)

    checkEvaluation(cast(Literal.create(null, IntegerType), ShortType), null)
  }

  test("cast and add") {
    checkEvaluation(Add(Literal(23d), cast(true, DoubleType)), 24d)
    checkEvaluation(Add(Literal(23), cast(true, IntegerType)), 24)
    checkEvaluation(Add(Literal(23f), cast(true, FloatType)), 24f)
    checkEvaluation(Add(Literal(Decimal(23)), cast(true, DecimalType.USER_DEFAULT)), Decimal(24))
    checkEvaluation(Add(Literal(23.toByte), cast(true, ByteType)), 24.toByte)
    checkEvaluation(Add(Literal(23.toShort), cast(true, ShortType)), 24.toShort)
  }

  test("from decimal") {
    checkCast(Decimal(0.0), false)
    checkCast(Decimal(0.5), true)
    checkCast(Decimal(-5.0), true)
    checkCast(Decimal(1.5), 1.toByte)
    checkCast(Decimal(1.5), 1.toShort)
    checkCast(Decimal(1.5), 1)
    checkCast(Decimal(1.5), 1.toLong)
    checkCast(Decimal(1.5), 1.5f)
    checkCast(Decimal(1.5), 1.5)
    checkCast(Decimal(1.5), "1.5")
  }

  test("cast from array") {
    val array = Literal.create(Seq("123", "true", "f", null),
      ArrayType(StringType, containsNull = true))
    val array_notNull = Literal.create(Seq("123", "true", "f"),
      ArrayType(StringType, containsNull = false))

    checkNullCast(ArrayType(StringType), ArrayType(IntegerType))

    {
      val array = Literal.create(Seq.empty, ArrayType(NullType, containsNull = false))
      val ret = cast(array, ArrayType(IntegerType, containsNull = false))
      assert(ret.resolved)
      checkEvaluation(ret, Seq.empty)
    }

    {
      val ret = cast(array, ArrayType(BooleanType, containsNull = false))
      assert(ret.resolved === false)
    }

    {
      val ret = cast(array_notNull, ArrayType(BooleanType, containsNull = false))
      assert(ret.resolved === false)
    }

    {
      val ret = cast(array, IntegerType)
      assert(ret.resolved === false)
    }
  }

  test("cast from map") {
    val map = Literal.create(
      Map("a" -> "123", "b" -> "true", "c" -> "f", "d" -> null),
      MapType(StringType, StringType, valueContainsNull = true))
    val map_notNull = Literal.create(
      Map("a" -> "123", "b" -> "true", "c" -> "f"),
      MapType(StringType, StringType, valueContainsNull = false))

    checkNullCast(MapType(StringType, IntegerType), MapType(StringType, StringType))

    {
      val ret = cast(map, MapType(StringType, BooleanType, valueContainsNull = false))
      assert(ret.resolved === false)
    }
    {
      val ret = cast(map, MapType(IntegerType, StringType, valueContainsNull = true))
      assert(ret.resolved === false)
    }
    {
      val ret = cast(map_notNull, MapType(StringType, BooleanType, valueContainsNull = false))
      assert(ret.resolved === false)
    }
    {
      val ret = cast(map_notNull, MapType(IntegerType, StringType, valueContainsNull = true))
      assert(ret.resolved === false)
    }

    {
      val ret = cast(map, IntegerType)
      assert(ret.resolved === false)
    }
  }

  test("cast from struct") {
    checkNullCast(
      StructType(Seq(
        StructField("a", StringType),
        StructField("b", IntegerType))),
      StructType(Seq(
        StructField("a", StringType),
        StructField("b", StringType))))

    val struct = Literal.create(
      InternalRow(
        UTF8String.fromString("123"),
        UTF8String.fromString("true"),
        UTF8String.fromString("f"),
        null),
      StructType(Seq(
        StructField("a", StringType, nullable = true),
        StructField("b", StringType, nullable = true),
        StructField("c", StringType, nullable = true),
        StructField("d", StringType, nullable = true))))
    val struct_notNull = Literal.create(
      InternalRow(
        UTF8String.fromString("123"),
        UTF8String.fromString("true"),
        UTF8String.fromString("f")),
      StructType(Seq(
        StructField("a", StringType, nullable = false),
        StructField("b", StringType, nullable = false),
        StructField("c", StringType, nullable = false))))

    {
      val ret = cast(struct, StructType(Seq(
        StructField("a", BooleanType, nullable = true),
        StructField("b", BooleanType, nullable = true),
        StructField("c", BooleanType, nullable = false),
        StructField("d", BooleanType, nullable = true))))
      assert(ret.resolved === false)
    }

    {
      val ret = cast(struct_notNull, StructType(Seq(
        StructField("a", BooleanType, nullable = true),
        StructField("b", BooleanType, nullable = true),
        StructField("c", BooleanType, nullable = false))))
      assert(ret.resolved === false)
    }

    {
      val ret = cast(struct, StructType(Seq(
        StructField("a", StringType, nullable = true),
        StructField("b", StringType, nullable = true),
        StructField("c", StringType, nullable = true))))
      assert(ret.resolved === false)
    }
    {
      val ret = cast(struct, IntegerType)
      assert(ret.resolved === false)
    }
  }

  test("cast struct with a timestamp field") {
    val originalSchema = new StructType().add("tsField", TimestampType, nullable = false)
    // nine out of ten times I'm casting a struct, it's to normalize its fields nullability
    val targetSchema = new StructType().add("tsField", TimestampType, nullable = true)

    val inp = Literal.create(InternalRow(0L), originalSchema)
    val expected = InternalRow(0L)
    checkEvaluation(cast(inp, targetSchema), expected)
  }

  test("complex casting") {
    val complex = Literal.create(
      Row(
        Seq("123", "true", "f"),
        Map("a" -> "123", "b" -> "true", "c" -> "f"),
        Row(0)),
      StructType(Seq(
        StructField("a",
          ArrayType(StringType, containsNull = false), nullable = true),
        StructField("m",
          MapType(StringType, StringType, valueContainsNull = false), nullable = true),
        StructField("s",
          StructType(Seq(
            StructField("i", IntegerType, nullable = true)))))))

    val ret = cast(complex, StructType(Seq(
      StructField("a",
        ArrayType(IntegerType, containsNull = true), nullable = true),
      StructField("m",
        MapType(StringType, BooleanType, valueContainsNull = false), nullable = true),
      StructField("s",
        StructType(Seq(
          StructField("l", LongType, nullable = true)))))))

    assert(ret.resolved === false)
  }

  test("cast between string and interval") {
    import org.apache.spark.unsafe.types.CalendarInterval

    checkEvaluation(Cast(Literal(""), CalendarIntervalType), null)
    checkEvaluation(Cast(Literal("interval -3 month 1 day 7 hours"), CalendarIntervalType),
      new CalendarInterval(-3, 1, 7 * MICROS_PER_HOUR))
    checkEvaluation(Cast(Literal.create(
      new CalendarInterval(15, 9, -3 * MICROS_PER_HOUR), CalendarIntervalType),
      StringType),
      "1 years 3 months 9 days -3 hours")
    checkEvaluation(Cast(Literal("INTERVAL 1 Second 1 microsecond"), CalendarIntervalType),
      new CalendarInterval(0, 0, 1000001))
    checkEvaluation(Cast(Literal("1 MONTH 1 Microsecond"), CalendarIntervalType),
      new CalendarInterval(1, 0, 1))
  }

  test("cast string to boolean") {
    checkCast("t", true)
    checkCast("true", true)
    checkCast("tRUe", true)
    checkCast("y", true)
    checkCast("yes", true)
    checkCast("1", true)
    checkCast("f", false)
    checkCast("false", false)
    checkCast("FAlsE", false)
    checkCast("n", false)
    checkCast("no", false)
    checkCast("0", false)
  }

  protected def checkInvalidCastFromNumericType(to: DataType): Unit = {
    assert(cast(1.toByte, to).checkInputDataTypes().isFailure)
    assert(cast(1.toShort, to).checkInputDataTypes().isFailure)
    assert(cast(1, to).checkInputDataTypes().isFailure)
    assert(cast(1L, to).checkInputDataTypes().isFailure)
    assert(cast(1.0.toFloat, to).checkInputDataTypes().isFailure)
    assert(cast(1.0, to).checkInputDataTypes().isFailure)
  }

  test("SPARK-16729 type checking for casting to date type") {
    assert(cast("1234", DateType).checkInputDataTypes().isSuccess)
    assert(cast(new Timestamp(1), DateType).checkInputDataTypes().isSuccess)
    assert(cast(false, DateType).checkInputDataTypes().isFailure)
    checkInvalidCastFromNumericType(DateType)
  }

  test("SPARK-20302 cast with same structure") {
    val from = new StructType()
      .add("a", IntegerType)
      .add("b", new StructType().add("b1", LongType))

    val to = new StructType()
      .add("a1", IntegerType)
      .add("b1", new StructType().add("b11", LongType))

    val input = Row(10, Row(12L))

    checkEvaluation(cast(Literal.create(input, from), to), input)
  }

  test("SPARK-22500: cast for struct should not generate codes beyond 64KB") {
    val N = 25

    val fromInner = new StructType(
      (1 to N).map(i => StructField(s"s$i", DoubleType)).toArray)
    val toInner = new StructType(
      (1 to N).map(i => StructField(s"i$i", IntegerType)).toArray)
    val inputInner = Row.fromSeq((1 to N).map(i => i + 0.5))
    val outputInner = Row.fromSeq((1 to N))
    val fromOuter = new StructType(
      (1 to N).map(i => StructField(s"s$i", fromInner)).toArray)
    val toOuter = new StructType(
      (1 to N).map(i => StructField(s"s$i", toInner)).toArray)
    val inputOuter = Row.fromSeq((1 to N).map(_ => inputInner))
    val outputOuter = Row.fromSeq((1 to N).map(_ => outputInner))
    checkEvaluation(cast(Literal.create(inputOuter, fromOuter), toOuter), outputOuter)
  }

  test("SPARK-22570: Cast should not create a lot of global variables") {
    val ctx = new CodegenContext
    cast("1", IntegerType).genCode(ctx)
    cast("2", LongType).genCode(ctx)
    assert(ctx.inlinedMutableStates.length == 0)
  }

  test("up-cast") {
    def isCastSafe(from: NumericType, to: NumericType): Boolean = (from, to) match {
      case (_, dt: DecimalType) => dt.isWiderThan(from)
      case (dt: DecimalType, _) => dt.isTighterThan(to)
      case _ => numericPrecedence.indexOf(from) <= numericPrecedence.indexOf(to)
    }

    def makeComplexTypes(dt: NumericType, nullable: Boolean): Seq[DataType] = {
      Seq(
        new StructType().add("a", dt, nullable).add("b", dt, nullable),
        ArrayType(dt, nullable),
        MapType(dt, dt, nullable),
        ArrayType(new StructType().add("a", dt, nullable), nullable),
        new StructType().add("a", ArrayType(dt, nullable), nullable)
      )
    }

    import DataTypeTestUtils._
    numericTypes.foreach { from =>
      val (safeTargetTypes, unsafeTargetTypes) = numericTypes.partition(to => isCastSafe(from, to))

      safeTargetTypes.foreach { to =>
        assert(Cast.canUpCast(from, to), s"It should be possible to up-cast $from to $to")

        // If the nullability is compatible, we can up-cast complex types too.
        Seq(true -> true, false -> false, false -> true).foreach { case (fn, tn) =>
          makeComplexTypes(from, fn).zip(makeComplexTypes(to, tn)).foreach {
            case (complexFromType, complexToType) =>
              assert(Cast.canUpCast(complexFromType, complexToType))
          }
        }

        makeComplexTypes(from, true).zip(makeComplexTypes(to, false)).foreach {
          case (complexFromType, complexToType) =>
            assert(!Cast.canUpCast(complexFromType, complexToType))
        }
      }

      unsafeTargetTypes.foreach { to =>
        assert(!Cast.canUpCast(from, to), s"It shouldn't be possible to up-cast $from to $to")
        makeComplexTypes(from, true).zip(makeComplexTypes(to, true)).foreach {
          case (complexFromType, complexToType) =>
            assert(!Cast.canUpCast(complexFromType, complexToType))
        }
      }
    }
    numericTypes.foreach { dt =>
      makeComplexTypes(dt, true).foreach { complexType =>
        assert(!Cast.canUpCast(complexType, StringType))
      }
    }

    atomicTypes.foreach { atomicType =>
      assert(Cast.canUpCast(NullType, atomicType))
    }
  }

  test("SPARK-27671: cast from nested null type in struct") {
    import DataTypeTestUtils._

    atomicTypes.foreach { atomicType =>
      val struct = Literal.create(
        InternalRow(null),
        StructType(Seq(StructField("a", NullType, nullable = true))))

      val ret = cast(struct, StructType(Seq(
        StructField("a", atomicType, nullable = true))))
      assert(ret.resolved)
      checkEvaluation(ret, InternalRow(null))
    }
  }

  test("Process Infinity, -Infinity, NaN in case insensitive manner") {
    Seq("inf", "+inf", "infinity", "+infiNity", " infinity ").foreach { value =>
      checkEvaluation(cast(value, FloatType), Float.PositiveInfinity)
    }
    Seq("-infinity", "-infiniTy", "  -infinity  ", "  -inf  ").foreach { value =>
      checkEvaluation(cast(value, FloatType), Float.NegativeInfinity)
    }
    Seq("inf", "+inf", "infinity", "+infiNity", " infinity ").foreach { value =>
      checkEvaluation(cast(value, DoubleType), Double.PositiveInfinity)
    }
    Seq("-infinity", "-infiniTy", "  -infinity  ", "  -inf  ").foreach { value =>
      checkEvaluation(cast(value, DoubleType), Double.NegativeInfinity)
    }
    Seq("nan", "nAn", " nan ").foreach { value =>
      checkEvaluation(cast(value, FloatType), Float.NaN)
    }
    Seq("nan", "nAn", " nan ").foreach { value =>
      checkEvaluation(cast(value, DoubleType), Double.NaN)
    }
  }

  test("SPARK-22825 Cast array to string") {
    val ret1 = cast(Literal.create(Array(1, 2, 3, 4, 5)), StringType)
    checkEvaluation(ret1, "[1, 2, 3, 4, 5]")
    val ret2 = cast(Literal.create(Array("ab", "cde", "f")), StringType)
    checkEvaluation(ret2, "[ab, cde, f]")
    Seq(false, true).foreach { omitNull =>
      withSQLConf(SQLConf.LEGACY_COMPLEX_TYPES_TO_STRING.key -> omitNull.toString) {
        val ret3 = cast(Literal.create(Array("ab", null, "c")), StringType)
        checkEvaluation(ret3, s"[ab,${if (omitNull) "" else " null"}, c]")
      }
    }
    val ret4 =
      cast(Literal.create(Array("ab".getBytes, "cde".getBytes, "f".getBytes)), StringType)
    checkEvaluation(ret4, "[ab, cde, f]")
    val ret5 = cast(
      Literal.create(Array("2014-12-03", "2014-12-04", "2014-12-06").map(Date.valueOf)),
      StringType)
    checkEvaluation(ret5, "[2014-12-03, 2014-12-04, 2014-12-06]")
    val ret6 = cast(
      Literal.create(Array("2014-12-03 13:01:00", "2014-12-04 15:05:00")
        .map(Timestamp.valueOf)),
      StringType)
    checkEvaluation(ret6, "[2014-12-03 13:01:00, 2014-12-04 15:05:00]")
    val ret7 = cast(Literal.create(Array(Array(1, 2, 3), Array(4, 5))), StringType)
    checkEvaluation(ret7, "[[1, 2, 3], [4, 5]]")
    val ret8 = cast(
      Literal.create(Array(Array(Array("a"), Array("b", "c")), Array(Array("d")))),
      StringType)
    checkEvaluation(ret8, "[[[a], [b, c]], [[d]]]")
  }

  test("SPARK-33291: Cast array with null elements to string") {
    Seq(false, true).foreach { omitNull =>
      withSQLConf(SQLConf.LEGACY_COMPLEX_TYPES_TO_STRING.key -> omitNull.toString) {
        val ret1 = cast(Literal.create(Array(null, null)), StringType)
        checkEvaluation(
          ret1,
          s"[${if (omitNull) "" else "null"},${if (omitNull) "" else " null"}]")
      }
    }
  }

  test("SPARK-22973 Cast map to string") {
    Seq(
      false -> ("{", "}"),
      true -> ("[", "]")).foreach { case (legacyCast, (lb, rb)) =>
      withSQLConf(SQLConf.LEGACY_COMPLEX_TYPES_TO_STRING.key -> legacyCast.toString) {
        val ret1 = cast(Literal.create(Map(1 -> "a", 2 -> "b", 3 -> "c")), StringType)
        checkEvaluation(ret1, s"${lb}1 -> a, 2 -> b, 3 -> c$rb")
        val ret2 = cast(
          Literal.create(Map("1" -> "a".getBytes, "2" -> null, "3" -> "c".getBytes)),
          StringType)
        checkEvaluation(ret2, s"${lb}1 -> a, 2 ->${if (legacyCast) "" else " null"}, 3 -> c$rb")
        val ret3 = cast(
          Literal.create(Map(
            1 -> Date.valueOf("2014-12-03"),
            2 -> Date.valueOf("2014-12-04"),
            3 -> Date.valueOf("2014-12-05"))),
          StringType)
        checkEvaluation(ret3, s"${lb}1 -> 2014-12-03, 2 -> 2014-12-04, 3 -> 2014-12-05$rb")
        val ret4 = cast(
          Literal.create(Map(
            1 -> Timestamp.valueOf("2014-12-03 13:01:00"),
            2 -> Timestamp.valueOf("2014-12-04 15:05:00"))),
          StringType)
        checkEvaluation(ret4, s"${lb}1 -> 2014-12-03 13:01:00, 2 -> 2014-12-04 15:05:00$rb")
        val ret5 = cast(
          Literal.create(Map(
            1 -> Array(1, 2, 3),
            2 -> Array(4, 5, 6))),
          StringType)
        checkEvaluation(ret5, s"${lb}1 -> [1, 2, 3], 2 -> [4, 5, 6]$rb")
      }
    }
  }

  test("SPARK-22981 Cast struct to string") {
    Seq(
      false -> ("{", "}"),
      true -> ("[", "]")).foreach { case (legacyCast, (lb, rb)) =>
      withSQLConf(SQLConf.LEGACY_COMPLEX_TYPES_TO_STRING.key -> legacyCast.toString) {
        val ret1 = cast(Literal.create((1, "a", 0.1)), StringType)
        checkEvaluation(ret1, s"${lb}1, a, 0.1$rb")
        val ret2 = cast(Literal.create(Tuple3[Int, String, String](1, null, "a")), StringType)
        checkEvaluation(ret2, s"${lb}1,${if (legacyCast) "" else " null"}, a$rb")
        val ret3 = cast(Literal.create(
          (Date.valueOf("2014-12-03"), Timestamp.valueOf("2014-12-03 15:05:00"))), StringType)
        checkEvaluation(ret3, s"${lb}2014-12-03, 2014-12-03 15:05:00$rb")
        val ret4 = cast(Literal.create(((1, "a"), 5, 0.1)), StringType)
        checkEvaluation(ret4, s"$lb${lb}1, a$rb, 5, 0.1$rb")
        val ret5 = cast(Literal.create((Seq(1, 2, 3), "a", 0.1)), StringType)
        checkEvaluation(ret5, s"$lb[1, 2, 3], a, 0.1$rb")
        val ret6 = cast(Literal.create((1, Map(1 -> "a", 2 -> "b", 3 -> "c"))), StringType)
        checkEvaluation(ret6, s"${lb}1, ${lb}1 -> a, 2 -> b, 3 -> c$rb$rb")
      }
    }
  }

  test("SPARK-33291: Cast struct with null elements to string") {
    Seq(
      false -> ("{", "}"),
      true -> ("[", "]")).foreach { case (legacyCast, (lb, rb)) =>
      withSQLConf(SQLConf.LEGACY_COMPLEX_TYPES_TO_STRING.key -> legacyCast.toString) {
        val ret1 = cast(Literal.create(Tuple2[String, String](null, null)), StringType)
        checkEvaluation(
          ret1,
          s"$lb${if (legacyCast) "" else "null"},${if (legacyCast) "" else " null"}$rb")
      }
    }
  }

  test("SPARK-34667: cast year-month interval to string") {
    Seq(
      Period.ofMonths(0) -> "0-0",
      Period.ofMonths(1) -> "0-1",
      Period.ofMonths(-1) -> "-0-1",
      Period.ofYears(1) -> "1-0",
      Period.ofYears(-1) -> "-1-0",
      Period.ofYears(10).plusMonths(10) -> "10-10",
      Period.ofYears(-123).minusMonths(6) -> "-123-6",
      Period.ofMonths(Int.MaxValue) -> "178956970-7",
      Period.ofMonths(Int.MinValue) -> "-178956970-8"
    ).foreach { case (period, intervalPayload) =>
      checkEvaluation(
        Cast(Literal(period), StringType),
        s"INTERVAL '$intervalPayload' YEAR TO MONTH")
    }

    yearMonthIntervalTypes.foreach { it =>
      checkConsistencyBetweenInterpretedAndCodegen(
        (child: Expression) => Cast(child, StringType), it)
    }
  }

  test("SPARK-34668: cast day-time interval to string") {
    Seq(
      Duration.ZERO -> "0 00:00:00",
      Duration.of(1, ChronoUnit.MICROS) -> "0 00:00:00.000001",
      Duration.ofMillis(-1) -> "-0 00:00:00.001",
      Duration.ofMillis(1234) -> "0 00:00:01.234",
      Duration.ofSeconds(-9).minus(999999, ChronoUnit.MICROS) -> "-0 00:00:09.999999",
      Duration.ofMinutes(30).plusMillis(59010) -> "0 00:30:59.01",
      Duration.ofHours(-23).minusSeconds(59) -> "-0 23:00:59",
      Duration.ofDays(1).plus(12345678, ChronoUnit.MICROS) -> "1 00:00:12.345678",
      Duration.ofDays(-1234).minusHours(23).minusMinutes(59).minusSeconds(59).minusMillis(999) ->
        "-1234 23:59:59.999",
      microsToDuration(Long.MaxValue) -> "106751991 04:00:54.775807",
      microsToDuration(Long.MinValue + 1) -> "-106751991 04:00:54.775807",
      microsToDuration(Long.MinValue) -> "-106751991 04:00:54.775808"
    ).foreach { case (period, intervalPayload) =>
      checkEvaluation(
        Cast(Literal(period), StringType),
        s"INTERVAL '$intervalPayload' DAY TO SECOND")
    }

    dayTimeIntervalTypes.foreach { it =>
      checkConsistencyBetweenInterpretedAndCodegen((child: Expression) =>
        Cast(child, StringType), it)
    }
  }

  private val specialTs = Seq(
    "0001-01-01T00:00:00", // the fist timestamp of Common Era
    "1582-10-15T23:59:59", // the cutover date from Julian to Gregorian calendar
    "1970-01-01T00:00:00", // the epoch timestamp
    "9999-12-31T23:59:59"  // the last supported timestamp according to SQL standard
  )

  test("SPARK-35698: cast timestamp without time zone to string") {
    specialTs.foreach { s =>
      checkEvaluation(cast(LocalDateTime.parse(s), StringType), s.replace("T", " "))
    }
  }

  test("SPARK-35711: cast timestamp without time zone to timestamp with local time zone") {
    outstandingZoneIds.foreach { zoneId =>
      withDefaultTimeZone(zoneId) {
        specialTs.foreach { s =>
          val input = LocalDateTime.parse(s)
          val expectedTs = Timestamp.valueOf(s.replace("T", " "))
          checkEvaluation(cast(input, TimestampType), expectedTs)
        }
      }
    }
  }

  test("SPARK-35716: cast timestamp without time zone to date type") {
    specialTs.foreach { s =>
      val dt = LocalDateTime.parse(s)
      checkEvaluation(cast(dt, DateType), LocalDate.parse(s.split("T")(0)))
    }
  }

  test("SPARK-35718: cast date type to timestamp without timezone") {
    specialTs.foreach { s =>
      val inputDate = LocalDate.parse(s.split("T")(0))
      // The hour/minute/second of the expect result should be 0
      val expectedTs = LocalDateTime.parse(s.split("T")(0) + "T00:00:00")
      checkEvaluation(cast(inputDate, TimestampWithoutTZType), expectedTs)
    }
  }

  test("SPARK-35719: cast timestamp with local time zone to timestamp without timezone") {
    outstandingZoneIds.foreach { zoneId =>
      withDefaultTimeZone(zoneId) {
        specialTs.foreach { s =>
          val input = Timestamp.valueOf(s.replace("T", " "))
          val expectedTs = LocalDateTime.parse(s)
          checkEvaluation(cast(input, TimestampWithoutTZType), expectedTs)
        }
      }
    }
  }

  test("disallow type conversions between Numeric types and Timestamp without time zone type") {
    import DataTypeTestUtils.numericTypes
    checkInvalidCastFromNumericType(TimestampWithoutTZType)
    var errorMsg = "cannot cast bigint to timestamp without time zone"
    verifyCastFailure(cast(Literal(0L), TimestampWithoutTZType), Some(errorMsg))

    val timestampWithoutTZLiteral = Literal.create(LocalDateTime.now(), TimestampWithoutTZType)
    errorMsg = "cannot cast timestamp without time zone to"
    numericTypes.foreach { numericType =>
      verifyCastFailure(cast(timestampWithoutTZLiteral, numericType), Some(errorMsg))
    }
  }
}
