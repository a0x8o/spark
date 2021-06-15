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

import java.time.DateTimeException

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.util.DateTimeTestUtils
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

abstract class AnsiCastSuiteBase extends CastSuiteBase {

  private def testIntMaxAndMin(dt: DataType): Unit = {
    assert(Seq(IntegerType, ShortType, ByteType).contains(dt))
    Seq(Int.MaxValue + 1L, Int.MinValue - 1L).foreach { value =>
      checkExceptionInExpression[ArithmeticException](cast(value, dt), "overflow")
      checkExceptionInExpression[ArithmeticException](cast(Decimal(value.toString), dt), "overflow")
      checkExceptionInExpression[ArithmeticException](
        cast(Literal(value * 1.5f, FloatType), dt), "overflow")
      checkExceptionInExpression[ArithmeticException](
        cast(Literal(value * 1.0, DoubleType), dt), "overflow")
    }
  }

  private def testLongMaxAndMin(dt: DataType): Unit = {
    assert(Seq(LongType, IntegerType).contains(dt))
    Seq(Decimal(Long.MaxValue) + Decimal(1), Decimal(Long.MinValue) - Decimal(1)).foreach { value =>
      checkExceptionInExpression[ArithmeticException](
        cast(value, dt), "overflow")
      checkExceptionInExpression[ArithmeticException](
        cast((value * Decimal(1.1)).toFloat, dt), "overflow")
      checkExceptionInExpression[ArithmeticException](
        cast((value * Decimal(1.1)).toDouble, dt), "overflow")
    }
  }

  test("ANSI mode: Throw exception on casting out-of-range value to byte type") {
    testIntMaxAndMin(ByteType)
    Seq(Byte.MaxValue + 1, Byte.MinValue - 1).foreach { value =>
      checkExceptionInExpression[ArithmeticException](cast(value, ByteType), "overflow")
      checkExceptionInExpression[ArithmeticException](
        cast(Literal(value.toFloat, FloatType), ByteType), "overflow")
      checkExceptionInExpression[ArithmeticException](
        cast(Literal(value.toDouble, DoubleType), ByteType), "overflow")
    }

    Seq(Byte.MaxValue, 0.toByte, Byte.MinValue).foreach { value =>
      checkEvaluation(cast(value, ByteType), value)
      checkEvaluation(cast(value.toString, ByteType), value)
      checkEvaluation(cast(Decimal(value.toString), ByteType), value)
      checkEvaluation(cast(Literal(value.toFloat, FloatType), ByteType), value)
      checkEvaluation(cast(Literal(value.toDouble, DoubleType), ByteType), value)
    }
  }

  test("ANSI mode: Throw exception on casting out-of-range value to short type") {
    testIntMaxAndMin(ShortType)
    Seq(Short.MaxValue + 1, Short.MinValue - 1).foreach { value =>
      checkExceptionInExpression[ArithmeticException](cast(value, ShortType), "overflow")
      checkExceptionInExpression[ArithmeticException](
        cast(Literal(value.toFloat, FloatType), ShortType), "overflow")
      checkExceptionInExpression[ArithmeticException](
        cast(Literal(value.toDouble, DoubleType), ShortType), "overflow")
    }

    Seq(Short.MaxValue, 0.toShort, Short.MinValue).foreach { value =>
      checkEvaluation(cast(value, ShortType), value)
      checkEvaluation(cast(value.toString, ShortType), value)
      checkEvaluation(cast(Decimal(value.toString), ShortType), value)
      checkEvaluation(cast(Literal(value.toFloat, FloatType), ShortType), value)
      checkEvaluation(cast(Literal(value.toDouble, DoubleType), ShortType), value)
    }
  }

