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
import java.time.{Duration, Period}
import java.time.temporal.ChronoUnit

import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.TypeCoercionSuite
import org.apache.spark.sql.catalyst.expressions.aggregate.{CollectList, CollectSet}
import org.apache.spark.sql.catalyst.util.DateTimeConstants._
import org.apache.spark.sql.catalyst.util.DateTimeTestUtils._
import org.apache.spark.sql.catalyst.util.DateTimeUtils._
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

/**
 * Test suite for data type casting expression [[Cast]].
 */
class CastSuite extends CastSuiteBase {

  override def cast(v: Any, targetType: DataType, timeZoneId: Option[String] = None): CastBase = {
    v match {
      case lit: Expression => Cast(lit, targetType, timeZoneId)
      case _ => Cast(Literal(v), targetType, timeZoneId)
    }
  }

  test("null cast #2") {
    import DataTypeTestUtils._

    checkNullCast(DateType, BooleanType)
    checkNullCast(TimestampType, BooleanType)
    checkNullCast(BooleanType, TimestampType)
    numericTypes.foreach(dt => checkNullCast(dt, TimestampType))
    numericTypes.foreach(dt => checkNullCast(TimestampType, dt))
    numericTypes.foreach(dt => checkNullCast(DateType, dt))
  }

  test("cast from long #2") {
    checkEvaluation(cast(123L, DecimalType(3, 1)), null)
    checkEvaluation(cast(123L, DecimalType(2, 0)), null)
  }

  test("cast from int #2") {
    checkEvaluation(cast(cast(1000, TimestampType), LongType), 1000.toLong)
    checkEvaluation(cast(cast(-1200, TimestampType), LongType), -1200.toLong)

    checkEvaluation(cast(123, DecimalType(3, 1)), null)
    checkEvaluation(cast(123, DecimalType(2, 0)), null)
  }

  test("cast string to date #2") {
    checkEvaluation(Cast(Literal("2015-03-18X"), DateType), null)
    checkEvaluation(Cast(Literal("2015/03/18"), DateType), null)
    checkEvaluation(Cast(Literal("2015.03.18"), DateType), null)
    checkEvaluation(Cast(Literal("20150318"), DateType), null)
    checkEvaluation(Cast(Literal("2015-031-8"), DateType), null)
  }

