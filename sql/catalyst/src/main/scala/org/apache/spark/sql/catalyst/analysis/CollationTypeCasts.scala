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

import javax.annotation.Nullable

import scala.annotation.tailrec

import org.apache.spark.sql.catalyst.analysis.TypeCoercion.{hasStringType, haveSameType}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.{ArrayType, DataType, MapType, StringType}

object CollationTypeCasts extends TypeCoercionRule {
  override val transform: PartialFunction[Expression, Expression] = {
    case e if !e.childrenResolved => e

    case ifExpr: If =>
      ifExpr.withNewChildren(
        ifExpr.predicate +: collateToSingleType(Seq(ifExpr.trueValue, ifExpr.falseValue)))

    case caseWhenExpr: CaseWhen if !haveSameType(caseWhenExpr.inputTypesForMerging) =>
      val outputStringType =
        getOutputCollation(caseWhenExpr.branches.map(_._2) ++ caseWhenExpr.elseValue)
        val newBranches = caseWhenExpr.branches.map { case (condition, value) =>
          (condition, castStringType(value, outputStringType).getOrElse(value))
        }
        val newElseValue =
          caseWhenExpr.elseValue.map(e => castStringType(e, outputStringType).getOrElse(e))
        CaseWhen(newBranches, newElseValue)

    case stringLocate: StringLocate =>
      stringLocate.withNewChildren(collateToSingleType(
        Seq(stringLocate.first, stringLocate.second)) :+ stringLocate.third)

    case substringIndex: SubstringIndex =>
      substringIndex.withNewChildren(
        collateToSingleType(
          Seq(substringIndex.first, substringIndex.second)) :+ substringIndex.third)

    case eltExpr: Elt =>
      eltExpr.withNewChildren(eltExpr.children.head +: collateToSingleType(eltExpr.children.tail))

    case overlayExpr: Overlay =>
      overlayExpr.withNewChildren(collateToSingleType(Seq(overlayExpr.input, overlayExpr.replace))
        ++ Seq(overlayExpr.pos, overlayExpr.len))

    case regExpReplace: RegExpReplace =>
      val Seq(subject, rep) = collateToSingleType(Seq(regExpReplace.subject, regExpReplace.rep))
      val newChildren = Seq(subject, regExpReplace.regexp, rep, regExpReplace.pos)
      regExpReplace.withNewChildren(newChildren)

    case stringPadExpr @ (_: StringRPad | _: StringLPad) =>
      val Seq(str, len, pad) = stringPadExpr.children
      val Seq(newStr, newPad) = collateToSingleType(Seq(str, pad))
      stringPadExpr.withNewChildren(Seq(newStr, len, newPad))

    case otherExpr @ (
      _: In | _: InSubquery | _: CreateArray | _: ArrayJoin | _: Concat | _: Greatest | _: Least |
      _: Coalesce | _: BinaryExpression | _: ConcatWs | _: Mask | _: StringReplace |
      _: StringTranslate | _: StringTrim | _: StringTrimLeft | _: StringTrimRight) =>
      val newChildren = collateToSingleType(otherExpr.children)
      otherExpr.withNewChildren(newChildren)
  }
  /**
   * Extracts StringTypes from filtered hasStringType
   */
  @tailrec
  private def extractStringType(dt: DataType): StringType = dt match {
    case st: StringType => st
    case ArrayType(et, _) => extractStringType(et)
    case MapType(kt, vt, _) => if (hasStringType(kt)) {
        extractStringType(kt)
      } else {
        extractStringType(vt)
      }
  }

  /**
   * Casts given expression to collated StringType with id equal to collationId only
   * if expression has StringType in the first place.
   * @param expr
   * @param collationId
   * @return
   */
  def castStringType(expr: Expression, st: StringType): Option[Expression] =
    castStringType(expr.dataType, st).map { dt => Cast(expr, dt)}

  private def castStringType(inType: DataType, castType: StringType): Option[DataType] = {
    @Nullable val ret: DataType = inType match {
      case st: StringType if st.collationId != castType.collationId => castType
      case ArrayType(arrType, nullable) =>
        castStringType(arrType, castType).map(ArrayType(_, nullable)).orNull
      case MapType(keyType, valueType, nullable) =>
        val newKeyType = castStringType(keyType, castType).getOrElse(keyType)
        val newValueType = castStringType(valueType, castType).getOrElse(valueType)
        if (newKeyType != keyType || newValueType != valueType) {
          MapType(newKeyType, newValueType, nullable)
        } else {
          null
        }
      case _ => null
    }
    Option(ret)
  }

  /**
   * Collates input expressions to a single collation.
   */
  def collateToSingleType(exprs: Seq[Expression]): Seq[Expression] = {
    val st = getOutputCollation(exprs)

    exprs.map(e => castStringType(e, st).getOrElse(e))
  }

  /**
   * Based on the data types of the input expressions this method determines
   * a collation type which the output will have. This function accepts Seq of
   * any expressions, but will only be affected by collated StringTypes or
   * complex DataTypes with collated StringTypes (e.g. ArrayType)
   */
  def getOutputCollation(expr: Seq[Expression]): StringType = {
    val explicitTypes = expr.filter {
        case _: Collate => true
        case cast: Cast if cast.getTagValue(Cast.USER_SPECIFIED_CAST).isDefined =>
          cast.dataType.isInstanceOf[StringType]
        case _ => false
      }
      .map(_.dataType.asInstanceOf[StringType].collationId)
      .distinct

    explicitTypes.size match {
      // We have 1 explicit collation
      case 1 => StringType(explicitTypes.head)
      // Multiple explicit collations occurred
      case size if size > 1 =>
        throw QueryCompilationErrors
          .explicitCollationMismatchError(
            explicitTypes.map(t => StringType(t).typeName)
          )
      // Only implicit or default collations present
      case 0 =>
        val implicitTypes = expr.filter {
            case Literal(_, _: StringType) => false
            case cast: Cast if cast.getTagValue(Cast.USER_SPECIFIED_CAST).isEmpty =>
              cast.child.dataType.isInstanceOf[StringType]
            case _ => true
          }
          .map(_.dataType)
          .filter(hasStringType)
          .map(extractStringType(_).collationId)
          .distinct

        if (implicitTypes.length > 1) {
          throw QueryCompilationErrors.implicitCollationMismatchError()
        }
        else {
          implicitTypes.headOption.map(StringType(_)).getOrElse(SQLConf.get.defaultStringType)
        }
    }
  }
}
