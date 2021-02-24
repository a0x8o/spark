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
    InConversion ::
      WidenSetOperationTypes ::
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
      StringLiteralCoercion ::
      Nil

  override def findTightestCommonType(t1: DataType, t2: DataType): Option[DataType] = {
    (t1, t2) match {
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

      case (t1, t2) => findTypeForComplex(t1, t2, findTightestCommonType)
    }

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

      // Cast null type (usually from null literals) into target types
      case (NullType, target) => Some(target.defaultConcreteType)

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

      // If input is a numeric type but not decimal, and we expect a decimal type,
      // cast the input to decimal.
      case (d: NumericType, DecimalType) => Some(DecimalType.forType(d))

      case (n1: NumericType, n2: NumericType) =>
        val widerType = findWiderTypeForTwo(n1, n2)
        widerType match {
          // if the expected type is Float type, we should still return Float type.
          case Some(DoubleType) if n1 != DoubleType && n2 == FloatType => Some(FloatType)

          case Some(dt) if dt == n2 => Some(dt)

          case _ => None
        }

      case (DateType, TimestampType) => Some(TimestampType)

      // When we reach here, input type is not acceptable for any types in this type collection,
      // try to find the first one we can implicitly cast.
      case (_, TypeCollection(types)) =>
        types.flatMap(implicitCast(inType, _, isInputFoldable)).headOption

      // Implicit cast between array types.
      //
      // Compare the nullabilities of the from type and the to type, check whether the cast of
      // the nullability is resolvable by the following rules:
      // 1. If the nullability of the to type is true, the cast is always allowed;
      // 2. If the nullabilities of both the from type and the to type are false, the cast is
      //    allowed.
      // 3. Otherwise, the cast is not allowed
      case (ArrayType(fromType, containsNullFrom), ArrayType(toType: DataType, containsNullTo))
          if Cast.resolvableNullability(containsNullFrom, containsNullTo) =>
        implicitCast(fromType, toType, isInputFoldable).map(ArrayType(_, containsNullTo))

      // Implicit cast between Map types.
      // Follows the same semantics of implicit casting between two array types.
      // Refer to documentation above.
      case (MapType(fromKeyType, fromValueType, fn), MapType(toKeyType, toValueType, tn))
          if Cast.resolvableNullability(fn, tn) =>
        val newKeyType = implicitCast(fromKeyType, toKeyType, isInputFoldable)
        val newValueType = implicitCast(fromValueType, toValueType, isInputFoldable)
        if (newKeyType.isDefined && newValueType.isDefined) {
          Some(MapType(newKeyType.get, newValueType.get, tn))
        } else {
          None
        }

      case _ => None
    }
  }

  override def canCast(from: DataType, to: DataType): Boolean = AnsiCast.canCast(from, to)

  /**
   * Promotes string literals that appear in arithmetic and comparison expressions.
   */
  object PromoteStringLiterals extends TypeCoercionRule {
    private def castExpr(expr: Expression, targetType: DataType): Expression = {
      (expr.dataType, targetType) match {
        case (NullType, dt) => Literal.create(null, targetType)
        case (l, dt) if (l != dt) => Cast(expr, targetType)
        case _ => expr
      }
    }

    override protected def coerceTypes(
        plan: LogicalPlan): LogicalPlan = plan resolveExpressions {
      // Skip nodes who's children have not been resolved yet.
      case e if !e.childrenResolved => e

      case b @ BinaryOperator(left @ StringType(), right @ AtomicType()) if left.foldable =>
        b.makeCopy(Array(castExpr(left, right.dataType), right))

      case b @ BinaryOperator(left @ AtomicType(), right @ StringType()) if right.foldable =>
        b.makeCopy(Array(left, castExpr(right, left.dataType)))

      case Abs(e @ StringType()) if e.foldable => Abs(Cast(e, DoubleType))
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
    }
  }
}