  test("casting to fixed-precision decimals") {
    assert(cast(123, DecimalType.USER_DEFAULT).nullable === false)
    assert(cast(10.03f, DecimalType.SYSTEM_DEFAULT).nullable)
    assert(cast(10.03, DecimalType.SYSTEM_DEFAULT).nullable)
    assert(cast(Decimal(10.03), DecimalType.SYSTEM_DEFAULT).nullable === false)

    assert(cast(123, DecimalType(2, 1)).nullable)
    assert(cast(10.03f, DecimalType(2, 1)).nullable)
    assert(cast(10.03, DecimalType(2, 1)).nullable)
    assert(cast(Decimal(10.03), DecimalType(2, 1)).nullable)

    assert(cast(123, DecimalType.IntDecimal).nullable === false)
    assert(cast(10.03f, DecimalType.FloatDecimal).nullable)
    assert(cast(10.03, DecimalType.DoubleDecimal).nullable)
    assert(cast(Decimal(10.03), DecimalType(4, 2)).nullable === false)
    assert(cast(Decimal(10.03), DecimalType(5, 3)).nullable === false)

    assert(cast(Decimal(10.03), DecimalType(3, 1)).nullable)
    assert(cast(Decimal(10.03), DecimalType(4, 1)).nullable === false)
    assert(cast(Decimal(9.95), DecimalType(2, 1)).nullable)
    assert(cast(Decimal(9.95), DecimalType(3, 1)).nullable === false)

    assert(cast(true, DecimalType.SYSTEM_DEFAULT).nullable === false)
    assert(cast(true, DecimalType(1, 1)).nullable)

    checkEvaluation(cast(10.03, DecimalType.SYSTEM_DEFAULT), Decimal(10.03))
    checkEvaluation(cast(10.03, DecimalType(4, 2)), Decimal(10.03))
    checkEvaluation(cast(10.03, DecimalType(3, 1)), Decimal(10.0))
    checkEvaluation(cast(10.03, DecimalType(2, 0)), Decimal(10))
    checkEvaluation(cast(10.03, DecimalType(1, 0)), null)
    checkEvaluation(cast(10.03, DecimalType(2, 1)), null)
    checkEvaluation(cast(10.03, DecimalType(3, 2)), null)
    checkEvaluation(cast(Decimal(10.03), DecimalType(3, 1)), Decimal(10.0))
    checkEvaluation(cast(Decimal(10.03), DecimalType(3, 2)), null)

    checkEvaluation(cast(10.05, DecimalType.SYSTEM_DEFAULT), Decimal(10.05))
    checkEvaluation(cast(10.05, DecimalType(4, 2)), Decimal(10.05))
    checkEvaluation(cast(10.05, DecimalType(3, 1)), Decimal(10.1))
    checkEvaluation(cast(10.05, DecimalType(2, 0)), Decimal(10))
    checkEvaluation(cast(10.05, DecimalType(1, 0)), null)
    checkEvaluation(cast(10.05, DecimalType(2, 1)), null)
    checkEvaluation(cast(10.05, DecimalType(3, 2)), null)
    checkEvaluation(cast(Decimal(10.05), DecimalType(3, 1)), Decimal(10.1))
    checkEvaluation(cast(Decimal(10.05), DecimalType(3, 2)), null)

    checkEvaluation(cast(9.95, DecimalType(3, 2)), Decimal(9.95))
    checkEvaluation(cast(9.95, DecimalType(3, 1)), Decimal(10.0))
    checkEvaluation(cast(9.95, DecimalType(2, 0)), Decimal(10))
    checkEvaluation(cast(9.95, DecimalType(2, 1)), null)
    checkEvaluation(cast(9.95, DecimalType(1, 0)), null)
    checkEvaluation(cast(Decimal(9.95), DecimalType(3, 1)), Decimal(10.0))
    checkEvaluation(cast(Decimal(9.95), DecimalType(1, 0)), null)

    checkEvaluation(cast(-9.95, DecimalType(3, 2)), Decimal(-9.95))
    checkEvaluation(cast(-9.95, DecimalType(3, 1)), Decimal(-10.0))
    checkEvaluation(cast(-9.95, DecimalType(2, 0)), Decimal(-10))
    checkEvaluation(cast(-9.95, DecimalType(2, 1)), null)
    checkEvaluation(cast(-9.95, DecimalType(1, 0)), null)
    checkEvaluation(cast(Decimal(-9.95), DecimalType(3, 1)), Decimal(-10.0))
    checkEvaluation(cast(Decimal(-9.95), DecimalType(1, 0)), null)

    checkEvaluation(cast(Decimal("1003"), DecimalType.SYSTEM_DEFAULT), Decimal(1003))
    checkEvaluation(cast(Decimal("1003"), DecimalType(4, 0)), Decimal(1003))
    checkEvaluation(cast(Decimal("1003"), DecimalType(3, 0)), null)

    checkEvaluation(cast(Decimal("995"), DecimalType(3, 0)), Decimal(995))

    checkEvaluation(cast(Double.NaN, DecimalType.SYSTEM_DEFAULT), null)
    checkEvaluation(cast(1.0 / 0.0, DecimalType.SYSTEM_DEFAULT), null)
    checkEvaluation(cast(Float.NaN, DecimalType.SYSTEM_DEFAULT), null)
    checkEvaluation(cast(1.0f / 0.0f, DecimalType.SYSTEM_DEFAULT), null)

    checkEvaluation(cast(Double.NaN, DecimalType(2, 1)), null)
    checkEvaluation(cast(1.0 / 0.0, DecimalType(2, 1)), null)
    checkEvaluation(cast(Float.NaN, DecimalType(2, 1)), null)
    checkEvaluation(cast(1.0f / 0.0f, DecimalType(2, 1)), null)

    checkEvaluation(cast(true, DecimalType(2, 1)), Decimal(1))
    checkEvaluation(cast(true, DecimalType(1, 1)), null)

    withSQLConf(SQLConf.LEGACY_ALLOW_NEGATIVE_SCALE_OF_DECIMAL_ENABLED.key -> "true") {
      assert(cast(Decimal("1003"), DecimalType(3, -1)).nullable)
      assert(cast(Decimal("1003"), DecimalType(4, -1)).nullable === false)
      assert(cast(Decimal("995"), DecimalType(2, -1)).nullable)
      assert(cast(Decimal("995"), DecimalType(3, -1)).nullable === false)

      checkEvaluation(cast(Decimal("1003"), DecimalType(3, -1)), Decimal(1000))
      checkEvaluation(cast(Decimal("1003"), DecimalType(2, -2)), Decimal(1000))
      checkEvaluation(cast(Decimal("1003"), DecimalType(1, -2)), null)
      checkEvaluation(cast(Decimal("1003"), DecimalType(2, -1)), null)

      checkEvaluation(cast(Decimal("995"), DecimalType(3, -1)), Decimal(1000))
      checkEvaluation(cast(Decimal("995"), DecimalType(2, -2)), Decimal(1000))
      checkEvaluation(cast(Decimal("995"), DecimalType(2, -1)), null)
      checkEvaluation(cast(Decimal("995"), DecimalType(1, -2)), null)
    }
  }

