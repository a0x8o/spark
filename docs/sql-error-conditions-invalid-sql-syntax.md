---
layout: global
title: INVALID_SQL_SYNTAX error class
displayTitle: INVALID_SQL_SYNTAX error class
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

Invalid SQL syntax.

This error class has the following derived error classes:

## CREATE_TEMP_FUNC_WITH_IF_NOT_EXISTS

CREATE TEMPORARY FUNCTION with IF NOT EXISTS is not allowed.

## ANALYZE_TABLE_UNEXPECTED_NOSCAN

ANALYZE TABLE(S) ... COMPUTE STATISTICS ... `<ctx>` must be either NOSCAN or empty.

## SHOW_FUNCTIONS_INVALID_SCOPE

SHOW `<scope>` FUNCTIONS not supported.

## CREATE_TEMP_FUNC_WITH_DATABASE

CREATE TEMPORARY FUNCTION with specifying a database(`<database>`) is not allowed.

## REPETITIVE_WINDOW_DEFINITION

The definition of window `<windowName>` is repetitive.

## MULTI_PART_NAME

`<statement>` with multiple part function name(`<funcName>`) is not allowed.

## LATERAL_WITHOUT_SUBQUERY_OR_TABLE_VALUED_FUNC

LATERAL can only be used with subquery and table-valued functions.

## OPTION_IS_INVALID

option or property key `<key>` is invalid; only `<supported>` are supported

## INVALID_WINDOW_REFERENCE

Window reference `<windowName>` is not a window specification.

## CREATE_FUNC_WITH_IF_NOT_EXISTS_AND_REPLACE

CREATE FUNCTION with both IF NOT EXISTS and REPLACE is not allowed.

## EMPTY_PARTITION_VALUE

Partition key `<partKey>` must set value.

## TRANSFORM_WRONG_NUM_ARGS

The transform`<transform>` requires `<expectedNum>` parameters but the actual number is `<actualNum>`.

## SHOW_FUNCTIONS_INVALID_PATTERN

Invalid pattern in SHOW FUNCTIONS: `<pattern>`. It must be a "STRING" literal.

## UNSUPPORTED_FUNC_NAME

Unsupported function name `<funcName>`.

## INVALID_COLUMN_REFERENCE

Expected a column reference for transform `<transform>`: `<expr>`.

## UNRESOLVED_WINDOW_REFERENCE

Cannot resolve window reference `<windowName>`.

## INVALID_TABLE_VALUED_FUNC_NAME

Table valued function cannot specify database name: `<funcName>`.
