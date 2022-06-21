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

package org.apache.spark.sql.jdbc

import java.sql.Types
import java.util.Locale

import org.apache.spark.sql.connector.expressions.aggregate.{AggregateFunc, GeneralAggregateFunc}
import org.apache.spark.sql.types._


private case object TeradataDialect extends JdbcDialect {

  override def canHandle(url: String): Boolean =
    url.toLowerCase(Locale.ROOT).startsWith("jdbc:teradata")

  // scalastyle:off line.size.limit
  // See https://docs.teradata.com/r/Teradata-VantageTM-SQL-Functions-Expressions-and-Predicates/March-2019/Aggregate-Functions
  // scalastyle:on line.size.limit
  override def compileAggregate(aggFunction: AggregateFunc): Option[String] = {
    super.compileAggregate(aggFunction).orElse(
      aggFunction match {
        case f: GeneralAggregateFunc if f.name() == "VAR_POP" =>
          assert(f.children().length == 1)
          val distinct = if (f.isDistinct) "DISTINCT " else ""
          Some(s"VAR_POP($distinct${f.children().head})")
        case f: GeneralAggregateFunc if f.name() == "VAR_SAMP" =>
          assert(f.children().length == 1)
          val distinct = if (f.isDistinct) "DISTINCT " else ""
          Some(s"VAR_SAMP($distinct${f.children().head})")
        case f: GeneralAggregateFunc if f.name() == "STDDEV_POP" =>
          assert(f.children().length == 1)
          val distinct = if (f.isDistinct) "DISTINCT " else ""
          Some(s"STDDEV_POP($distinct${f.children().head})")
        case f: GeneralAggregateFunc if f.name() == "STDDEV_SAMP" =>
          assert(f.children().length == 1)
          val distinct = if (f.isDistinct) "DISTINCT " else ""
          Some(s"STDDEV_SAMP($distinct${f.children().head})")
        case f: GeneralAggregateFunc if f.name() == "COVAR_POP" && f.isDistinct == false =>
          assert(f.children().length == 2)
          Some(s"COVAR_POP(${f.children().head}, ${f.children().last})")
        case f: GeneralAggregateFunc if f.name() == "COVAR_SAMP" && f.isDistinct == false =>
          assert(f.children().length == 2)
          Some(s"COVAR_SAMP(${f.children().head}, ${f.children().last})")
        case f: GeneralAggregateFunc if f.name() == "CORR" && f.isDistinct == false =>
          assert(f.children().length == 2)
          Some(s"CORR(${f.children().head}, ${f.children().last})")
        case _ => None
      }
    )
  }

  override def getJDBCType(dt: DataType): Option[JdbcType] = dt match {
    case StringType => Some(JdbcType("VARCHAR(255)", java.sql.Types.VARCHAR))
    case BooleanType => Option(JdbcType("CHAR(1)", java.sql.Types.CHAR))
    case _ => None
  }

  // Teradata does not support cascading a truncation
  override def isCascadingTruncateTable(): Option[Boolean] = Some(false)

  /**
   * The SQL query used to truncate a table. Teradata does not support the 'TRUNCATE' syntax that
   * other dialects use. Instead, we need to use a 'DELETE FROM' statement.
   * @param table The table to truncate.
   * @param cascade Whether or not to cascade the truncation. Default value is the
   *                value of isCascadingTruncateTable(). Teradata does not support cascading a
   *                'DELETE FROM' statement (and as mentioned, does not support 'TRUNCATE' syntax)
   * @return The SQL query to use for truncating a table
   */
  override def getTruncateQuery(
      table: String,
      cascade: Option[Boolean] = isCascadingTruncateTable): String = {
    s"DELETE FROM $table ALL"
  }

  // See https://docs.teradata.com/reader/scPHvjfglIlB8F70YliLAw/wysTNUMsP~0aGzksLCl1kg
  override def renameTable(oldTable: String, newTable: String): String = {
    s"RENAME TABLE $oldTable TO $newTable"
  }

  override def getLimitClause(limit: Integer): String = {
    ""
  }

  override def getCatalystType(
      sqlType: Int, typeName: String, size: Int, md: MetadataBuilder): Option[DataType] = {
    sqlType match {
      case Types.NUMERIC =>
        if (md == null) {
          Some(DecimalType.SYSTEM_DEFAULT)
        } else {
          val scale = md.build().getLong("scale")
          // In Teradata, define Number without parameter means precision and scale is flexible.
          // However, in this case, the scale returned from JDBC is 0, which will lead to
          // fractional part loss. And the precision returned from JDBC is 40, which conflicts to
          // DecimalType.MAX_PRECISION.
          // Handle this special case by adding explicit conversion to system default decimal type.
          if (size == 40) {
            if (scale == 0) Some(DecimalType.SYSTEM_DEFAULT)
            // In Teradata, Number(*, scale) is valid but in this case, the precision
            // returned from JDBC is also 40, which conflicts to DecimalType.MAX_PRECISION.
            else Some(DecimalType(DecimalType.MAX_PRECISION, scale.toInt))
          } else {
            // Normal case, Number(precision, scale) is explicitly set in Teradata
            Some(DecimalType(size, scale.toInt))
          }
        }
        case _ => None
    }
  }
}
