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

package org.apache.spark.sql.execution.streaming.state

import java.io.{DataInputStream, DataOutputStream, FileNotFoundException, IOException}

import scala.util.control.NonFatal

import com.google.common.io.ByteStreams
import org.apache.commons.io.IOUtils
import org.apache.hadoop.fs.{FSError, Path}

import org.apache.spark.internal.{Logging, MDC}
import org.apache.spark.internal.LogKeys._
import org.apache.spark.io.CompressionCodec
import org.apache.spark.sql.errors.QueryExecutionErrors
import org.apache.spark.sql.execution.streaming.CheckpointFileManager
import org.apache.spark.sql.execution.streaming.CheckpointFileManager.CancellableFSDataOutputStream
import org.apache.spark.sql.execution.streaming.state.RecordType.RecordType
import org.apache.spark.util.NextIterator

/**
 * Enum used to write record types to changelog files used with RocksDBStateStoreProvider.
 */
object RecordType extends Enumeration {
  type RecordType = Value

  val EOF_RECORD = Value("eof_record")
  val PUT_RECORD = Value("put_record")
  val DELETE_RECORD = Value("delete_record")
  val MERGE_RECORD = Value("merge_record")

  // Generate byte representation of each record type
  def getRecordTypeAsByte(recordType: RecordType): Byte = {
    recordType match {
      case EOF_RECORD => 0x00.toByte
      case PUT_RECORD => 0x01.toByte
      case DELETE_RECORD => 0x10.toByte
      case MERGE_RECORD => 0x11.toByte
    }
  }

  // Generate record type from byte representation
  def getRecordTypeFromByte(byte: Byte): RecordType = {
    byte match {
      case 0x00 => EOF_RECORD
      case 0x01 => PUT_RECORD
      case 0x10 => DELETE_RECORD
      case 0x11 => MERGE_RECORD
      case _ => throw new RuntimeException(s"Found invalid record type for value=$byte")
    }
  }
}

/**
 * Base class for state store changelog writer
 * @param fm - checkpoint file manager used to manage streaming query checkpoint
 * @param file - name of file to use to write changelog
 * @param compressionCodec - compression method using for writing changelog file
 */
abstract class StateStoreChangelogWriter(
    fm: CheckpointFileManager,
    file: Path,
    compressionCodec: CompressionCodec) extends Logging {

  private def compressStream(outputStream: DataOutputStream): DataOutputStream = {
    val compressed = compressionCodec.compressedOutputStream(outputStream)
    new DataOutputStream(compressed)
  }

  protected var backingFileStream: CancellableFSDataOutputStream =
    fm.createAtomic(file, overwriteIfPossible = true)
  protected var compressedStream: DataOutputStream = compressStream(backingFileStream)
  var size = 0

  def put(key: Array[Byte], value: Array[Byte]): Unit

  def put(key: Array[Byte], value: Array[Byte], colFamilyName: String): Unit

  def delete(key: Array[Byte]): Unit

  def delete(key: Array[Byte], colFamilyName: String): Unit

  def merge(key: Array[Byte], value: Array[Byte], colFamilyName: String): Unit

  def abort(): Unit = {
    try {
      if (backingFileStream != null) backingFileStream.cancel()
      if (compressedStream != null) IOUtils.closeQuietly(compressedStream)
    } catch {
      // Closing the compressedStream causes the stream to write/flush flush data into the
      // rawStream. Since the rawStream is already closed, there may be errors.
      // Usually its an IOException. However, Hadoop's RawLocalFileSystem wraps
      // IOException into FSError.
      case e: FSError if e.getCause.isInstanceOf[IOException] =>
      case NonFatal(ex) =>
        logInfo(log"Failed to cancel changelog file ${MDC(FILE_NAME, file)} " +
          log"for state store provider " +
          log"with exception=${MDC(ERROR, ex)}")
    } finally {
      backingFileStream = null
      compressedStream = null
    }
  }

  def commit(): Unit
}

/**
 * Write changes to the key value state store instance to a changelog file.
 * There are 2 types of records, put and delete.
 * A put record is written as: | key length | key content | value length | value content |
 * A delete record is written as: | key length | key content | -1 |
 * Write an Int -1 to signal the end of file.
 * The overall changelog format is: | put record | delete record | ... | put record | -1 |
 */