  test("SPARK-28470: Cast should honor nullOnOverflow property") {
    withSQLConf(SQLConf.ANSI_ENABLED.key -> "false") {
      checkEvaluation(Cast(Literal("134.12"), DecimalType(3, 2)), null)
      checkEvaluation(
        Cast(Literal(Timestamp.valueOf("2019-07-25 22:04:36")), DecimalType(3, 2)), null)
      checkEvaluation(Cast(Literal(BigDecimal(134.12)), DecimalType(3, 2)), null)
      checkEvaluation(Cast(Literal(134.12), DecimalType(3, 2)), null)
    }
  }

  test("collect_list/collect_set can cast to ArrayType not containsNull") {
    val list = CollectList(Literal(1))
    assert(Cast.canCast(list.dataType, ArrayType(IntegerType, false)))
    val set = CollectSet(Literal(1))
    assert(Cast.canCast(set.dataType, ArrayType(StringType, false)))
  }

  test("NullTypes should be able to cast to any complex types") {
    assert(Cast.canCast(ArrayType(NullType, true), ArrayType(IntegerType, true)))
    assert(Cast.canCast(ArrayType(NullType, false), ArrayType(IntegerType, true)))

    assert(Cast.canCast(
      MapType(NullType, NullType, true), MapType(IntegerType, IntegerType, true)))
    assert(Cast.canCast(
      MapType(NullType, NullType, false), MapType(IntegerType, IntegerType, true)))

    assert(Cast.canCast(
      StructType(StructField("a", NullType, true) :: Nil),
      StructType(StructField("a", IntegerType, true) :: Nil)))
    assert(Cast.canCast(
      StructType(StructField("a", NullType, false) :: Nil),
      StructType(StructField("a", IntegerType, true) :: Nil)))
  }

  test("cast string to boolean II") {
    checkEvaluation(cast("abc", BooleanType), null)
    checkEvaluation(cast("", BooleanType), null)
  }

  test("cast from array II") {
    val array = Literal.create(Seq("123", "true", "f", null),
      ArrayType(StringType, containsNull = true))
    val array_notNull = Literal.create(Seq("123", "true", "f"),
      ArrayType(StringType, containsNull = false))

    {
      val ret = cast(array, ArrayType(BooleanType, containsNull = true))
      assert(ret.resolved)
      checkEvaluation(ret, Seq(null, true, false, null))
    }

    {
      val ret = cast(array_notNull, ArrayType(BooleanType, containsNull = true))
      assert(ret.resolved)
      checkEvaluation(ret, Seq(null, true, false))
    }
  }

