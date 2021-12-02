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

package org.apache.spark.sql.catalyst.analysis

import org.apache.spark.sql.catalyst.analysis.TypeCoercion.numericPrecedence
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.types._

/**
 * In Spark ANSI mode, the type coercion rules are based on the type precedence lists of the input
 * data types.
 * As per the section "Type precedence list determination" of "ISO/IEC 9075-2:2011
 * Information technology - Database languages - SQL - Part 2: Foundation (SQL/Foundation)",
 * the type precedence lists of primitive data types are as following:
 *   * Byte: Byte, Short, Int, Long, Decimal, Float, Double
 *   * Short: Short, Int, Long, Decimal, Float, Double
 *   * Int: Int, Long, Decimal, Float, Double
 *   * Long: Long, Decimal, Float, Double
 *   * Decimal: Float, Double, or any wider Numeric type
 *   * Float: Float, Double
 *   * Double: Double
 *   * String: String
 *   * Date: Date, Timestamp
 *   * Timestamp: Timestamp
 *   * Binary: Binary
 *   * Boolean: Boolean
 *   * Interval: Interval
 * As for complex data types, Spark will determine the precedent list recursively based on their
 * sub-types and nullability.
 *
 * With the definition of type precedent list, the general type coercion rules are as following:
 *   * Data type S is allowed to be implicitly cast as type T iff T is in the precedence list of S
 *   * Comparison is allowed iff the data type precedence list of both sides has at least one common
 *     element. When evaluating the comparison, Spark casts both sides as the tightest common data
 *     type of their precedent lists.
 *   * There should be at least one common data type among all the children's precedence lists for
 *     the following operators. The data type of the operator is the tightest common precedent
 *     data type.
 *       * In
 *       * Except
 *       * Intersect
 *       * Greatest
 *       * Least
 *       * Union
 *       * If
 *       * CaseWhen
 *       * CreateArray
 *       * Array Concat
 *       * Sequence
 *       * MapConcat
 *       * CreateMap
 *   * For complex types (struct, array, map), Spark recursively looks into the element type and
 *     applies the rules above.
 *  Note: this new type coercion system will allow implicit converting String type literals as other
 *  primitive types, in case of breaking too many existing Spark SQL queries. This is a special
 *  rule and it is not from the ANSI SQL standard.
 */
object AnsiTypeCoercion extends TypeCoercionBase {
  override def typeCoercionRules: List[Rule[LogicalPlan]] =
    WidenSetOperationTypes ::
    new AnsiCombinedTypeCoercionRule(
      InConversion ::
      PromoteStringLiterals ::
      DecimalPrecision ::
      FunctionArgumentConversion ::
      ConcatCoercion ::
      MapZipWithCoercion ::
      EltCoercion ::
      CaseWhenCoercion ::
      IfCoercion ::
      StackCoercion ::
      Division ::
      IntegralDivision ::
      ImplicitTypeCasts ::
      DateTimeOperations ::
      WindowFrameCoercion ::
      GetDateFieldOperations:: Nil) :: Nil

  val findTightestCommonType: (DataType, DataType) => Option[DataType] = {
    case (t1, t2) if t1 == t2 => Some(t1)
    case (NullType, t1) => Some(t1)
    case (t1, NullType) => Some(t1)

    case (t1: IntegralType, t2: DecimalType) if t2.isWiderThan(t1) =>
      Some(t2)
    case (t1: DecimalType, t2: IntegralType) if t1.isWiderThan(t2) =>
      Some(t1)

    case (t1: NumericType, t2: NumericType)
        if !t1.isInstanceOf[DecimalType] && !t2.isInstanceOf[DecimalType] =>
      val index = numericPrecedence.lastIndexWhere(t => t == t1 || t == t2)
      val widerType = numericPrecedence(index)
      if (widerType == FloatType) {
        // If the input type is an Integral type and a Float type, simply return Double type as
        // the tightest common type to avoid potential precision loss on converting the Integral
        // type as Float type.
        Some(DoubleType)
      } else {
        Some(widerType)
      }

    case (_: TimestampType, _: DateType) | (_: DateType, _: TimestampType) =>
      Some(TimestampType)

    case (t1: DayTimeIntervalType, t2: DayTimeIntervalType) =>
      Some(DayTimeIntervalType(t1.startField.min(t2.startField), t1.endField.max(t2.endField)))
    case (t1: YearMonthIntervalType, t2: YearMonthIntervalType) =>
      Some(YearMonthIntervalType(t1.startField.min(t2.startField), t1.endField.max(t2.endField)))

    case (t1, t2) => findTypeForComplex(t1, t2, findTightestCommonType)
  }

