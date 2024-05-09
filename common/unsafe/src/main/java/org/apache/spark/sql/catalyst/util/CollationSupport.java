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
package org.apache.spark.sql.catalyst.util;

import com.ibm.icu.text.StringSearch;

import org.apache.spark.unsafe.types.UTF8String;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Static entry point for collation-aware expressions (StringExpressions, RegexpExpressions, and
 * other expressions that require custom collation support), as well as private utility methods for
 * collation-aware UTF8String operations needed to implement .
 */
public final class CollationSupport {

  /**
   * Collation-aware string expressions.
   */

  public static class StringSplitSQL {
    public static UTF8String[] exec(final UTF8String s, final UTF8String d, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(s, d);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(s, d);
      } else {
        return execICU(s, d, collationId);
      }
    }
    public static String genCode(final String s, final String d, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.StringSplitSQL.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", s, d);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s)", s, d);
      } else {
        return String.format(expr + "ICU(%s, %s, %d)", s, d, collationId);
      }
    }
    public static UTF8String[] execBinary(final UTF8String string, final UTF8String delimiter) {
      return string.splitSQL(delimiter, -1);
    }
    public static UTF8String[] execLowercase(final UTF8String string, final UTF8String delimiter) {
      if (delimiter.numBytes() == 0) return new UTF8String[] { string };
      if (string.numBytes() == 0) return new UTF8String[] { UTF8String.EMPTY_UTF8 };
      Pattern pattern = Pattern.compile(Pattern.quote(delimiter.toString()),
        CollationSupport.lowercaseRegexFlags);
      String[] splits = pattern.split(string.toString(), -1);
      UTF8String[] res = new UTF8String[splits.length];
      for (int i = 0; i < res.length; i++) {
        res[i] = UTF8String.fromString(splits[i]);
      }
      return res;
    }
    public static UTF8String[] execICU(final UTF8String string, final UTF8String delimiter,
        final int collationId) {
      if (delimiter.numBytes() == 0) return new UTF8String[] { string };
      if (string.numBytes() == 0) return new UTF8String[] { UTF8String.EMPTY_UTF8 };
      List<UTF8String> strings = new ArrayList<>();
      String target = string.toString(), pattern = delimiter.toString();
      StringSearch stringSearch = CollationFactory.getStringSearch(target, pattern, collationId);
      int start = 0, end;
      while ((end = stringSearch.next()) != StringSearch.DONE) {
        strings.add(UTF8String.fromString(target.substring(start, end)));
        start = end + stringSearch.getMatchLength();
      }
      if (start <= target.length()) {
        strings.add(UTF8String.fromString(target.substring(start)));
      }
      return strings.toArray(new UTF8String[0]);
    }
  }

  public static class Contains {
    public static boolean exec(final UTF8String l, final UTF8String r, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(l, r);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(l, r);
      } else {
        return execICU(l, r, collationId);
      }
    }
    public static String genCode(final String l, final String r, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.Contains.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", l, r);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s)", l, r);
      } else {
        return String.format(expr + "ICU(%s, %s, %d)", l, r, collationId);
      }
    }
    public static boolean execBinary(final UTF8String l, final UTF8String r) {
      return l.contains(r);
    }
    public static boolean execLowercase(final UTF8String l, final UTF8String r) {
      return l.containsInLowerCase(r);
    }
    public static boolean execICU(final UTF8String l, final UTF8String r,
        final int collationId) {
      if (r.numBytes() == 0) return true;
      if (l.numBytes() == 0) return false;
      StringSearch stringSearch = CollationFactory.getStringSearch(l, r, collationId);
      return stringSearch.first() != StringSearch.DONE;
    }
  }

  public static class StartsWith {
    public static boolean exec(final UTF8String l, final UTF8String r,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(l, r);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(l, r);
      } else {
        return execICU(l, r, collationId);
      }
    }
    public static String genCode(final String l, final String r, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.StartsWith.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", l, r);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s)", l, r);
      } else {
        return String.format(expr + "ICU(%s, %s, %d)", l, r, collationId);
      }
    }
    public static boolean execBinary(final UTF8String l, final UTF8String r) {
      return l.startsWith(r);
    }
    public static boolean execLowercase(final UTF8String l, final UTF8String r) {
      return l.startsWithInLowerCase(r);
    }
    public static boolean execICU(final UTF8String l, final UTF8String r,
        final int collationId) {
      if (r.numBytes() == 0) return true;
      if (l.numBytes() == 0) return false;
      StringSearch stringSearch = CollationFactory.getStringSearch(l, r, collationId);
      return stringSearch.first() == 0;
    }
  }

  public static class EndsWith {
    public static boolean exec(final UTF8String l, final UTF8String r, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(l, r);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(l, r);
      } else {
        return execICU(l, r, collationId);
      }
    }
    public static String genCode(final String l, final String r, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.EndsWith.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", l, r);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s)", l, r);
      } else {
        return String.format(expr + "ICU(%s, %s, %d)", l, r, collationId);
      }
    }
    public static boolean execBinary(final UTF8String l, final UTF8String r) {
      return l.endsWith(r);
    }
    public static boolean execLowercase(final UTF8String l, final UTF8String r) {
      return l.endsWithInLowerCase(r);
    }
    public static boolean execICU(final UTF8String l, final UTF8String r,
        final int collationId) {
      if (r.numBytes() == 0) return true;
      if (l.numBytes() == 0) return false;
      StringSearch stringSearch = CollationFactory.getStringSearch(l, r, collationId);
      int endIndex = stringSearch.getTarget().getEndIndex();
      return stringSearch.last() == endIndex - stringSearch.getMatchLength();
    }
  }

  public static class Upper {
    public static UTF8String exec(final UTF8String v, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality || collation.supportsLowercaseEquality) {
        return execUTF8(v);
      } else {
        return execICU(v, collationId);
      }
    }
    public static String genCode(final String v, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.Upper.exec";
      if (collation.supportsBinaryEquality || collation.supportsLowercaseEquality) {
        return String.format(expr + "UTF8(%s)", v);
      } else {
        return String.format(expr + "ICU(%s, %d)", v, collationId);
      }
    }
    public static UTF8String execUTF8(final UTF8String v) {
      return v.toUpperCase();
    }
    public static UTF8String execICU(final UTF8String v, final int collationId) {
      return UTF8String.fromString(CollationAwareUTF8String.toUpperCase(v.toString(), collationId));
    }
  }

  public static class Lower {
    public static UTF8String exec(final UTF8String v, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality || collation.supportsLowercaseEquality) {
        return execUTF8(v);
      } else {
        return execICU(v, collationId);
      }
    }
    public static String genCode(final String v, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
        String expr = "CollationSupport.Lower.exec";
      if (collation.supportsBinaryEquality || collation.supportsLowercaseEquality) {
        return String.format(expr + "UTF8(%s)", v);
      } else {
        return String.format(expr + "ICU(%s, %d)", v, collationId);
      }
    }
    public static UTF8String execUTF8(final UTF8String v) {
      return v.toLowerCase();
    }
    public static UTF8String execICU(final UTF8String v, final int collationId) {
      return UTF8String.fromString(CollationAwareUTF8String.toLowerCase(v.toString(), collationId));
    }
  }

  public static class InitCap {
    public static UTF8String exec(final UTF8String v, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality || collation.supportsLowercaseEquality) {
        return execUTF8(v);
      } else {
        return execICU(v, collationId);
      }
    }

    public static String genCode(final String v, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.InitCap.exec";
      if (collation.supportsBinaryEquality || collation.supportsLowercaseEquality) {
        return String.format(expr + "UTF8(%s)", v);
      } else {
        return String.format(expr + "ICU(%s, %d)", v, collationId);
      }
    }

    public static UTF8String execUTF8(final UTF8String v) {
      return v.toLowerCase().toTitleCase();
    }

    public static UTF8String execICU(final UTF8String v, final int collationId) {
      return UTF8String.fromString(
              CollationAwareUTF8String.toTitleCase(
                      CollationAwareUTF8String.toLowerCase(
                              v.toString(),
                              collationId
                      ),
                      collationId));
    }
  }

  public static class FindInSet {
    public static int exec(final UTF8String word, final UTF8String set, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(word, set);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(word, set);
      } else {
        return execICU(word, set, collationId);
      }
    }
    public static String genCode(final String word, final String set, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.FindInSet.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", word, set);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s)", word, set);
      } else {
        return String.format(expr + "ICU(%s, %s, %d)", word, set, collationId);
      }
    }
    public static int execBinary(final UTF8String word, final UTF8String set) {
      return set.findInSet(word);
    }
    public static int execLowercase(final UTF8String word, final UTF8String set) {
      return set.toLowerCase().findInSet(word.toLowerCase());
    }
    public static int execICU(final UTF8String word, final UTF8String set,
                                  final int collationId) {
      return CollationAwareUTF8String.findInSet(word, set, collationId);
    }
  }

  public static class StringInstr {
    public static int exec(final UTF8String string, final UTF8String substring,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(string, substring);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(string, substring);
      } else {
        return execICU(string, substring, collationId);
      }
    }
    public static String genCode(final String string, final String substring,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.StringInstr.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", string, substring);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s)", string, substring);
      } else {
        return String.format(expr + "ICU(%s, %s, %d)", string, substring, collationId);
      }
    }
    public static int execBinary(final UTF8String string, final UTF8String substring) {
      return string.indexOf(substring, 0);
    }
    public static int execLowercase(final UTF8String string, final UTF8String substring) {
      return string.toLowerCase().indexOf(substring.toLowerCase(), 0);
    }
    public static int execICU(final UTF8String string, final UTF8String substring,
        final int collationId) {
      return CollationAwareUTF8String.indexOf(string, substring, 0, collationId);
    }
  }

  public static class StringReplace {
    public static UTF8String exec(final UTF8String src, final UTF8String search,
        final UTF8String replace, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(src, search, replace);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(src, search, replace);
      } else {
        return execICU(src, search, replace, collationId);
      }
    }
    public static String genCode(final String src, final String search, final String replace,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.StringReplace.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s, %s)", src, search, replace);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s, %s)", src, search, replace);
      } else {
        return String.format(expr + "ICU(%s, %s, %s, %d)", src, search, replace, collationId);
      }
    }
    public static UTF8String execBinary(final UTF8String src, final UTF8String search,
        final UTF8String replace) {
      return src.replace(search, replace);
    }
    public static UTF8String execLowercase(final UTF8String src, final UTF8String search,
        final UTF8String replace) {
      return CollationAwareUTF8String.lowercaseReplace(src, search, replace);
    }
    public static UTF8String execICU(final UTF8String src, final UTF8String search,
        final UTF8String replace, final int collationId) {
      return CollationAwareUTF8String.replace(src, search, replace, collationId);
    }
  }

  public static class StringLocate {
    public static int exec(final UTF8String string, final UTF8String substring, final int start,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(string, substring, start);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(string, substring, start);
      } else {
        return execICU(string, substring, start, collationId);
      }
    }
    public static String genCode(final String string, final String substring, final int start,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.StringLocate.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s, %d)", string, substring, start);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s, %d)", string, substring, start);
      } else {
        return String.format(expr + "ICU(%s, %s, %d, %d)", string, substring, start, collationId);
      }
    }
    public static int execBinary(final UTF8String string, final UTF8String substring,
        final int start) {
      return string.indexOf(substring, start);
    }
    public static int execLowercase(final UTF8String string, final UTF8String substring,
        final int start) {
      return string.toLowerCase().indexOf(substring.toLowerCase(), start);
    }
    public static int execICU(final UTF8String string, final UTF8String substring, final int start,
        final int collationId) {
      return CollationAwareUTF8String.indexOf(string, substring, start, collationId);
    }
  }

  public static class SubstringIndex {
    public static UTF8String exec(final UTF8String string, final UTF8String delimiter,
        final int count, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(string, delimiter, count);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(string, delimiter, count);
      } else {
        return execICU(string, delimiter, count, collationId);
      }
    }
    public static String genCode(final String string, final String delimiter,
        final int count, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.SubstringIndex.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s, %d)", string, delimiter, count);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s, %d)", string, delimiter, count);
      } else {
        return String.format(expr + "ICU(%s, %s, %d, %d)", string, delimiter, count, collationId);
      }
    }
    public static UTF8String execBinary(final UTF8String string, final UTF8String delimiter,
        final int count) {
      return string.subStringIndex(delimiter, count);
    }
    public static UTF8String execLowercase(final UTF8String string, final UTF8String delimiter,
        final int count) {
      return CollationAwareUTF8String.lowercaseSubStringIndex(string, delimiter, count);
    }
    public static UTF8String execICU(final UTF8String string, final UTF8String delimiter,
        final int count, final int collationId) {
      return CollationAwareUTF8String.subStringIndex(string, delimiter, count,
              collationId);
    }
  }

  public static class StringTranslate {
    public static UTF8String exec(final UTF8String source, Map<String, String> dict,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(source, dict);
      } else if (collation.supportsLowercaseEquality) {
        return execLowercase(source, dict);
      } else {
        return execICU(source, dict, collationId);
      }
    }
    public static String genCode(final String source, final String dict, final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.EndsWith.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", source, dict);
      } else if (collation.supportsLowercaseEquality) {
        return String.format(expr + "Lowercase(%s, %s)", source, dict);
      } else {
        return String.format(expr + "ICU(%s, %s, %d)", source, dict, collationId);
      }
    }
    public static UTF8String execBinary(final UTF8String source, Map<String, String> dict) {
      return source.translate(dict);
    }
    public static UTF8String execLowercase(final UTF8String source, Map<String, String> dict) {
      String srcStr = source.toString();
      StringBuilder sb = new StringBuilder();
      int charCount = 0;
      for (int k = 0; k < srcStr.length(); k += charCount) {
        int codePoint = srcStr.codePointAt(k);
        charCount = Character.charCount(codePoint);
        String subStr = srcStr.substring(k, k + charCount);
        String translated = dict.get(subStr.toLowerCase());
        if (null == translated) {
          sb.append(subStr);
        } else if (!"\0".equals(translated)) {
          sb.append(translated);
        }
      }
      return UTF8String.fromString(sb.toString());
    }
    public static UTF8String execICU(final UTF8String source, Map<String, String> dict,
        final int collationId) {
      return source.translate(CollationAwareUTF8String.getCollationAwareDict(
        source, dict, collationId));
    }
  }

  public static class StringTrim {
    public static UTF8String exec(
        final UTF8String srcString,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(srcString);
      } else {
        return execLowercase(srcString);
      }
    }
    public static UTF8String exec(
        final UTF8String srcString,
        final UTF8String trimString,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(srcString, trimString);
      } else {
        return execLowercase(srcString, trimString);
      }
    }
    public static String genCode(
        final String srcString,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.StringTrim.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s)", srcString);
      } {
        return String.format(expr + "Lowercase(%s)", srcString);
      }
    }
    public static String genCode(
        final String srcString,
        final String trimString,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.StringTrim.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", srcString, trimString);
      } else {
        return String.format(expr + "Lowercase(%s, %s)", srcString, trimString);
      }
    }
    public static UTF8String execBinary(
        final UTF8String srcString) {
      return srcString.trim();
    }
    public static UTF8String execBinary(
        final UTF8String srcString,
        final UTF8String trimString) {
      return srcString.trim(trimString);
    }
    public static UTF8String execLowercase(
        final UTF8String srcString) {
      return srcString.trim();
    }
    public static UTF8String execLowercase(
        final UTF8String srcString,
        final UTF8String trimString) {
      return CollationAwareUTF8String.lowercaseTrim(srcString, trimString);
    }
  }

  public static class StringTrimLeft {
    public static UTF8String exec(
        final UTF8String srcString,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(srcString);
      } else {
        return execLowercase(srcString);
      }
    }
    public static UTF8String exec(
        final UTF8String srcString,
        final UTF8String trimString,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(srcString, trimString);
      } else {
        return execLowercase(srcString, trimString);
      }
    }
    public static String genCode(
        final String srcString,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.StringTrimLeft.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s)", srcString);
      } else {
        return String.format(expr + "Lowercase(%s)", srcString);
      }
    }
    public static String genCode(
        final String srcString,
        final String trimString,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.StringTrimLeft.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", srcString, trimString);
      } else {
        return String.format(expr + "Lowercase(%s, %s)", srcString, trimString);
      }
    }
    public static UTF8String execBinary(
        final UTF8String srcString) {
      return srcString.trimLeft();
    }
    public static UTF8String execBinary(
        final UTF8String srcString,
        final UTF8String trimString) {
      return srcString.trimLeft(trimString);
    }
    public static UTF8String execLowercase(
        final UTF8String srcString) {
      return srcString.trimLeft();
    }
    public static UTF8String execLowercase(
        final UTF8String srcString,
        final UTF8String trimString) {
      return CollationAwareUTF8String.lowercaseTrimLeft(srcString, trimString);
    }
  }

  public static class StringTrimRight {
    public static UTF8String exec(
        final UTF8String srcString,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(srcString);
      } else {
        return execLowercase(srcString);
      }
    }
    public static UTF8String exec(
        final UTF8String srcString,
        final UTF8String trimString,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      if (collation.supportsBinaryEquality) {
        return execBinary(srcString, trimString);
      } else {
        return execLowercase(srcString, trimString);
      }
    }
    public static String genCode(
        final String srcString,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.StringTrimRight.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s)", srcString);
      } else {
        return String.format(expr + "Lowercase(%s)", srcString);
      }
    }
    public static String genCode(
        final String srcString,
        final String trimString,
        final int collationId) {
      CollationFactory.Collation collation = CollationFactory.fetchCollation(collationId);
      String expr = "CollationSupport.StringTrimRight.exec";
      if (collation.supportsBinaryEquality) {
        return String.format(expr + "Binary(%s, %s)", srcString, trimString);
      } else {
        return String.format(expr + "Lowercase(%s, %s)", srcString, trimString);
      }
    }
    public static UTF8String execBinary(
        final UTF8String srcString) {
      return srcString.trimRight();
    }
    public static UTF8String execBinary(
        final UTF8String srcString,
        final UTF8String trimString) {
      return srcString.trimRight(trimString);
    }
    public static UTF8String execLowercase(
        final UTF8String srcString) {
      return srcString.trimRight();
    }
    public static UTF8String execLowercase(
        final UTF8String srcString,
        final UTF8String trimString) {
      return CollationAwareUTF8String.lowercaseTrimRight(srcString, trimString);
    }
  }

  // TODO: Add more collation-aware string expressions.

  /**
   * Collation-aware regexp expressions.
   */

  public static boolean supportsLowercaseRegex(final int collationId) {
    // for regex, only Unicode case-insensitive matching is possible,
    // so UTF8_BINARY_LCASE is treated as UNICODE_CI in this context
    return CollationFactory.fetchCollation(collationId).supportsLowercaseEquality;
  }

  private static final int lowercaseRegexFlags = Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE;
  public static int collationAwareRegexFlags(final int collationId) {
    return supportsLowercaseRegex(collationId) ? lowercaseRegexFlags : 0;
  }

  private static final UTF8String lowercaseRegexPrefix = UTF8String.fromString("(?ui)");
  public static UTF8String lowercaseRegex(final UTF8String regex) {
    return UTF8String.concat(lowercaseRegexPrefix, regex);
  }
  public static UTF8String collationAwareRegex(final UTF8String regex, final int collationId) {
    return supportsLowercaseRegex(collationId) ? lowercaseRegex(regex) : regex;
  }

  /**
   * Other collation-aware expressions.
   */

  // TODO: Add other collation-aware expressions.

}