  test("cast from map II") {
    val map = Literal.create(
      Map("a" -> "123", "b" -> "true", "c" -> "f", "d" -> null),
      MapType(StringType, StringType, valueContainsNull = true))
    val map_notNull = Literal.create(
      Map("a" -> "123", "b" -> "true", "c" -> "f"),
      MapType(StringType, StringType, valueContainsNull = false))

    {
      val ret = cast(map, MapType(StringType, BooleanType, valueContainsNull = true))
      assert(ret.resolved)
      checkEvaluation(ret, Map("a" -> null, "b" -> true, "c" -> false, "d" -> null))
    }

    {
      val ret = cast(map_notNull, MapType(StringType, BooleanType, valueContainsNull = true))
      assert(ret.resolved)
      checkEvaluation(ret, Map("a" -> null, "b" -> true, "c" -> false))
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
      val ret = cast(struct, StructType(Seq(
        StructField("a", BooleanType, nullable = true),
        StructField("b", BooleanType, nullable = true),
        StructField("c", BooleanType, nullable = true),
        StructField("d", BooleanType, nullable = true))))
      assert(ret.resolved)
      checkEvaluation(ret, InternalRow(null, true, false, null))
    }

    {
      val ret = cast(struct_notNull, StructType(Seq(
        StructField("a", BooleanType, nullable = true),
        StructField("b", BooleanType, nullable = true),
        StructField("c", BooleanType, nullable = true))))
      assert(ret.resolved)
      checkEvaluation(ret, InternalRow(null, true, false))
    }
  }

  test("SPARK-31227: Non-nullable null type should not coerce to nullable type") {
    TypeCoercionSuite.allTypes.foreach { t =>
      assert(Cast.canCast(ArrayType(NullType, false), ArrayType(t, false)))

      assert(Cast.canCast(
        MapType(NullType, NullType, false), MapType(t, t, false)))

      assert(Cast.canCast(
        StructType(StructField("a", NullType, false) :: Nil),
        StructType(StructField("a", t, false) :: Nil)))
    }
  }