  override def findWiderTypeForTwo(t1: DataType, t2: DataType): Option[DataType] = {
    findTightestCommonType(t1, t2)
      .orElse(findWiderTypeForDecimal(t1, t2))
      .orElse(findTypeForComplex(t1, t2, findWiderTypeForTwo))
  }

  override def findWiderCommonType(types: Seq[DataType]): Option[DataType] = {
    types.foldLeft[Option[DataType]](Some(NullType))((r, c) =>
      r match {
        case Some(d) => findWiderTypeForTwo(d, c)
        case _ => None
      })
  }

  override def implicitCast(e: Expression, expectedType: AbstractDataType): Option[Expression] = {
    implicitCast(e.dataType, expectedType, e.foldable).map { dt =>
      if (dt == e.dataType) e else Cast(e, dt)
    }
  }

  /**
   * In Ansi mode, the implicit cast is only allow when `expectedType` is in the type precedent
   * list of `inType`.
   */
  private def implicitCast(
      inType: DataType,
      expectedType: AbstractDataType,
      isInputFoldable: Boolean): Option[DataType] = {
    (inType, expectedType) match {
      // If the expected type equals the input type, no need to cast.
      case _ if expectedType.acceptsType(inType) => Some(inType)

      // If input is a numeric type but not decimal, and we expect a decimal type,
      // cast the input to decimal.
      case (n: NumericType, DecimalType) => Some(DecimalType.forType(n))

      // Cast null type (usually from null literals) into target types
      // By default, the result type is `target.defaultConcreteType`. When the target type is
      // `TypeCollection`, there is another branch to find the "closet convertible data type" below.
      case (NullType, target) if !target.isInstanceOf[TypeCollection] =>
        Some(target.defaultConcreteType)

      // This type coercion system will allow implicit converting String type literals as other
      // primitive types, in case of breaking too many existing Spark SQL queries.
      case (StringType, a: AtomicType) if isInputFoldable =>
        Some(a)

      // If the target type is any Numeric type, convert the String type literal as Double type.
      case (StringType, NumericType) if isInputFoldable =>
        Some(DoubleType)

      // If the target type is any Decimal type, convert the String type literal as Double type.
      case (StringType, DecimalType) if isInputFoldable =>
        Some(DecimalType.SYSTEM_DEFAULT)

      case (_, target: DataType) =>
        if (Cast.canANSIStoreAssign(inType, target)) {
          Some(target)
        } else {
          None
        }

      // When we reach here, input type is not acceptable for any types in this type collection,
      // try to find the first one we can implicitly cast.
      case (_, TypeCollection(types)) =>
        types.flatMap(implicitCast(inType, _, isInputFoldable)).headOption

      case _ => None
    }
  }

  override def canCast(from: DataType, to: DataType): Boolean = AnsiCast.canCast(from, to)

  /**
   * Promotes string literals that appear in arithmetic, comparison, and datetime expressions.
   */
  object PromoteStringLiterals extends TypeCoercionRule {
    private def castExpr(expr: Expression, targetType: DataType): Expression = {
      expr.dataType match {
        case NullType => Literal.create(null, targetType)
        case l if l != targetType => Cast(expr, targetType)
        case _ => expr
      }
    }

    // Return whether a string literal can be promoted as the give data type in a binary operation.
    private def canPromoteAsInBinaryOperation(dt: DataType) = dt match {
      // If a binary operation contains interval type and string literal, we can't decide which
      // interval type the string literal should be promoted as. There are many possible interval
      // types, such as year interval, month interval, day interval, hour interval, etc.
      case _: AnsiIntervalType => false
      case _: AtomicType => true
      case _ => false
    }