  test("ANSI mode: Throw exception on casting out-of-range value to int type") {
    testIntMaxAndMin(IntegerType)
    testLongMaxAndMin(IntegerType)

    Seq(Int.MaxValue, 0, Int.MinValue).foreach { value =>
      checkEvaluation(cast(value, IntegerType), value)
      checkEvaluation(cast(value.toString, IntegerType), value)
      checkEvaluation(cast(Decimal(value.toString), IntegerType), value)
      checkEvaluation(cast(Literal(value * 1.0, DoubleType), IntegerType), value)
    }
    checkEvaluation(cast(Int.MaxValue + 0.9D, IntegerType), Int.MaxValue)
    checkEvaluation(cast(Int.MinValue - 0.9D, IntegerType), Int.MinValue)
  }

  test("ANSI mode: Throw exception on casting out-of-range value to long type") {
    testLongMaxAndMin(LongType)

    Seq(Long.MaxValue, 0, Long.MinValue).foreach { value =>
      checkEvaluation(cast(value, LongType), value)
      checkEvaluation(cast(value.toString, LongType), value)
      checkEvaluation(cast(Decimal(value.toString), LongType), value)
    }
    checkEvaluation(cast(Long.MaxValue + 0.9F, LongType), Long.MaxValue)
    checkEvaluation(cast(Long.MinValue - 0.9F, LongType), Long.MinValue)
    checkEvaluation(cast(Long.MaxValue + 0.9D, LongType), Long.MaxValue)
    checkEvaluation(cast(Long.MinValue - 0.9D, LongType), Long.MinValue)
  }

  test("ANSI mode: Throw exception on casting out-of-range value to decimal type") {
    checkExceptionInExpression[ArithmeticException](
      cast(Literal("134.12"), DecimalType(3, 2)), "cannot be represented")
    checkExceptionInExpression[ArithmeticException](
      cast(Literal(BigDecimal(134.12)), DecimalType(3, 2)), "cannot be represented")
    checkExceptionInExpression[ArithmeticException](
      cast(Literal(134.12), DecimalType(3, 2)), "cannot be represented")
  }

  test("ANSI mode: disallow type conversions between Numeric types and Timestamp type") {
    import DataTypeTestUtils.numericTypes
    checkInvalidCastFromNumericType(TimestampType)
    var errorMsg =
      "you can use functions TIMESTAMP_SECONDS/TIMESTAMP_MILLIS/TIMESTAMP_MICROS instead"
    verifyCastFailure(cast(Literal(0L), TimestampType), Some(errorMsg))

    val timestampLiteral = Literal(1L, TimestampType)
    errorMsg = "you can use functions UNIX_SECONDS/UNIX_MILLIS/UNIX_MICROS instead."
    numericTypes.foreach { numericType =>
      verifyCastFailure(cast(timestampLiteral, numericType), Some(errorMsg))
    }
  }

  test("ANSI mode: disallow type conversions between Numeric types and Date type") {
    import DataTypeTestUtils.numericTypes
    checkInvalidCastFromNumericType(DateType)
    var errorMsg = "you can use function DATE_FROM_UNIX_DATE instead"
    verifyCastFailure(cast(Literal(0L), DateType), Some(errorMsg))
    val dateLiteral = Literal(1, DateType)
    errorMsg = "you can use function UNIX_DATE instead"
    numericTypes.foreach { numericType =>
      verifyCastFailure(cast(dateLiteral, numericType), Some(errorMsg))
    }
  }

  test("ANSI mode: disallow type conversions between Numeric types and Binary type") {
    import DataTypeTestUtils.numericTypes
    checkInvalidCastFromNumericType(BinaryType)
    val binaryLiteral = Literal(new Array[Byte](1.toByte), BinaryType)
    numericTypes.foreach { numericType =>
      assert(cast(binaryLiteral, numericType).checkInputDataTypes().isFailure)
    }
  }

  test("ANSI mode: disallow type conversions between Datatime types and Boolean types") {
    val timestampLiteral = Literal(1L, TimestampType)
    assert(cast(timestampLiteral, BooleanType).checkInputDataTypes().isFailure)
    val dateLiteral = Literal(1, DateType)
    assert(cast(dateLiteral, BooleanType).checkInputDataTypes().isFailure)

    val booleanLiteral = Literal(true, BooleanType)
    assert(cast(booleanLiteral, TimestampType).checkInputDataTypes().isFailure)
    assert(cast(booleanLiteral, DateType).checkInputDataTypes().isFailure)
  }