class StateStoreChangelogWriterV1(
    fm: CheckpointFileManager,
    file: Path,
    compressionCodec: CompressionCodec)
  extends StateStoreChangelogWriter(fm, file, compressionCodec) {

  override def put(key: Array[Byte], value: Array[Byte]): Unit = {
    assert(compressedStream != null)
    compressedStream.writeInt(key.size)
    compressedStream.write(key)
    compressedStream.writeInt(value.size)
    compressedStream.write(value)
    size += 1
  }

  override def put(key: Array[Byte], value: Array[Byte], colFamilyName: String): Unit = {
    throw StateStoreErrors.unsupportedOperationException(
      operationName = "Put", entity = "changelog writer v1")
  }

  override def delete(key: Array[Byte]): Unit = {
    assert(compressedStream != null)
    compressedStream.writeInt(key.size)
    compressedStream.write(key)
    // -1 in the value field means record deletion.
    compressedStream.writeInt(-1)
    size += 1
  }

  override def delete(key: Array[Byte], colFamilyName: String): Unit = {
    throw StateStoreErrors.unsupportedOperationException(
      operationName = "Delete", entity = "changelog writer v1")
  }

  override def merge(key: Array[Byte], value: Array[Byte], colFamilyName: String): Unit = {
    throw new UnsupportedOperationException("Operation not supported with state " +
      "changelog writer v1")
  }

  override def commit(): Unit = {
    try {
      // -1 in the key length field mean EOF.
      compressedStream.writeInt(-1)
      compressedStream.close()
    } catch {
      case e: Throwable =>
        abort()
        logError(s"Fail to commit changelog file $file because of exception $e")
        throw e
    } finally {
      backingFileStream = null
      compressedStream = null
    }
  }
}

/**
 * Write changes to the key value state store instance to a changelog file.
 * There are 2 types of data records, put and delete.
 * A put record is written as: | record type | key length
 *    | key content | value length | value content | col family name length | col family name | -1 |
 * A delete record is written as: | record type | key length | key content | -1
 *    | col family name length | col family name | -1 |
 * Write an EOF_RECORD to signal the end of file.
 * The overall changelog format is: | put record | delete record | ... | put record | eof record |
 */
class StateStoreChangelogWriterV2(
    fm: CheckpointFileManager,
    file: Path,
    compressionCodec: CompressionCodec)
  extends StateStoreChangelogWriter(fm, file, compressionCodec) {

  override def put(key: Array[Byte], value: Array[Byte]): Unit = {
    throw StateStoreErrors.unsupportedOperationException(
      operationName = "Put", entity = "changelog writer v2")
  }

  override def put(key: Array[Byte], value: Array[Byte], colFamilyName: String): Unit = {
    writePutOrMergeRecord(key, value, colFamilyName, RecordType.PUT_RECORD)
  }

  override def delete(key: Array[Byte]): Unit = {
    throw StateStoreErrors.unsupportedOperationException(
      operationName = "Delete", entity = "changelog writer v2")
  }

  override def delete(key: Array[Byte], colFamilyName: String): Unit = {
    assert(compressedStream != null)
    compressedStream.write(RecordType.getRecordTypeAsByte(RecordType.DELETE_RECORD))
    compressedStream.writeInt(key.size)
    compressedStream.write(key)
    // -1 in the value field means record deletion.
    compressedStream.writeInt(-1)
    compressedStream.writeInt(colFamilyName.getBytes.size)
    compressedStream.write(colFamilyName.getBytes)
    size += 1
  }

  override def merge(key: Array[Byte], value: Array[Byte], colFamilyName: String): Unit = {
    writePutOrMergeRecord(key, value, colFamilyName, RecordType.MERGE_RECORD)
  }

  private def writePutOrMergeRecord(key: Array[Byte],
      value: Array[Byte],
      colFamilyName: String,
      recordType: RecordType): Unit = {
    assert(recordType == RecordType.PUT_RECORD || recordType == RecordType.MERGE_RECORD)
    assert(compressedStream != null)
    compressedStream.write(RecordType.getRecordTypeAsByte(recordType))
    compressedStream.writeInt(key.size)
    compressedStream.write(key)
    compressedStream.writeInt(value.size)
    compressedStream.write(value)
    compressedStream.writeInt(colFamilyName.getBytes.size)
    compressedStream.write(colFamilyName.getBytes)
    size += 1
  }

  def commit(): Unit = {
    try {
      // write EOF_RECORD to signal end of file
      compressedStream.write(RecordType.getRecordTypeAsByte(RecordType.EOF_RECORD))
      compressedStream.close()
    } catch {
      case e: Throwable =>
        abort()
        logError(s"Fail to commit changelog file $file because of exception $e")
        throw e
    } finally {
      backingFileStream = null
      compressedStream = null
    }
  }
}

/**
 * Base class for state store changelog reader
 * @param fm - checkpoint file manager used to manage streaming query checkpoint
 * @param fileToRead - name of file to use to read changelog
 * @param compressionCodec - de-compression method using for reading changelog file
 */