  test("Cast should output null for invalid strings when ANSI is not enabled.") {
    withSQLConf(SQLConf.ANSI_ENABLED.key -> "false") {
      checkEvaluation(cast("abdef", DecimalType.USER_DEFAULT), null)
      checkEvaluation(cast("2012-12-11", DoubleType), null)

      // cast to array
      val array = Literal.create(Seq("123", "true", "f", null),
        ArrayType(StringType, containsNull = true))
      val array_notNull = Literal.create(Seq("123", "true", "f"),
        ArrayType(StringType, containsNull = false))

      {
        val ret = cast(array, ArrayType(IntegerType, containsNull = true))
        assert(ret.resolved)
        checkEvaluation(ret, Seq(123, null, null, null))
      }
      {
        val ret = cast(array, ArrayType(IntegerType, containsNull = false))
        assert(ret.resolved === false)
      }
      {
        val ret = cast(array_notNull, ArrayType(IntegerType, containsNull = true))
        assert(ret.resolved)
        checkEvaluation(ret, Seq(123, null, null))
      }
      {
        val ret = cast(array_notNull, ArrayType(IntegerType, containsNull = false))
        assert(ret.resolved === false)
      }

      // cast from map
      val map = Literal.create(
        Map("a" -> "123", "b" -> "true", "c" -> "f", "d" -> null),
        MapType(StringType, StringType, valueContainsNull = true))
      val map_notNull = Literal.create(
        Map("a" -> "123", "b" -> "true", "c" -> "f"),
        MapType(StringType, StringType, valueContainsNull = false))

      {
        val ret = cast(map, MapType(StringType, IntegerType, valueContainsNull = true))
        assert(ret.resolved)
        checkEvaluation(ret, Map("a" -> 123, "b" -> null, "c" -> null, "d" -> null))
      }
      {
        val ret = cast(map, MapType(StringType, IntegerType, valueContainsNull = false))
        assert(ret.resolved === false)
      }
      {
        val ret = cast(map_notNull, MapType(StringType, IntegerType, valueContainsNull = true))
        assert(ret.resolved)
        checkEvaluation(ret, Map("a" -> 123, "b" -> null, "c" -> null))
      }
      {
        val ret = cast(map_notNull, MapType(StringType, IntegerType, valueContainsNull = false))
        assert(ret.resolved === false)
      }

      // cast from struct
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
          StructField("a", IntegerType, nullable = true),
          StructField("b", IntegerType, nullable = true),
          StructField("c", IntegerType, nullable = true),
          StructField("d", IntegerType, nullable = true))))
        assert(ret.resolved)
        checkEvaluation(ret, InternalRow(123, null, null, null))
      }
      {
        val ret = cast(struct, StructType(Seq(
          StructField("a", IntegerType, nullable = true),
          StructField("b", IntegerType, nullable = true),
          StructField("c", IntegerType, nullable = false),
          StructField("d", IntegerType, nullable = true))))
        assert(ret.resolved === false)
      }
      {
        val ret = cast(struct_notNull, StructType(Seq(
          StructField("a", IntegerType, nullable = true),
          StructField("b", IntegerType, nullable = true),
          StructField("c", IntegerType, nullable = true))))
        assert(ret.resolved)
        checkEvaluation(ret, InternalRow(123, null, null))
      }
      {
        val ret = cast(struct_notNull, StructType(Seq(
          StructField("a", IntegerType, nullable = true),
          StructField("b", IntegerType, nullable = true),
          StructField("c", IntegerType, nullable = false))))
        assert(ret.resolved === false)
      }

      // Invalid literals when casted to double and float results in null.
      Seq(DoubleType, FloatType).foreach { dataType =>
        checkEvaluation(cast("badvalue", dataType), null)
      }
    }
  }

  test("cast from date") {
    val d = Date.valueOf("1970-01-01")
    checkEvaluation(cast(d, ShortType), null)
    checkEvaluation(cast(d, IntegerType), null)
    checkEvaluation(cast(d, LongType), null)
    checkEvaluation(cast(d, FloatType), null)
    checkEvaluation(cast(d, DoubleType), null)
    checkEvaluation(cast(d, DecimalType.SYSTEM_DEFAULT), null)
    checkEvaluation(cast(d, DecimalType(10, 2)), null)
    checkEvaluation(cast(d, StringType), "1970-01-01")

    checkEvaluation(
      cast(cast(d, TimestampType, UTC_OPT), StringType, UTC_OPT),
      "1970-01-01 00:00:00")
  }

  test("cast from timestamp") {
    val millis = 15 * 1000 + 3
    val seconds = millis * 1000 + 3
    val ts = new Timestamp(millis)
    val tss = new Timestamp(seconds)
    checkEvaluation(cast(ts, ShortType), 15.toShort)
    checkEvaluation(cast(ts, IntegerType), 15)
    checkEvaluation(cast(ts, LongType), 15.toLong)
    checkEvaluation(cast(ts, FloatType), 15.003f)
    checkEvaluation(cast(ts, DoubleType), 15.003)

    checkEvaluation(cast(cast(tss, ShortType), TimestampType),
      fromJavaTimestamp(ts) * MILLIS_PER_SECOND)
    checkEvaluation(cast(cast(tss, IntegerType), TimestampType),
      fromJavaTimestamp(ts) * MILLIS_PER_SECOND)
    checkEvaluation(cast(cast(tss, LongType), TimestampType),
      fromJavaTimestamp(ts) * MILLIS_PER_SECOND)
    checkEvaluation(
      cast(cast(millis.toFloat / MILLIS_PER_SECOND, TimestampType), FloatType),
      millis.toFloat / MILLIS_PER_SECOND)
    checkEvaluation(
      cast(cast(millis.toDouble / MILLIS_PER_SECOND, TimestampType), DoubleType),
      millis.toDouble / MILLIS_PER_SECOND)
    checkEvaluation(
      cast(cast(Decimal(1), TimestampType), DecimalType.SYSTEM_DEFAULT),
      Decimal(1))

    // A test for higher precision than millis
    checkEvaluation(cast(cast(0.000001, TimestampType), DoubleType), 0.000001)

    checkEvaluation(cast(Double.NaN, TimestampType), null)
    checkEvaluation(cast(1.0 / 0.0, TimestampType), null)
    checkEvaluation(cast(Float.NaN, TimestampType), null)
    checkEvaluation(cast(1.0f / 0.0f, TimestampType), null)
  }

  test("cast a timestamp before the epoch 1970-01-01 00:00:00Z") {
    withDefaultTimeZone(UTC) {
      val negativeTs = Timestamp.valueOf("1900-05-05 18:34:56.1")
      assert(negativeTs.getTime < 0)
      val expectedSecs = Math.floorDiv(negativeTs.getTime, MILLIS_PER_SECOND)
      checkEvaluation(cast(negativeTs, ByteType), expectedSecs.toByte)
      checkEvaluation(cast(negativeTs, ShortType), expectedSecs.toShort)
      checkEvaluation(cast(negativeTs, IntegerType), expectedSecs.toInt)
      checkEvaluation(cast(negativeTs, LongType), expectedSecs)
    }
  }

  test("SPARK-32828: cast from a derived user-defined type to a base type") {
    val v = Literal.create(Row(1), new ExampleSubTypeUDT())
    checkEvaluation(cast(v, new ExampleBaseTypeUDT), Row(1))
  }

  test("Fast fail for cast string type to decimal type") {
    checkEvaluation(cast("12345678901234567890123456789012345678", DecimalType(38, 0)),
      Decimal("12345678901234567890123456789012345678"))
    checkEvaluation(cast("123456789012345678901234567890123456789", DecimalType(38, 0)), null)
    checkEvaluation(cast("12345678901234567890123456789012345678", DecimalType(38, 1)), null)

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
    checkEvaluation(cast("6E+38", DecimalType(38, 0)), null)
    checkEvaluation(cast("6E+37", DecimalType(38, 1)), null)

    checkEvaluation(cast("abcd", DecimalType(38, 1)), null)
  }

  test("data type casting II") {
    checkEvaluation(
      cast(cast(cast(cast(cast(cast("5", ByteType), TimestampType),
        DecimalType.SYSTEM_DEFAULT), LongType), StringType), ShortType),
        5.toShort)
      checkEvaluation(
        cast(cast(cast(cast(cast(cast("5", TimestampType, UTC_OPT), ByteType),
          DecimalType.SYSTEM_DEFAULT), LongType), StringType), ShortType),
        null)
      checkEvaluation(cast(cast(cast(cast(cast(cast("5", DecimalType.SYSTEM_DEFAULT),
        ByteType), TimestampType), LongType), StringType), ShortType),
        5.toShort)
  }

  test("Cast from double II") {
    checkEvaluation(cast(cast(1.toDouble, TimestampType), DoubleType), 1.toDouble)
  }

  test("SPARK-34727: cast from float II") {
    checkCast(16777215.0f, java.time.Instant.ofEpochSecond(16777215))
  }

  test("SPARK-34744: Improve error message for casting cause overflow error") {
    withSQLConf(SQLConf.ANSI_ENABLED.key -> "true") {
      val e1 = intercept[ArithmeticException] {
        Cast(Literal(Byte.MaxValue + 1), ByteType).eval()
      }.getMessage
      assert(e1.contains("Casting 128 to tinyint causes overflow"))
      val e2 = intercept[ArithmeticException] {
        Cast(Literal(Short.MaxValue + 1), ShortType).eval()
      }.getMessage
      assert(e2.contains("Casting 32768 to smallint causes overflow"))
      val e3 = intercept[ArithmeticException] {
        Cast(Literal(Int.MaxValue + 1L), IntegerType).eval()
      }.getMessage
      assert(e3.contains("Casting 2147483648 to int causes overflow"))
    }
  }

  test("SPARK-35112: Cast string to day-time interval") {
    checkEvaluation(cast(Literal.create("0 0:0:0"), DayTimeIntervalType()), 0L)
    checkEvaluation(cast(Literal.create(" interval '0 0:0:0' Day TO second   "),
      DayTimeIntervalType()), 0L)
    checkEvaluation(cast(Literal.create("INTERVAL '1 2:03:04' DAY TO SECOND"),
      DayTimeIntervalType()), 93784000000L)
    checkEvaluation(cast(Literal.create("INTERVAL '1 03:04:00' DAY TO SECOND"),
      DayTimeIntervalType()), 97440000000L)
    checkEvaluation(cast(Literal.create("INTERVAL '1 03:04:00.0000' DAY TO SECOND"),
      DayTimeIntervalType()), 97440000000L)
    checkEvaluation(cast(Literal.create("1 2:03:04"), DayTimeIntervalType()), 93784000000L)
    checkEvaluation(cast(Literal.create("INTERVAL '-10 2:03:04' DAY TO SECOND"),
      DayTimeIntervalType()), -871384000000L)
    checkEvaluation(cast(Literal.create("-10 2:03:04"), DayTimeIntervalType()), -871384000000L)
    checkEvaluation(cast(Literal.create("-106751991 04:00:54.775808"), DayTimeIntervalType()),
      Long.MinValue)
    checkEvaluation(cast(Literal.create("106751991 04:00:54.775807"), DayTimeIntervalType()),
      Long.MaxValue)

    Seq("-106751991 04:00:54.775808", "106751991 04:00:54.775807").foreach { interval =>
      val ansiInterval = s"INTERVAL '$interval' DAY TO SECOND"
      checkEvaluation(
        cast(cast(Literal.create(interval), DayTimeIntervalType()), StringType), ansiInterval)
      checkEvaluation(cast(cast(Literal.create(ansiInterval),
        DayTimeIntervalType()), StringType), ansiInterval)
    }

    Seq("INTERVAL '-106751991 04:00:54.775809' YEAR TO MONTH",
      "INTERVAL '106751991 04:00:54.775808' YEAR TO MONTH").foreach { interval =>
        val e = intercept[IllegalArgumentException] {
          cast(Literal.create(interval), DayTimeIntervalType()).eval()
        }.getMessage
        assert(e.contains("Interval string must match day-time format of"))
      }

    Seq(Byte.MaxValue, Short.MaxValue, Int.MaxValue, Long.MaxValue, Long.MinValue + 1,
      Long.MinValue).foreach { duration =>
        val interval = Literal.create(
          Duration.of(duration, ChronoUnit.MICROS),
          DayTimeIntervalType())
        checkEvaluation(cast(cast(interval, StringType), DayTimeIntervalType()), duration)
      }
  }

  test("SPARK-35111: Cast string to year-month interval") {
    checkEvaluation(cast(Literal.create("INTERVAL '1-0' YEAR TO MONTH"),
      YearMonthIntervalType()), 12)
    checkEvaluation(cast(Literal.create("INTERVAL '-1-0' YEAR TO MONTH"),
      YearMonthIntervalType()), -12)
    checkEvaluation(cast(Literal.create("INTERVAL -'-1-0' YEAR TO MONTH"),
      YearMonthIntervalType()), 12)
    checkEvaluation(cast(Literal.create("INTERVAL +'-1-0' YEAR TO MONTH"),
      YearMonthIntervalType()), -12)
    checkEvaluation(cast(Literal.create("INTERVAL +'+1-0' YEAR TO MONTH"),
      YearMonthIntervalType()), 12)
    checkEvaluation(cast(Literal.create("INTERVAL +'1-0' YEAR TO MONTH"),
      YearMonthIntervalType()), 12)
    checkEvaluation(cast(Literal.create(" interval +'1-0' YEAR  TO MONTH "),
      YearMonthIntervalType()), 12)
    checkEvaluation(cast(Literal.create(" -1-0 "), YearMonthIntervalType()), -12)
    checkEvaluation(cast(Literal.create("-1-0"), YearMonthIntervalType()), -12)
    checkEvaluation(cast(Literal.create(null, StringType), YearMonthIntervalType()), null)

    Seq("0-0", "10-1", "-178956970-7", "178956970-7", "-178956970-8").foreach { interval =>
      val ansiInterval = s"INTERVAL '$interval' YEAR TO MONTH"
      checkEvaluation(
        cast(cast(Literal.create(interval), YearMonthIntervalType()), StringType), ansiInterval)
      checkEvaluation(cast(cast(Literal.create(ansiInterval),
        YearMonthIntervalType()), StringType), ansiInterval)
    }

    Seq("INTERVAL '-178956970-9' YEAR TO MONTH", "INTERVAL '178956970-8' YEAR TO MONTH")
      .foreach { interval =>
        val e = intercept[IllegalArgumentException] {
          cast(Literal.create(interval), YearMonthIntervalType()).eval()
        }.getMessage
        assert(e.contains("Error parsing interval year-month string: integer overflow"))
      }

    Seq(Byte.MaxValue, Short.MaxValue, Int.MaxValue, Int.MinValue + 1, Int.MinValue)
      .foreach { period =>
        val interval = Literal.create(Period.ofMonths(period), YearMonthIntervalType())
        checkEvaluation(cast(cast(interval, StringType), YearMonthIntervalType()), period)
      }
  }
}