  test("cast from invalid string to numeric should throw NumberFormatException") {
    // cast to IntegerType
    Seq(IntegerType, ShortType, ByteType, LongType).foreach { dataType =>
      checkExceptionInExpression[NumberFormatException](
        cast("string", dataType), "invalid input syntax for type numeric: string")
      checkExceptionInExpression[NumberFormatException](
        cast("123-string", dataType), "invalid input syntax for type numeric: 123-string")
      checkExceptionInExpression[NumberFormatException](
        cast("2020-07-19", dataType), "invalid input syntax for type numeric: 2020-07-19")
      checkExceptionInExpression[NumberFormatException](
        cast("1.23", dataType), "invalid input syntax for type numeric: 1.23")
    }

    Seq(DoubleType, FloatType, DecimalType.USER_DEFAULT).foreach { dataType =>
      checkExceptionInExpression[NumberFormatException](
        cast("string", dataType), "invalid input syntax for type numeric: string")
      checkExceptionInExpression[NumberFormatException](
        cast("123.000.00", dataType), "invalid input syntax for type numeric: 123.000.00")
      checkExceptionInExpression[NumberFormatException](
        cast("abc.com", dataType), "invalid input syntax for type numeric: abc.com")
    }
  }

  protected def checkCastToNumericError(l: Literal, to: DataType, tryCastResult: Any): Unit = {
    checkExceptionInExpression[NumberFormatException](
      cast(l, to), "invalid input syntax for type numeric: true")
  }

  test("cast from invalid string array to numeric array should throw NumberFormatException") {
    val array = Literal.create(Seq("123", "true", "f", null),
      ArrayType(StringType, containsNull = true))

    checkCastToNumericError(array, ArrayType(ByteType, containsNull = true),
      Seq(123.toByte, null, null, null))
    checkCastToNumericError(array, ArrayType(ShortType, containsNull = true),
      Seq(123.toShort, null, null, null))
    checkCastToNumericError(array, ArrayType(IntegerType, containsNull = true),
      Seq(123, null, null, null))
    checkCastToNumericError(array, ArrayType(LongType, containsNull = true),
      Seq(123L, null, null, null))
  }

  test("Fast fail for cast string type to decimal type in ansi mode") {
    checkEvaluation(cast("12345678901234567890123456789012345678", DecimalType(38, 0)),
      Decimal("12345678901234567890123456789012345678"))
    checkExceptionInExpression[ArithmeticException](
      cast("123456789012345678901234567890123456789", DecimalType(38, 0)),
      "out of decimal type range")
    checkExceptionInExpression[ArithmeticException](
      cast("12345678901234567890123456789012345678", DecimalType(38, 1)),
      "cannot be represented as Decimal(38, 1)")

    checkEvaluation(cast("0.00000000000000000000000000000000000001", DecimalType(38, 0)),
      Decimal("0"))
    checkEvaluation(cast("0.00000000000000000000000000000000000000000001", DecimalType(38, 0)),
      Decimal("0"))
    checkEvaluation(cast("0.00000000000000000000000000000000000001", DecimalType(38, 18)),
      Decimal("0E-18"))
    checkEvaluation(cast("6E-120", DecimalType(38, 0)),
      Decimal("0"))

    checkEvaluation(cast("6E+37", DecimalType(38, 0)),
      Decimal("60000000000000000000000000000000000000"))
    checkExceptionInExpression[ArithmeticException](
      cast("6E+38", DecimalType(38, 0)),
      "out of decimal type range")
    checkExceptionInExpression[ArithmeticException](
      cast("6E+37", DecimalType(38, 1)),
      "cannot be represented as Decimal(38, 1)")

    checkExceptionInExpression[NumberFormatException](
      cast("abcd", DecimalType(38, 1)),
      "invalid input syntax for type numeric")
  }