abstract class StateStoreChangelogReader(
    fm: CheckpointFileManager,
    fileToRead: Path,
    compressionCodec: CompressionCodec)
  extends NextIterator[(RecordType.Value, Array[Byte], Array[Byte], String)] with Logging {

  private def decompressStream(inputStream: DataInputStream): DataInputStream = {
    val compressed = compressionCodec.compressedInputStream(inputStream)
    new DataInputStream(compressed)
  }

  private val sourceStream = try {
    fm.open(fileToRead)
  } catch {
    case f: FileNotFoundException =>
      throw QueryExecutionErrors.failedToReadStreamingStateFileError(fileToRead, f)
  }
  protected val input: DataInputStream = decompressStream(sourceStream)

  def close(): Unit = { if (input != null) input.close() }

  override def getNext(): (RecordType.Value, Array[Byte], Array[Byte], String)
}

/**
 * Read an iterator of change record from the changelog file.
 * A record is represented by ByteArrayPair(recordType: RecordType.Value,
 *  key: Array[Byte], value: Array[Byte], colFamilyName: String)
 * A put record is returned as a ByteArrayPair(recordType, key, value, colFamilyName)
 * A delete record is return as a ByteArrayPair(recordType, key, null, colFamilyName)
 */
class StateStoreChangelogReaderV1(
    fm: CheckpointFileManager,
    fileToRead: Path,
    compressionCodec: CompressionCodec)
  extends StateStoreChangelogReader(fm, fileToRead, compressionCodec) {

  override def getNext(): (RecordType.Value, Array[Byte], Array[Byte], String) = {
    val keySize = input.readInt()
    // A -1 key size mean end of file.
    if (keySize == -1) {
      finished = true
      null
    } else if (keySize < 0) {
      throw new IOException(
        s"Error reading streaming state file $fileToRead: key size cannot be $keySize")
    } else {
      // TODO: reuse the key buffer and value buffer across records.
      val keyBuffer = new Array[Byte](keySize)
      ByteStreams.readFully(input, keyBuffer, 0, keySize)
      val valueSize = input.readInt()
      if (valueSize < 0) {
        // A deletion record
        (RecordType.DELETE_RECORD, keyBuffer, null, StateStore.DEFAULT_COL_FAMILY_NAME)
      } else {
        val valueBuffer = new Array[Byte](valueSize)
        ByteStreams.readFully(input, valueBuffer, 0, valueSize)
        // A put record.
        (RecordType.PUT_RECORD, keyBuffer, valueBuffer, StateStore.DEFAULT_COL_FAMILY_NAME)
      }
    }
  }
}

/**
 * Read an iterator of change record from the changelog file.
 * A record is represented by ByteArrayPair(recordType: RecordType.Value,
 *  key: Array[Byte], value: Array[Byte], colFamilyName: String)
 * A put record is returned as a ByteArrayPair(recordType, key, value, colFamilyName)
 * A delete record is return as a ByteArrayPair(recordType, key, null, colFamilyName)
 */
class StateStoreChangelogReaderV2(
    fm: CheckpointFileManager,
    fileToRead: Path,
    compressionCodec: CompressionCodec)
  extends StateStoreChangelogReader(fm, fileToRead, compressionCodec) {

  private def parseBuffer(input: DataInputStream): Array[Byte] = {
    val blockSize = input.readInt()
    val blockBuffer = new Array[Byte](blockSize)
    ByteStreams.readFully(input, blockBuffer, 0, blockSize)
    blockBuffer
  }

  override def getNext(): (RecordType.Value, Array[Byte], Array[Byte], String) = {
    val recordType = RecordType.getRecordTypeFromByte(input.readByte())
    // A EOF_RECORD means end of file.
    if (recordType == RecordType.EOF_RECORD) {
      finished = true
      null
    } else {
      recordType match {
        case RecordType.PUT_RECORD =>
          val keyBuffer = parseBuffer(input)
          val valueBuffer = parseBuffer(input)
          val colFamilyNameBuffer = parseBuffer(input)
          (RecordType.PUT_RECORD, keyBuffer, valueBuffer,
            colFamilyNameBuffer.map(_.toChar).mkString)

        case RecordType.DELETE_RECORD =>
          val keyBuffer = parseBuffer(input)
          val valueSize = input.readInt()
          assert(valueSize == -1)
          val colFamilyNameBuffer = parseBuffer(input)
          (RecordType.DELETE_RECORD, keyBuffer, null,
            colFamilyNameBuffer.map(_.toChar).mkString)

        case RecordType.MERGE_RECORD =>
          val keyBuffer = parseBuffer(input)
          val valueBuffer = parseBuffer(input)
          val colFamilyNameBuffer = parseBuffer(input)
          (RecordType.MERGE_RECORD, keyBuffer, valueBuffer,
            colFamilyNameBuffer.map(_.toChar).mkString)

        case _ =>
          throw new IOException("Failed to process unknown record type")
      }
    }
  }
}