    override def transform: PartialFunction[Expression, Expression] = {
      // Skip nodes who's children have not been resolved yet.
      case e if !e.childrenResolved => e

      case b @ BinaryOperator(left @ StringType(), right)
        if left.foldable && canPromoteAsInBinaryOperation(right.dataType) =>
        b.makeCopy(Array(castExpr(left, right.dataType), right))

      case b @ BinaryOperator(left, right @ StringType())
        if right.foldable && canPromoteAsInBinaryOperation(left.dataType) =>
        b.makeCopy(Array(left, castExpr(right, left.dataType)))

      case Abs(e @ StringType(), failOnError) if e.foldable => Abs(Cast(e, DoubleType), failOnError)
      case m @ UnaryMinus(e @ StringType(), _) if e.foldable =>
        m.withNewChildren(Seq(Cast(e, DoubleType)))
      case UnaryPositive(e @ StringType()) if e.foldable => UnaryPositive(Cast(e, DoubleType))

      // Promotes string literals in `In predicate`.
      case p @ In(a, b)
        if a.dataType != StringType && b.exists( e => e.foldable && e.dataType == StringType) =>
        val newList = b.map {
          case e @ StringType() if e.foldable => Cast(e, a.dataType)
          case other => other
        }
        p.makeCopy(Array(a, newList))

      case d @ DateAdd(left @ StringType(), _) if left.foldable =>
        d.copy(startDate = Cast(d.startDate, DateType))
      case d @ DateAdd(_, right @ StringType()) if right.foldable =>
        d.copy(days = Cast(right, IntegerType))
      case d @ DateSub(left @ StringType(), _) if left.foldable =>
        d.copy(startDate = Cast(d.startDate, DateType))
      case d @ DateSub(_, right @ StringType()) if right.foldable =>
        d.copy(days = Cast(right, IntegerType))

      case s @ SubtractDates(left @ StringType(), _, _) if left.foldable =>
        s.copy(left = Cast(s.left, DateType))
      case s @ SubtractDates(_, right @ StringType(), _) if right.foldable =>
        s.copy(right = Cast(s.right, DateType))
      case t @ TimeAdd(left @ StringType(), _, _) if left.foldable =>
        t.copy(start = Cast(t.start, TimestampType))
      case t @ SubtractTimestamps(left @ StringType(), _, _, _) if left.foldable =>
        t.copy(left = Cast(t.left, t.right.dataType))
      case t @ SubtractTimestamps(_, right @ StringType(), _, _) if right.foldable =>
        t.copy(right = Cast(right, t.left.dataType))
    }
  }

  /**
   * When getting a date field from a Timestamp column, cast the column as date type.
   *
   * This is Spark's hack to make the implementation simple. In the default type coercion rules,
   * the implicit cast rule does the work. However, The ANSI implicit cast rule doesn't allow
   * converting Timestamp type as Date type, so we need to have this additional rule
   * to make sure the date field extraction from Timestamp columns works.
   */
  object GetDateFieldOperations extends TypeCoercionRule {
    override def transform: PartialFunction[Expression, Expression] = {
      case g: GetDateField if AnyTimestampType.unapply(g.child) =>
        g.withNewChildren(Seq(Cast(g.child, DateType)))
    }
  }

  object DateTimeOperations extends TypeCoercionRule {
    override val transform: PartialFunction[Expression, Expression] = {
      // Skip nodes who's children have not been resolved yet.
      case e if !e.childrenResolved => e

      case d @ DateAdd(AnyTimestampType(), _) => d.copy(startDate = Cast(d.startDate, DateType))
      case d @ DateSub(AnyTimestampType(), _) => d.copy(startDate = Cast(d.startDate, DateType))

      case s @ SubtractTimestamps(DateType(), AnyTimestampType(), _, _) =>
        s.copy(left = Cast(s.left, s.right.dataType))
      case s @ SubtractTimestamps(AnyTimestampType(), DateType(), _, _) =>
        s.copy(right = Cast(s.right, s.left.dataType))
      case s @ SubtractTimestamps(AnyTimestampType(), AnyTimestampType(), _, _)
        if s.left.dataType != s.right.dataType =>
        val newLeft = castIfNotSameType(s.left, TimestampNTZType)
        val newRight = castIfNotSameType(s.right, TimestampNTZType)
        s.copy(left = newLeft, right = newRight)
    }
  }

  // This is for generating a new rule id, so that we can run both default and Ansi
  // type coercion rules against one logical plan.
  class AnsiCombinedTypeCoercionRule(rules: Seq[TypeCoercionRule]) extends
    CombinedTypeCoercionRule(rules)
}