  protected def checkCastToBooleanError(l: Literal, to: DataType, tryCastResult: Any): Unit = {
    checkExceptionInExpression[UnsupportedOperationException](
      cast(l, to), s"invalid input syntax for type boolean")
  }

  test("ANSI mode: cast string to boolean with parse error") {
    checkCastToBooleanError(Literal("abc"), BooleanType, null)
    checkCastToBooleanError(Literal(""), BooleanType, null)
  }

  test("cast from array II") {
    val array = Literal.create(Seq("123", "true", "f", null),
      ArrayType(StringType, containsNull = true))
    val array_notNull = Literal.create(Seq("123", "true", "f"),
      ArrayType(StringType, containsNull = false))

    {
      val to: DataType = ArrayType(BooleanType, containsNull = true)
      val ret = cast(array, to)
      assert(ret.resolved)
      checkCastToBooleanError(array, to, Seq(null, true, false, null))
    }

    {
      val to: DataType = ArrayType(BooleanType, containsNull = true)
      val ret = cast(array_notNull, to)
      assert(ret.resolved)
      checkCastToBooleanError(array_notNull, to, Seq(null, true, false))
    }
  }

  test("cast from map II") {
    val map = Literal.create(
      Map("a" -> "123", "b" -> "true", "c" -> "f", "d" -> null),
      MapType(StringType, StringType, valueContainsNull = true))
    val map_notNull = Literal.create(
      Map("a" -> "123", "b" -> "true", "c" -> "f"),
      MapType(StringType, StringType, valueContainsNull = false))

    checkNullCast(MapType(StringType, IntegerType), MapType(StringType, StringType))

    {
      val to: DataType = MapType(StringType, BooleanType, valueContainsNull = true)
      val ret = cast(map, to)
      assert(ret.resolved)
      checkCastToBooleanError(map, to, Map("a" -> null, "b" -> true, "c" -> false, "d" -> null))
    }

    {
      val to: DataType = MapType(StringType, BooleanType, valueContainsNull = true)
      val ret = cast(map_notNull, to)
      assert(ret.resolved)
      checkCastToBooleanError(map_notNull, to, Map("a" -> null, "b" -> true, "c" -> false))
    }
  }

  test("cast from struct II") {
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
      val to: DataType = StructType(Seq(
        StructField("a", BooleanType, nullable = true),
        StructField("b", BooleanType, nullable = true),
        StructField("c", BooleanType, nullable = true),
        StructField("d", BooleanType, nullable = true)))
      val ret = cast(struct, to)
      assert(ret.resolved)
      checkCastToBooleanError(struct, to, InternalRow(null, true, false, null))
    }

    {
      val to: DataType = StructType(Seq(
        StructField("a", BooleanType, nullable = true),
        StructField("b", BooleanType, nullable = true),
        StructField("c", BooleanType, nullable = true)))
      val ret = cast(struct_notNull, to)
      assert(ret.resolved)
      checkCastToBooleanError(struct_notNull, to, InternalRow(null, true, false))
    }
  }

  test("ANSI mode: cast string to timestamp with parse error") {
    DateTimeTestUtils.outstandingZoneIds.foreach { zid =>
      def checkCastWithParseError(str: String): Unit = {
        checkExceptionInExpression[DateTimeException](
          cast(Literal(str), TimestampType, Option(zid.getId)),
          s"Cannot cast $str to TimestampType.")
      }

      checkCastWithParseError("123")
      checkCastWithParseError("2015-03-18 123142")
      checkCastWithParseError("2015-03-18T123123")
      checkCastWithParseError("2015-03-18X")
      checkCastWithParseError("2015/03/18")
      checkCastWithParseError("2015.03.18")
      checkCastWithParseError("20150318")
      checkCastWithParseError("2015-031-8")
      checkCastWithParseError("2015-03-18T12:03:17-0:70")
      checkCastWithParseError("abdef")
    }
  }

  test("ANSI mode: cast string to date with parse error") {
    DateTimeTestUtils.outstandingZoneIds.foreach { zid =>
      def checkCastWithParseError(str: String): Unit = {
        checkExceptionInExpression[DateTimeException](
          cast(Literal(str), DateType, Option(zid.getId)),
          s"Cannot cast $str to DateType.")
      }

      checkCastWithParseError("12345")
      checkCastWithParseError("12345-12-18")
      checkCastWithParseError("2015-13-18")
      checkCastWithParseError("2015-03-128")
      checkCastWithParseError("2015/03/18")
      checkCastWithParseError("2015.03.18")
      checkCastWithParseError("20150318")
      checkCastWithParseError("2015-031-8")
      checkCastWithParseError("2015-03-18ABC")
      checkCastWithParseError("abdef")
    }
  }

  test("SPARK-26218: Fix the corner case of codegen when casting float to Integer") {
    checkExceptionInExpression[ArithmeticException](
      cast(cast(Literal("2147483648"), FloatType), IntegerType), "overflow")
  }
}

