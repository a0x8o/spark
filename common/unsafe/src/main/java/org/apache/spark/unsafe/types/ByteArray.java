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

package org.apache.spark.unsafe.types;

import java.nio.ByteOrder;
import java.util.Arrays;

import com.google.common.primitives.Ints;

import org.apache.spark.unsafe.Platform;

public final class ByteArray {

  public static final byte[] EMPTY_BYTE = new byte[0];
  private static final boolean IS_LITTLE_ENDIAN =
      ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

  /**
   * Writes the content of a byte array into a memory address, identified by an object and an
   * offset. The target memory address must already been allocated, and have enough space to
   * hold all the bytes in this string.
   */
  public static void writeToMemory(byte[] src, Object target, long targetOffset) {
    Platform.copyMemory(src, Platform.BYTE_ARRAY_OFFSET, target, targetOffset, src.length);
  }

  /**
   * Returns a 64-bit integer that can be used as the prefix used in sorting.
   */
  public static long getPrefix(byte[] bytes) {
    if (bytes == null) {
      return 0L;
    }
    return getPrefix(bytes, Platform.BYTE_ARRAY_OFFSET, bytes.length);
  }

  static long getPrefix(Object base, long offset, int numBytes) {
    // Since JVMs are either 4-byte aligned or 8-byte aligned, we check the size of the bytes.
    // If size is 0, just return 0.
    // If size is between 1 and 4 (inclusive), assume data is 4-byte aligned under the hood and
    // use a getInt to fetch the prefix.
    // If size is greater than 4, assume we have at least 8 bytes of data to fetch.
    // After getting the data, we use a mask to mask out data that is not part of the bytes.
    final long p;
    final long mask;
    if (numBytes >= 8) {
      p = Platform.getLong(base, offset);
      mask = 0;
    } else if (numBytes > 4) {
      p = Platform.getLong(base, offset);
      mask = (1L << (8 - numBytes) * 8) - 1;
    } else if (numBytes > 0) {
      long pRaw = Platform.getInt(base, offset);
      p = IS_LITTLE_ENDIAN ? pRaw : (pRaw << 32);
      mask = (1L << (8 - numBytes) * 8) - 1;
    } else {
      p = 0;
      mask = 0;
    }
    return (IS_LITTLE_ENDIAN ? java.lang.Long.reverseBytes(p) : p) & ~mask;
  }

  public static byte[] subStringSQL(byte[] bytes, int pos, int len) {
    // This pos calculation is according to UTF8String#subStringSQL
    if (pos > bytes.length) {
      return EMPTY_BYTE;
    }
    int start = 0;
    int end;
    if (pos > 0) {
      start = pos - 1;
    } else if (pos < 0) {
      start = bytes.length + pos;
    }
    if ((bytes.length - start) < len) {
      end = bytes.length;
    } else {
      end = start + len;
    }
    start = Math.max(start, 0); // underflow
    if (start >= end) {
      return EMPTY_BYTE;
    }
    return Arrays.copyOfRange(bytes, start, end);
  }

  public static byte[] concat(byte[]... inputs) {
    // Compute the total length of the result
    long totalLength = 0;
    for (byte[] input : inputs) {
      if (input != null) {
        totalLength += input.length;
      } else {
        return null;
      }
    }

    // Allocate a new byte array, and copy the inputs one by one into it
    final byte[] result = new byte[Ints.checkedCast(totalLength)];
    int offset = 0;
    for (byte[] input : inputs) {
      int len = input.length;
      Platform.copyMemory(
        input, Platform.BYTE_ARRAY_OFFSET,
        result, Platform.BYTE_ARRAY_OFFSET + offset,
        len);
      offset += len;
    }
    return result;
  }
}