/**
 * Test suite for data type casting expression [[Cast]] with ANSI mode disabled.
 */
class CastSuiteWithAnsiModeOn extends AnsiCastSuiteBase {
  override def beforeAll(): Unit = {
    super.beforeAll()
    SQLConf.get.setConf(SQLConf.ANSI_ENABLED, true)
  }

  override def afterAll(): Unit = {
    super.afterAll()
    SQLConf.get.unsetConf(SQLConf.ANSI_ENABLED)
  }

  override def cast(v: Any, targetType: DataType, timeZoneId: Option[String] = None): CastBase = {
    v match {
      case lit: Expression => Cast(lit, targetType, timeZoneId)
      case _ => Cast(Literal(v), targetType, timeZoneId)
    }
  }

  override def setConfigurationHint: String =
    s"set ${SQLConf.ANSI_ENABLED.key} as false"
}

/**
 * Test suite for data type casting expression [[AnsiCast]] with ANSI mode enabled.
 */
class AnsiCastSuiteWithAnsiModeOn extends AnsiCastSuiteBase {
  override def beforeAll(): Unit = {
    super.beforeAll()
    SQLConf.get.setConf(SQLConf.ANSI_ENABLED, true)
  }

  override def afterAll(): Unit = {
    super.afterAll()
    SQLConf.get.unsetConf(SQLConf.ANSI_ENABLED)
  }

  override def cast(v: Any, targetType: DataType, timeZoneId: Option[String] = None): CastBase = {
    v match {
      case lit: Expression => AnsiCast(lit, targetType, timeZoneId)
      case _ => AnsiCast(Literal(v), targetType, timeZoneId)
    }
  }

  override def setConfigurationHint: String =
    s"set ${SQLConf.STORE_ASSIGNMENT_POLICY.key} as" +
      s" ${SQLConf.StoreAssignmentPolicy.LEGACY.toString}"
}

/**
 * Test suite for data type casting expression [[AnsiCast]] with ANSI mode disabled.
 */
class AnsiCastSuiteWithAnsiModeOff extends AnsiCastSuiteBase {
  override def beforeAll(): Unit = {
    super.beforeAll()
    SQLConf.get.setConf(SQLConf.ANSI_ENABLED, false)
  }

  override def afterAll(): Unit = {
    super.afterAll()
    SQLConf.get.unsetConf(SQLConf.ANSI_ENABLED)
  }

  override def cast(v: Any, targetType: DataType, timeZoneId: Option[String] = None): CastBase = {
    v match {
      case lit: Expression => AnsiCast(lit, targetType, timeZoneId)
      case _ => AnsiCast(Literal(v), targetType, timeZoneId)
    }
  }

  override def setConfigurationHint: String =
    s"set ${SQLConf.STORE_ASSIGNMENT_POLICY.key} as" +
      s" ${SQLConf.StoreAssignmentPolicy.LEGACY.toString}"
}
