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
package org.apache.spark.sql.protobuf

import java.sql.Timestamp
import java.time.Duration

 import scala.collection.JavaConverters._

import com.google.protobuf.{ByteString, DynamicMessage}

import org.apache.spark.sql.{AnalysisException, Column, DataFrame, QueryTest, Row}
import org.apache.spark.sql.functions.{lit, struct}
import org.apache.spark.sql.protobuf.protos.SimpleMessageProtos._
import org.apache.spark.sql.protobuf.protos.SimpleMessageProtos.SimpleMessageRepeated.NestedEnum
import org.apache.spark.sql.protobuf.utils.ProtobufUtils
import org.apache.spark.sql.test.SharedSparkSession
import org.apache.spark.sql.types._

class ProtobufFunctionsSuite extends QueryTest with SharedSparkSession with ProtobufTestBase
  with Serializable {

  import testImplicits._

  val testFileDesc = testFile("functions_suite.desc", "protobuf/functions_suite.desc")
  private val javaClassNamePrefix = "org.apache.spark.sql.protobuf.protos.SimpleMessageProtos$"

  private def emptyBinaryDF = Seq(Array[Byte]()).toDF("binary")

  /**
   * Runs the given closure twice. Once with descriptor file and second time with Java class name.
   */
  private def checkWithFileAndClassName(messageName: String)(
    fn: (String, Option[String]) => Unit): Unit = {
      withClue("(With descriptor file)") {
        fn(messageName, Some(testFileDesc))
      }
      withClue("(With Java class name)") {
        fn(s"$javaClassNamePrefix$messageName", None)
      }
  }

  // A wrapper to invoke the right variable of from_protobuf() depending on arguments.
  private def from_protobuf_wrapper(
    col: Column,
    messageName: String,
    descFilePathOpt: Option[String],
    options: Map[String, String] = Map.empty): Column = {
    descFilePathOpt match {
      case Some(descFilePath) => functions.from_protobuf(
        col, messageName, descFilePath, options.asJava
      )
      case None => functions.from_protobuf(col, messageName, options.asJava)
    }
  }

  // A wrapper to invoke the right variable of to_protobuf() depending on arguments.
  private def to_protobuf_wrapper(
    col: Column, messageName: String, descFilePathOpt: Option[String]): Column = {
    descFilePathOpt match {
      case Some(descFilePath) => functions.to_protobuf(col, messageName, descFilePath)
      case None => functions.to_protobuf(col, messageName)
    }
  }

  test("roundtrip in to_protobuf and from_protobuf - struct") {
    val df = spark
      .range(1, 10)
      .select(struct(
        $"id",
        $"id".cast("string").as("string_value"),
        $"id".cast("int").as("int32_value"),
        $"id".cast("int").as("uint32_value"),
        $"id".cast("int").as("sint32_value"),
        $"id".cast("int").as("fixed32_value"),
        $"id".cast("int").as("sfixed32_value"),
        $"id".cast("long").as("int64_value"),
        $"id".cast("long").as("uint64_value"),
        $"id".cast("long").as("sint64_value"),
        $"id".cast("long").as("fixed64_value"),
        $"id".cast("long").as("sfixed64_value"),
        $"id".cast("double").as("double_value"),
        lit(1202.00).cast(org.apache.spark.sql.types.FloatType).as("float_value"),
        lit(true).as("bool_value"),
        lit("0".getBytes).as("bytes_value")).as("SimpleMessage"))

    checkWithFileAndClassName("SimpleMessage") {
      case (name, descFilePathOpt) =>
        val protoStructDF = df.select(
          to_protobuf_wrapper($"SimpleMessage", name, descFilePathOpt).as("proto"))
        val actualDf = protoStructDF.select(
          from_protobuf_wrapper($"proto", name, descFilePathOpt).as("proto.*"))
        checkAnswer(actualDf, df)
    }
  }

  test("roundtrip in from_protobuf and to_protobuf - Repeated") {

    val protoMessage = SimpleMessageRepeated
      .newBuilder()
      .setKey("key")
      .setValue("value")
      .addRboolValue(false)
      .addRboolValue(true)
      .addRdoubleValue(1092092.654d)
      .addRdoubleValue(1092093.654d)
      .addRfloatValue(10903.0f)
      .addRfloatValue(10902.0f)
      .addRnestedEnum(NestedEnum.NESTED_NOTHING)
      .addRnestedEnum(NestedEnum.NESTED_FIRST)
      .build()

    val df = Seq(protoMessage.toByteArray).toDF("value")

    checkWithFileAndClassName("SimpleMessageRepeated") {
      case (name, descFilePathOpt) =>
        val fromProtoDF = df.select(
          from_protobuf_wrapper($"value", name, descFilePathOpt).as("value_from"))
        val toProtoDF = fromProtoDF.select(
          to_protobuf_wrapper($"value_from", name, descFilePathOpt).as("value_to"))
        val toFromProtoDF = toProtoDF.select(
          from_protobuf_wrapper($"value_to", name, descFilePathOpt).as("value_to_from"))
        checkAnswer(fromProtoDF.select($"value_from.*"), toFromProtoDF.select($"value_to_from.*"))
    }
  }

  test("roundtrip in from_protobuf and to_protobuf - Repeated Message Once") {
    val repeatedMessageDesc = ProtobufUtils.buildDescriptor(testFileDesc, "RepeatedMessage")
    val basicMessageDesc = ProtobufUtils.buildDescriptor(testFileDesc, "BasicMessage")

    val basicMessage = DynamicMessage
      .newBuilder(basicMessageDesc)
      .setField(basicMessageDesc.findFieldByName("id"), 1111L)
      .setField(basicMessageDesc.findFieldByName("string_value"), "value")
      .setField(basicMessageDesc.findFieldByName("int32_value"), 12345)
      .setField(basicMessageDesc.findFieldByName("int64_value"), 0x90000000000L)
      .setField(basicMessageDesc.findFieldByName("double_value"), 10000000000.0d)
      .setField(basicMessageDesc.findFieldByName("float_value"), 10902.0f)
      .setField(basicMessageDesc.findFieldByName("bool_value"), true)
      .setField(
        basicMessageDesc.findFieldByName("bytes_value"),
        ByteString.copyFromUtf8("ProtobufDeserializer"))
      .build()

    val dynamicMessage = DynamicMessage
      .newBuilder(repeatedMessageDesc)
      .addRepeatedField(repeatedMessageDesc.findFieldByName("basic_message"), basicMessage)
      .build()

    val df = Seq(dynamicMessage.toByteArray).toDF("value")

    checkWithFileAndClassName("RepeatedMessage") {
      case (name, descFilePathOpt) =>
        val fromProtoDF = df.select(
          from_protobuf_wrapper($"value", name, descFilePathOpt).as("value_from"))
        val toProtoDF = fromProtoDF.select(
          to_protobuf_wrapper($"value_from", name, descFilePathOpt).as("value_to"))
        val toFromProtoDF = toProtoDF.select(
          from_protobuf_wrapper($"value_to", name, descFilePathOpt).as("value_to_from"))
        checkAnswer(fromProtoDF.select($"value_from.*"), toFromProtoDF.select($"value_to_from.*"))
    }
  }

  test("roundtrip in from_protobuf and to_protobuf - Repeated Message Twice") {
    val repeatedMessageDesc = ProtobufUtils.buildDescriptor(testFileDesc, "RepeatedMessage")
    val basicMessageDesc = ProtobufUtils.buildDescriptor(testFileDesc, "BasicMessage")

    val basicMessage1 = DynamicMessage
      .newBuilder(basicMessageDesc)
      .setField(basicMessageDesc.findFieldByName("id"), 1111L)
      .setField(basicMessageDesc.findFieldByName("string_value"), "value1")
      .setField(basicMessageDesc.findFieldByName("int32_value"), 12345)
      .setField(basicMessageDesc.findFieldByName("int64_value"), 0x90000000000L)
      .setField(basicMessageDesc.findFieldByName("double_value"), 10000000000.0d)
      .setField(basicMessageDesc.findFieldByName("float_value"), 10902.0f)
      .setField(basicMessageDesc.findFieldByName("bool_value"), true)
      .setField(
        basicMessageDesc.findFieldByName("bytes_value"),
        ByteString.copyFromUtf8("ProtobufDeserializer1"))
      .build()
    val basicMessage2 = DynamicMessage
      .newBuilder(basicMessageDesc)
      .setField(basicMessageDesc.findFieldByName("id"), 1112L)
      .setField(basicMessageDesc.findFieldByName("string_value"), "value2")
      .setField(basicMessageDesc.findFieldByName("int32_value"), 12346)
      .setField(basicMessageDesc.findFieldByName("int64_value"), 0x90000000000L)
      .setField(basicMessageDesc.findFieldByName("double_value"), 10000000000.0d)
      .setField(basicMessageDesc.findFieldByName("float_value"), 10903.0f)
      .setField(basicMessageDesc.findFieldByName("bool_value"), false)
      .setField(
        basicMessageDesc.findFieldByName("bytes_value"),
        ByteString.copyFromUtf8("ProtobufDeserializer2"))
      .build()

    val dynamicMessage = DynamicMessage
      .newBuilder(repeatedMessageDesc)
      .addRepeatedField(repeatedMessageDesc.findFieldByName("basic_message"), basicMessage1)
      .addRepeatedField(repeatedMessageDesc.findFieldByName("basic_message"), basicMessage2)
      .build()

    val df = Seq(dynamicMessage.toByteArray).toDF("value")

    checkWithFileAndClassName("RepeatedMessage") {
      case (name, descFilePathOpt) =>
        val fromProtoDF = df.select(
          from_protobuf_wrapper($"value", name, descFilePathOpt).as("value_from"))
        val toProtoDF = fromProtoDF.select(
          to_protobuf_wrapper($"value_from", name, descFilePathOpt).as("value_to"))
        val toFromProtoDF = toProtoDF.select(
          from_protobuf_wrapper($"value_to", name, descFilePathOpt).as("value_to_from"))
        checkAnswer(fromProtoDF.select($"value_from.*"), toFromProtoDF.select($"value_to_from.*"))
    }
  }

  test("roundtrip in from_protobuf and to_protobuf - Map") {
    val messageMapDesc = ProtobufUtils.buildDescriptor(testFileDesc, "SimpleMessageMap")

    val mapStr1 = DynamicMessage
      .newBuilder(messageMapDesc.findNestedTypeByName("StringMapdataEntry"))
      .setField(
        messageMapDesc.findNestedTypeByName("StringMapdataEntry").findFieldByName("key"),
        "string_key")
      .setField(
        messageMapDesc.findNestedTypeByName("StringMapdataEntry").findFieldByName("value"),
        "value1")
      .build()
    val mapStr2 = DynamicMessage
      .newBuilder(messageMapDesc.findNestedTypeByName("StringMapdataEntry"))
      .setField(
        messageMapDesc.findNestedTypeByName("StringMapdataEntry").findFieldByName("key"),
        "string_key")
      .setField(
        messageMapDesc.findNestedTypeByName("StringMapdataEntry").findFieldByName("value"),
        "value2")
      .build()
    val mapInt64 = DynamicMessage
      .newBuilder(messageMapDesc.findNestedTypeByName("Int64MapdataEntry"))
      .setField(
        messageMapDesc.findNestedTypeByName("Int64MapdataEntry").findFieldByName("key"),
        0x90000000000L)
      .setField(
        messageMapDesc.findNestedTypeByName("Int64MapdataEntry").findFieldByName("value"),
        0x90000000001L)
      .build()
    val mapInt32 = DynamicMessage
      .newBuilder(messageMapDesc.findNestedTypeByName("Int32MapdataEntry"))
      .setField(
        messageMapDesc.findNestedTypeByName("Int32MapdataEntry").findFieldByName("key"),
        12345)
      .setField(
        messageMapDesc.findNestedTypeByName("Int32MapdataEntry").findFieldByName("value"),
        54321)
      .build()
    val mapFloat = DynamicMessage
      .newBuilder(messageMapDesc.findNestedTypeByName("FloatMapdataEntry"))
      .setField(
        messageMapDesc.findNestedTypeByName("FloatMapdataEntry").findFieldByName("key"),
        "float_key")
      .setField(
        messageMapDesc.findNestedTypeByName("FloatMapdataEntry").findFieldByName("value"),
        109202.234f)
      .build()
    val mapDouble = DynamicMessage
      .newBuilder(messageMapDesc.findNestedTypeByName("DoubleMapdataEntry"))
      .setField(
        messageMapDesc.findNestedTypeByName("DoubleMapdataEntry").findFieldByName("key"),
        "double_key")
      .setField(
        messageMapDesc.findNestedTypeByName("DoubleMapdataEntry").findFieldByName("value"),
        109202.12d)
      .build()
    val mapBool = DynamicMessage
      .newBuilder(messageMapDesc.findNestedTypeByName("BoolMapdataEntry"))
      .setField(
        messageMapDesc.findNestedTypeByName("BoolMapdataEntry").findFieldByName("key"),
        true)
      .setField(
        messageMapDesc.findNestedTypeByName("BoolMapdataEntry").findFieldByName("value"),
        false)
      .build()

    val dynamicMessage = DynamicMessage
      .newBuilder(messageMapDesc)
      .setField(messageMapDesc.findFieldByName("key"), "key")
      .setField(messageMapDesc.findFieldByName("value"), "value")
      .addRepeatedField(messageMapDesc.findFieldByName("string_mapdata"), mapStr1)
      .addRepeatedField(messageMapDesc.findFieldByName("string_mapdata"), mapStr2)
      .addRepeatedField(messageMapDesc.findFieldByName("int64_mapdata"), mapInt64)
      .addRepeatedField(messageMapDesc.findFieldByName("int32_mapdata"), mapInt32)
      .addRepeatedField(messageMapDesc.findFieldByName("float_mapdata"), mapFloat)
      .addRepeatedField(messageMapDesc.findFieldByName("double_mapdata"), mapDouble)
      .addRepeatedField(messageMapDesc.findFieldByName("bool_mapdata"), mapBool)
      .build()

    val df = Seq(dynamicMessage.toByteArray).toDF("value")

    checkWithFileAndClassName("SimpleMessageMap") {
      case (name, descFilePathOpt) =>
        val fromProtoDF = df.select(
          from_protobuf_wrapper($"value", name, descFilePathOpt).as("value_from"))
        val toProtoDF = fromProtoDF.select(
          to_protobuf_wrapper($"value_from", name, descFilePathOpt).as("value_to"))
        val toFromProtoDF = toProtoDF.select(
          from_protobuf_wrapper($"value_to", name, descFilePathOpt).as("value_to_from"))
        checkAnswer(fromProtoDF.select($"value_from.*"), toFromProtoDF.select($"value_to_from.*"))
    }
  }

  test("roundtrip in from_protobuf and to_protobuf - Enum") {
    val messageEnumDesc = ProtobufUtils.buildDescriptor(testFileDesc, "SimpleMessageEnum")
    val basicEnumDesc = ProtobufUtils.buildDescriptor(testFileDesc, "BasicEnumMessage")

    val dynamicMessage = DynamicMessage
      .newBuilder(messageEnumDesc)
      .setField(messageEnumDesc.findFieldByName("key"), "key")
      .setField(messageEnumDesc.findFieldByName("value"), "value")
      .setField(
        messageEnumDesc.findFieldByName("nested_enum"),
        messageEnumDesc.findEnumTypeByName("NestedEnum").findValueByName("NESTED_NOTHING"))
      .setField(
        messageEnumDesc.findFieldByName("nested_enum"),
        messageEnumDesc.findEnumTypeByName("NestedEnum").findValueByName("NESTED_FIRST"))
      .setField(
        messageEnumDesc.findFieldByName("basic_enum"),
        basicEnumDesc.findEnumTypeByName("BasicEnum").findValueByName("FIRST"))
      .setField(
        messageEnumDesc.findFieldByName("basic_enum"),
        basicEnumDesc.findEnumTypeByName("BasicEnum").findValueByName("NOTHING"))
      .build()

    val df = Seq(dynamicMessage.toByteArray).toDF("value")

    checkWithFileAndClassName("SimpleMessageEnum") {
      case (name, descFilePathOpt) =>
        val fromProtoDF = df.select(
          from_protobuf_wrapper($"value", name, descFilePathOpt).as("value_from"))
        val toProtoDF = fromProtoDF.select(
          to_protobuf_wrapper($"value_from", name, descFilePathOpt).as("value_to"))
        val toFromProtoDF = toProtoDF.select(
          from_protobuf_wrapper($"value_to", name, descFilePathOpt).as("value_to_from"))
        checkAnswer(fromProtoDF.select($"value_from.*"), toFromProtoDF.select($"value_to_from.*"))
    }
  }

  test("round trip in from_protobuf and to_protobuf - Multiple Message") {
    val messageMultiDesc = ProtobufUtils.buildDescriptor(testFileDesc, "MultipleExample")
    val messageIncludeDesc = ProtobufUtils.buildDescriptor(testFileDesc, "IncludedExample")
    val messageOtherDesc = ProtobufUtils.buildDescriptor(testFileDesc, "OtherExample")

    val otherMessage = DynamicMessage
      .newBuilder(messageOtherDesc)
      .setField(messageOtherDesc.findFieldByName("other"), "other value")
      .build()

    val includeMessage = DynamicMessage
      .newBuilder(messageIncludeDesc)
      .setField(messageIncludeDesc.findFieldByName("included"), "included value")
      .setField(messageIncludeDesc.findFieldByName("other"), otherMessage)
      .build()

    val dynamicMessage = DynamicMessage
      .newBuilder(messageMultiDesc)
      .setField(messageMultiDesc.findFieldByName("included_example"), includeMessage)
      .build()

    val df = Seq(dynamicMessage.toByteArray).toDF("value")

    checkWithFileAndClassName("MultipleExample") {
      case (name, descFilePathOpt) =>
        val fromProtoDF = df.select(
          from_protobuf_wrapper($"value", name, descFilePathOpt).as("value_from"))
        val toProtoDF = fromProtoDF.select(
          to_protobuf_wrapper($"value_from", name, descFilePathOpt).as("value_to"))
        val toFromProtoDF = toProtoDF.select(
          from_protobuf_wrapper($"value_to", name, descFilePathOpt).as("value_to_from"))
        checkAnswer(fromProtoDF.select($"value_from.*"), toFromProtoDF.select($"value_to_from.*"))
    }

    // Simple recursion
    checkWithFileAndClassName("recursiveB") { // B -> A -> B
      case (name, descFilePathOpt) =>
        val e = intercept[AnalysisException] {
          emptyBinaryDF.select(
            from_protobuf_wrapper($"binary", name, descFilePathOpt).as("messageFromProto"))
            .show()
        }
        assert(e.getMessage.contains(
          "Found recursive reference in Protobuf schema, which can not be processed by Spark"
        ))
    }
  }

  test("Recursive fields in Protobuf should result in an error, C->D->Array(C)") {
    checkWithFileAndClassName("recursiveD") {
      case (name, descFilePathOpt) =>
        val e = intercept[AnalysisException] {
          emptyBinaryDF.select(
            from_protobuf_wrapper($"binary", name, descFilePathOpt).as("messageFromProto"))
            .show()
        }
        assert(e.getMessage.contains(
          "Found recursive reference in Protobuf schema, which can not be processed by Spark"
        ))
    }
  }

  test("Setting depth to 0 or -1 should trigger error on recursive fields (B -> A -> B)") {
    for (depth <- Seq("0", "-1")) {
      val e = intercept[AnalysisException] {
        emptyBinaryDF.select(
          functions.from_protobuf(
            $"binary", "recursiveB", testFileDesc,
            Map("recursive.fields.max.depth" -> depth).asJava
          ).as("messageFromProto")
        ).show()
      }
      assert(e.getMessage.contains(
        "Found recursive reference in Protobuf schema, which can not be processed by Spark"
      ))
    }
  }

  test("Handle extra fields : oldProducer -> newConsumer") {
    val testFileDesc = testFile("catalyst_types.desc", "protobuf/catalyst_types.desc")
    val oldProducer = ProtobufUtils.buildDescriptor(testFileDesc, "oldProducer")
    val newConsumer = ProtobufUtils.buildDescriptor(testFileDesc, "newConsumer")

    val oldProducerMessage = DynamicMessage
      .newBuilder(oldProducer)
      .setField(oldProducer.findFieldByName("key"), "key")
      .build()

    val df = Seq(oldProducerMessage.toByteArray).toDF("oldProducerData")
    val fromProtoDf = df.select(
      functions
        .from_protobuf($"oldProducerData", "newConsumer", testFileDesc)
        .as("fromProto"))

    val toProtoDf = fromProtoDf.select(
      functions
        .to_protobuf($"fromProto", "newConsumer", testFileDesc)
        .as("toProto"))

    val toProtoDfToFromProtoDf = toProtoDf.select(
      functions
        .from_protobuf($"toProto", "newConsumer", testFileDesc)
        .as("toProtoToFromProto"))

    val actualFieldNames =
      toProtoDfToFromProtoDf.select("toProtoToFromProto.*").schema.fields.toSeq.map(f => f.name)
    newConsumer.getFields.asScala.map { f =>
      {
        assert(actualFieldNames.contains(f.getName))

      }
    }
    assert(
      toProtoDfToFromProtoDf.select("toProtoToFromProto.value").take(1).toSeq(0).get(0) == null)
    assert(
      toProtoDfToFromProtoDf.select("toProtoToFromProto.actual.*").take(1).toSeq(0).get(0) == null)
  }

  test("Handle extra fields : newProducer -> oldConsumer") {
    val testFileDesc = testFile("catalyst_types.desc", "protobuf/catalyst_types.desc")
    val newProducer = ProtobufUtils.buildDescriptor(testFileDesc, "newProducer")
    val oldConsumer = ProtobufUtils.buildDescriptor(testFileDesc, "oldConsumer")

    val newProducerMessage = DynamicMessage
      .newBuilder(newProducer)
      .setField(newProducer.findFieldByName("key"), "key")
      .setField(newProducer.findFieldByName("value"), 1)
      .build()

    val df = Seq(newProducerMessage.toByteArray).toDF("newProducerData")
    val fromProtoDf = df.select(
      functions
        .from_protobuf($"newProducerData", "oldConsumer", testFileDesc)
        .as("oldConsumerProto"))

    val expectedFieldNames = oldConsumer.getFields.asScala.map(f => f.getName)
    fromProtoDf.select("oldConsumerProto.*").schema.fields.toSeq.map { f =>
      {
        assert(expectedFieldNames.contains(f.name))
      }
    }
  }

  test("roundtrip in to_protobuf and from_protobuf - with nulls") {
    val schema = StructType(
      StructField("requiredMsg",
        StructType(
          StructField("key", StringType, nullable = false) ::
            StructField("col_1", IntegerType, nullable = true) ::
            StructField("col_2", StringType, nullable = false) ::
            StructField("col_3", IntegerType, nullable = true) :: Nil
        ),
        nullable = true
      ) :: Nil
    )
    val inputDf = spark.createDataFrame(
      spark.sparkContext.parallelize(Seq(
        Row(Row("key1", null, "value2", null))
      )),
      schema
    )

    val toProtobuf = inputDf.select(
      functions.to_protobuf($"requiredMsg", "requiredMsg", testFileDesc)
        .as("to_proto"))

    val binary = toProtobuf.take(1).toSeq(0).get(0).asInstanceOf[Array[Byte]]

    val messageDescriptor = ProtobufUtils.buildDescriptor(testFileDesc, "requiredMsg")
    val actualMessage = DynamicMessage.parseFrom(messageDescriptor, binary)

    assert(actualMessage.getField(messageDescriptor.findFieldByName("key"))
      == inputDf.select("requiredMsg.key").take(1).toSeq(0).get(0))
    assert(actualMessage.getField(messageDescriptor.findFieldByName("col_2"))
      == inputDf.select("requiredMsg.col_2").take(1).toSeq(0).get(0))
    assert(actualMessage.getField(messageDescriptor.findFieldByName("col_1")) == 0)
    assert(actualMessage.getField(messageDescriptor.findFieldByName("col_3")) == 0)

    val fromProtoDf = toProtobuf.select(
      functions.from_protobuf($"to_proto", "requiredMsg", testFileDesc) as 'from_proto)

    assert(fromProtoDf.select("from_proto.key").take(1).toSeq(0).get(0)
      == inputDf.select("requiredMsg.key").take(1).toSeq(0).get(0))
    assert(fromProtoDf.select("from_proto.col_2").take(1).toSeq(0).get(0)
      == inputDf.select("requiredMsg.col_2").take(1).toSeq(0).get(0))
    assert(fromProtoDf.select("from_proto.col_1").take(1).toSeq(0).get(0) == null)
    assert(fromProtoDf.select("from_proto.col_3").take(1).toSeq(0).get(0) == null)
  }

  test("from_protobuf filter to_protobuf") {
    val basicMessageDesc = ProtobufUtils.buildDescriptor(testFileDesc, "BasicMessage")

    val basicMessage = DynamicMessage
      .newBuilder(basicMessageDesc)
      .setField(basicMessageDesc.findFieldByName("id"), 1111L)
      .setField(basicMessageDesc.findFieldByName("string_value"), "slam")
      .setField(basicMessageDesc.findFieldByName("int32_value"), 12345)
      .setField(basicMessageDesc.findFieldByName("int64_value"), 0x90000000000L)
      .setField(basicMessageDesc.findFieldByName("double_value"), 10000000000.0d)
      .setField(basicMessageDesc.findFieldByName("float_value"), 10902.0f)
      .setField(basicMessageDesc.findFieldByName("bool_value"), true)
      .setField(
        basicMessageDesc.findFieldByName("bytes_value"),
        ByteString.copyFromUtf8("ProtobufDeserializer"))
      .build()

    val df = Seq(basicMessage.toByteArray).toDF("value")

    val resultFrom = df
      .select(from_protobuf_wrapper($"value", "BasicMessage", Some(testFileDesc)) as 'sample)
      .where("sample.string_value == \"slam\"")

    val resultToFrom = resultFrom
      .select(to_protobuf_wrapper($"sample", "BasicMessage", Some(testFileDesc)) as 'value)
      .select(from_protobuf_wrapper($"value", "BasicMessage", Some(testFileDesc)) as 'sample)
      .where("sample.string_value == \"slam\"")

    assert(resultFrom.except(resultToFrom).isEmpty)
  }

  test("Handle TimestampType between to_protobuf and from_protobuf") {
    val schema = StructType(
      StructField("timeStampMsg",
        StructType(
          StructField("key", StringType, nullable = true) ::
            StructField("stmp", TimestampType, nullable = true) :: Nil
        ),
        nullable = true
      ) :: Nil
    )

    val inputDf = spark.createDataFrame(
      spark.sparkContext.parallelize(Seq(
        Row(Row("key1", Timestamp.valueOf("2016-05-09 10:12:43.999")))
      )),
      schema
    )

    checkWithFileAndClassName("timeStampMsg") {
      case (name, descFilePathOpt) =>
        val toProtoDf = inputDf
          .select(to_protobuf_wrapper($"timeStampMsg", name, descFilePathOpt) as 'to_proto)

        val fromProtoDf = toProtoDf
          .select(from_protobuf_wrapper($"to_proto", name, descFilePathOpt) as 'timeStampMsg)

        val actualFields = fromProtoDf.schema.fields.toList
        val expectedFields = inputDf.schema.fields.toList

        assert(actualFields.size === expectedFields.size)
        assert(actualFields === expectedFields)
        assert(fromProtoDf.select("timeStampMsg.key").take(1).toSeq(0).get(0)
          === inputDf.select("timeStampMsg.key").take(1).toSeq(0).get(0))
        assert(fromProtoDf.select("timeStampMsg.stmp").take(1).toSeq(0).get(0)
          === inputDf.select("timeStampMsg.stmp").take(1).toSeq(0).get(0))
    }
  }

  test("Handle DayTimeIntervalType between to_protobuf and from_protobuf") {
    val schema = StructType(
      StructField("durationMsg",
        StructType(
          StructField("key", StringType, nullable = true) ::
            StructField("duration",
              DayTimeIntervalType.defaultConcreteType, nullable = true) :: Nil
        ),
        nullable = true
      ) :: Nil
    )

    val inputDf = spark.createDataFrame(
      spark.sparkContext.parallelize(Seq(
        Row(Row("key1",
          Duration.ofDays(1).plusHours(2).plusMinutes(3).plusSeconds(4)
        ))
      )),
      schema
    )

    checkWithFileAndClassName("durationMsg") {
      case (name, descFilePathOpt) =>
        val toProtoDf = inputDf
          .select(to_protobuf_wrapper($"durationMsg", name, descFilePathOpt) as 'to_proto)

        val fromProtoDf = toProtoDf
          .select(from_protobuf_wrapper($"to_proto", name, descFilePathOpt) as 'durationMsg)

        val actualFields = fromProtoDf.schema.fields.toList
        val expectedFields = inputDf.schema.fields.toList

        assert(actualFields.size === expectedFields.size)
        assert(actualFields === expectedFields)
        assert(fromProtoDf.select("durationMsg.key").take(1).toSeq(0).get(0)
          === inputDf.select("durationMsg.key").take(1).toSeq(0).get(0))
        assert(fromProtoDf.select("durationMsg.duration").take(1).toSeq(0).get(0)
          === inputDf.select("durationMsg.duration").take(1).toSeq(0).get(0))
    }
  }

  test("raise cannot construct protobuf descriptor error") {
    val df = Seq(ByteString.empty().toByteArray).toDF("value")
    val testFileDescriptor =
      testFile("basicmessage_noimports.desc", "protobuf/basicmessage_noimports.desc")

    val e = intercept[AnalysisException] {
      df.select(functions.from_protobuf($"value", "BasicMessage", testFileDescriptor) as 'sample)
        .where("sample.string_value == \"slam\"").show()
    }
    checkError(
      exception = e,
      errorClass = "CANNOT_CONSTRUCT_PROTOBUF_DESCRIPTOR",
      parameters = Map("descFilePath" -> testFileDescriptor))
  }

  test("Verify OneOf field between from_protobuf -> to_protobuf and struct -> from_protobuf") {
    val descriptor = ProtobufUtils.buildDescriptor(testFileDesc, "OneOfEvent")
    val oneOfEvent = OneOfEvent.newBuilder()
      .setKey("key")
      .setCol1(123)
      .setCol3(109202L)
      .setCol2("col2value")
      .addCol4("col4value").build()

    val df = Seq(oneOfEvent.toByteArray).toDF("value")

    checkWithFileAndClassName("OneOfEvent") {
      case (name, descFilePathOpt) =>
        val fromProtoDf = df.select(
          from_protobuf_wrapper($"value", name, descFilePathOpt) as 'sample)
        val toDf = fromProtoDf.select(
          to_protobuf_wrapper($"sample", name, descFilePathOpt) as 'toProto)
        val toFromDf = toDf.select(
          from_protobuf_wrapper($"toProto", name, descFilePathOpt) as 'fromToProto)
        checkAnswer(fromProtoDf, toFromDf)
        val actualFieldNames = fromProtoDf.select("sample.*").schema.fields.toSeq.map(f => f.name)
        descriptor.getFields.asScala.map(f => {
          assert(actualFieldNames.contains(f.getName))
        })

        val eventFromSpark = OneOfEvent.parseFrom(
          toDf.select("toProto").take(1).toSeq(0).getAs[Array[Byte]](0))
        // OneOf field: the last set value(by order) will overwrite all previous ones.
        assert(eventFromSpark.getCol2.equals("col2value"))
        assert(eventFromSpark.getCol3 == 0)
        val expectedFields = descriptor.getFields.asScala.map(f => f.getName)
        eventFromSpark.getDescriptorForType.getFields.asScala.map(f => {
          assert(expectedFields.contains(f.getName))
        })

        val schema = DataType.fromJson(
          """
            | {
            |   "type":"struct",
            |   "fields":[
            |     {"name":"sample","nullable":true,"type":{
            |       "type":"struct",
            |       "fields":[
            |         {"name":"key","type":"string","nullable":true},
            |         {"name":"col_1","type":"integer","nullable":true},
            |         {"name":"col_2","type":"string","nullable":true},
            |         {"name":"col_3","type":"long","nullable":true},
            |         {"name":"col_4","nullable":true,"type":{
            |           "type":"array","elementType":"string","containsNull":false}}
            |       ]}
            |     }
            |   ]
            | }
            |""".stripMargin).asInstanceOf[StructType]
        assert(fromProtoDf.schema == schema)

        val data = Seq(
          Row(Row("key", 123, "col2value", 109202L, Seq("col4value"))),
          Row(Row("key2", null, null, null, null)) // Leave the rest null, including "col_4" array.
        )
        val dataDf = spark.createDataFrame(spark.sparkContext.parallelize(data), schema)
        val dataDfToProto = dataDf.select(
          to_protobuf_wrapper($"sample", name, descFilePathOpt) as 'toProto)

        val toProtoResults = dataDfToProto.select("toProto").collect()
        val eventFromSparkSchema = OneOfEvent.parseFrom(toProtoResults(0).getAs[Array[Byte]](0))
        assert(eventFromSparkSchema.getCol2.isEmpty)
        assert(eventFromSparkSchema.getCol3 == 109202L)
        eventFromSparkSchema.getDescriptorForType.getFields.asScala.map(f => {
          assert(expectedFields.contains(f.getName))
        })
        val secondEventFromSpark = OneOfEvent.parseFrom(toProtoResults(1).getAs[Array[Byte]](0))
        assert(secondEventFromSpark.getKey == "key2")
    }
  }

  test("Fail for recursion field with complex schema without recursive.fields.max.depth") {
    checkWithFileAndClassName("EventWithRecursion") {
      case (name, descFilePathOpt) =>
        val e = intercept[AnalysisException] {
          emptyBinaryDF.select(
            from_protobuf_wrapper($"binary", name, descFilePathOpt).as("messageFromProto"))
            .show()
        }
        assert(e.getMessage.contains(
          "Found recursive reference in Protobuf schema, which can not be processed by Spark"
        ))
    }
  }

  test("Verify recursion field with complex schema with recursive.fields.max.depth") {
    val descriptor = ProtobufUtils.buildDescriptor(testFileDesc, "Employee")

    val manager = Employee.newBuilder().setFirstName("firstName").setLastName("lastName").build()
    val em2 = EM2.newBuilder().setTeamsize(100).setEm2Manager(manager).build()
    val em = EM.newBuilder().setTeamsize(100).setEmManager(manager).build()
    val ic = IC.newBuilder().addSkills("java").setIcManager(manager).build()
    val employee = Employee.newBuilder().setFirstName("firstName")
      .setLastName("lastName").setEm2(em2).setEm(em).setIc(ic).build()

    val df = Seq(employee.toByteArray).toDF("protoEvent")
    val options = new java.util.HashMap[String, String]()
    options.put("recursive.fields.max.depth", "2")

    val fromProtoDf = df.select(
      functions.from_protobuf($"protoEvent", "Employee", testFileDesc, options) as 'sample)

    val toDf = fromProtoDf.select(
      functions.to_protobuf($"sample", "Employee", testFileDesc) as 'toProto)
    val toFromDf = toDf.select(
      functions.from_protobuf($"toProto",
        "Employee",
        testFileDesc,
        options) as 'fromToProto)

    checkAnswer(fromProtoDf, toFromDf)

    val actualFieldNames = fromProtoDf.select("sample.*").schema.fields.toSeq.map(f => f.name)
    descriptor.getFields.asScala.map(f => {
      assert(actualFieldNames.contains(f.getName))
    })

    val eventFromSpark = Employee.parseFrom(
      toDf.select("toProto").take(1).toSeq(0).getAs[Array[Byte]](0))

    assert(eventFromSpark.getIc.getIcManager.getFirstName.equals("firstName"))
    assert(eventFromSpark.getIc.getIcManager.getLastName.equals("lastName"))
    assert(eventFromSpark.getEm2.getEm2Manager.getFirstName.isEmpty)
  }

  test("Verify OneOf field with recursive fields between from_protobuf -> to_protobuf." +
    "and struct -> from_protobuf") {
    val descriptor = ProtobufUtils.buildDescriptor(testFileDesc, "OneOfEventWithRecursion")

    val nestedTwo = OneOfEventWithRecursion.newBuilder()
      .setKey("keyNested2").setValue("valueNested2").build()
    val nestedOne = EventRecursiveA.newBuilder()
      .setKey("keyNested1")
      .setRecursiveOneOffInA(nestedTwo).build()
    val oneOfRecursionEvent = OneOfEventWithRecursion.newBuilder()
      .setKey("keyNested0")
      .setValue("valueNested0")
      .setRecursiveA(nestedOne).build()
    val recursiveA = EventRecursiveA.newBuilder().setKey("recursiveAKey")
      .setRecursiveOneOffInA(oneOfRecursionEvent).build()
    val recursiveB = EventRecursiveB.newBuilder()
      .setKey("recursiveBKey")
      .setValue("recursiveBvalue").build()
    val oneOfEventWithRecursion = OneOfEventWithRecursion.newBuilder()
      .setKey("key")
      .setValue("value")
      .setRecursiveB(recursiveB)
      .setRecursiveA(recursiveA).build()

    val df = Seq(oneOfEventWithRecursion.toByteArray).toDF("value")

    val options = new java.util.HashMap[String, String]()
    options.put("recursive.fields.max.depth", "2") // Recursive fields appear twice.

    val fromProtoDf = df.select(
      functions.from_protobuf($"value",
        "OneOfEventWithRecursion",
        testFileDesc, options) as 'sample)
    val toDf = fromProtoDf.select(
      functions.to_protobuf($"sample", "OneOfEventWithRecursion", testFileDesc) as 'toProto)
    val toFromDf = toDf.select(
      functions.from_protobuf($"toProto",
        "OneOfEventWithRecursion",
        testFileDesc,
        options) as 'fromToProto)

    checkAnswer(fromProtoDf, toFromDf)

    val actualFieldNames = fromProtoDf.select("sample.*").schema.fields.toSeq.map(f => f.name)
    descriptor.getFields.asScala.map(f => {
      assert(actualFieldNames.contains(f.getName))
    })

    val eventFromSpark = OneOfEventWithRecursion.parseFrom(
      toDf.select("toProto").take(1).toSeq(0).getAs[Array[Byte]](0))

    var recursiveField = eventFromSpark.getRecursiveA.getRecursiveOneOffInA
    assert(recursiveField.getKey.equals("keyNested0"))
    assert(recursiveField.getValue.equals("valueNested0"))
    assert(recursiveField.getRecursiveA.getKey.equals("keyNested1"))
    assert(recursiveField.getRecursiveA.getRecursiveOneOffInA.getKey.isEmpty())

    val expectedFields = descriptor.getFields.asScala.map(f => f.getName)
    eventFromSpark.getDescriptorForType.getFields.asScala.map(f => {
      assert(expectedFields.contains(f.getName))
    })

    val schemaDDL =
      """
        | -- OneOfEvenWithRecursion with max depth 2.
        | sample STRUCT< -- 1st level for OneOffWithRecursion
        |     key string,
        |     recursiveA STRUCT< -- 1st level for RecursiveA
        |         recursiveOneOffInA STRUCT< -- 2st level for OneOffWithRecursion
        |             key string,
        |             recursiveA STRUCT< -- 2st level for RecursiveA
        |                 key string
        |                 -- Removed recursiveOneOffInA: 3rd level for OneOffWithRecursion
        |             >,
        |             recursiveB STRUCT<
        |                 key string,
        |                 value string
        |                 -- Removed recursiveOneOffInB: 3rd level for OneOffWithRecursion
        |             >,
        |             value string
        |         >,
        |         key string
        |     >,
        |     recursiveB STRUCT< -- 1st level for RecursiveB
        |         key string,
        |         value string,
        |         recursiveOneOffInB STRUCT< -- 2st level for OneOffWithRecursion
        |             key string,
        |             recursiveA STRUCT< -- 1st level for RecursiveA
        |                 key string
        |                 -- Removed recursiveOneOffInA: 3rd level for OneOffWithRecursion
        |             >,
        |             recursiveB STRUCT<
        |                 key string,
        |                 value string
        |                 -- Removed recursiveOneOffInB: 3rd level for OneOffWithRecursion
        |             >,
        |             value string
        |         >
        |     >,
        |     value string
        | >
        |""".stripMargin
    val schema = structFromDDL(schemaDDL)
    assert(fromProtoDf.schema == schema)
    val data = Seq(
      Row(
        Row("key1",
          Row(
            Row("keyNested0", null, null, "valueNested0"),
            "recursiveAKey"),
          null,
          "value1")
      )
    )
    val dataDf = spark.createDataFrame(spark.sparkContext.parallelize(data), schema)
    val dataDfToProto = dataDf.select(
      functions.to_protobuf($"sample", "OneOfEventWithRecursion", testFileDesc) as 'toProto)

    val eventFromSparkSchema = OneOfEventWithRecursion.parseFrom(
      dataDfToProto.select("toProto").take(1).toSeq(0).getAs[Array[Byte]](0))
    recursiveField = eventFromSparkSchema.getRecursiveA.getRecursiveOneOffInA
    assert(recursiveField.getKey.equals("keyNested0"))
    assert(recursiveField.getValue.equals("valueNested0"))
    assert(recursiveField.getRecursiveA.getKey.isEmpty())
    eventFromSparkSchema.getDescriptorForType.getFields.asScala.map(f => {
      assert(expectedFields.contains(f.getName))
    })
  }

  test("Verify recursive.fields.max.depth Levels 1,2, and 3 with Simple Schema") {
    val eventPerson3 = EventPerson.newBuilder().setName("person3").build()
    val eventPerson2 = EventPerson.newBuilder().setName("person2").setBff(eventPerson3).build()
    val eventPerson1 = EventPerson.newBuilder().setName("person1").setBff(eventPerson2).build()
    val eventPerson0 = EventPerson.newBuilder().setName("person0").setBff(eventPerson1).build()
    val df = Seq(eventPerson0.toByteArray).toDF("value")

    val optionsZero = new java.util.HashMap[String, String]()
    optionsZero.put("recursive.fields.max.depth", "1")
    val schemaOne = structFromDDL(
      "sample STRUCT<name: STRING>" // 'bff' field is dropped to due to limit of 1.
    )
    val expectedDfOne = spark.createDataFrame(
      spark.sparkContext.parallelize(Seq(Row(Row("person0", null)))), schemaOne)
    testFromProtobufWithOptions(df, expectedDfOne, optionsZero, "EventPerson")

    val optionsTwo = new java.util.HashMap[String, String]()
    optionsTwo.put("recursive.fields.max.depth", "2")
    val schemaTwo = structFromDDL(
      """
        | sample STRUCT<
        |     name: STRING,
        |     bff: STRUCT<name: STRING> -- Recursion is terminated here.
        | >
        |""".stripMargin)
    val expectedDfTwo = spark.createDataFrame(
      spark.sparkContext.parallelize(Seq(Row(Row("person0", Row("person1", null))))), schemaTwo)
    testFromProtobufWithOptions(df, expectedDfTwo, optionsTwo, "EventPerson")

    val optionsThree = new java.util.HashMap[String, String]()
    optionsThree.put("recursive.fields.max.depth", "3")
    val schemaThree = structFromDDL(
      """
        | sample STRUCT<
        |     name: STRING,
        |     bff: STRUCT<
        |         name: STRING,
        |         bff: STRUCT<name: STRING>
        |     >
        | >
        |""".stripMargin)
    val expectedDfThree = spark.createDataFrame(spark.sparkContext.parallelize(
      Seq(Row(Row("person0", Row("person1", Row("person2", null)))))), schemaThree)
    testFromProtobufWithOptions(df, expectedDfThree, optionsThree, "EventPerson")

    // Test recursive level 1 with EventPersonWrapper. In this case the top level struct
    // 'EventPersonWrapper' itself does not recurse unlike 'EventPerson'.
    // "bff" appears twice: Once allowed recursion and second time as terminated "null" type.
    val wrapperSchemaOne = structFromDDL(
      """
        | sample STRUCT<
        |     person: STRUCT< -- 1st level
        |         name: STRING,
        |         bff: STRUCT<name: STRING> -- 2nd level. Inner 3rd level Person is dropped.
        |     >
        | >
        |""".stripMargin).asInstanceOf[StructType]
    val expectedWrapperDfTwo = spark.createDataFrame(
      spark.sparkContext.parallelize(Seq(Row(Row(Row("person0", Row("person1", null)))))),
      wrapperSchemaOne)
    testFromProtobufWithOptions(
      Seq(EventPersonWrapper.newBuilder().setPerson(eventPerson0).build().toByteArray).toDF(),
      expectedWrapperDfTwo,
      optionsTwo,
      "EventPersonWrapper"
    )
  }

  test("Verify exceptions are correctly propagated with errors") {
    // This triggers an query compilation error and ensures that original exception is
    // also included in in the exception.

    val invalidDescPath = "/non/existent/path.desc"

    val ex = intercept[AnalysisException] {
      Seq(Array[Byte]())
        .toDF()
        .select(
          functions.from_protobuf($"value", "SomeMessage", invalidDescPath)
        ).collect()
    }
    checkError(
      ex,
      errorClass = "PROTOBUF_DESCRIPTOR_FILE_NOT_FOUND",
      parameters = Map("filePath" -> "/non/existent/path.desc")
    )
    assert(ex.getCause != null)
    assert(ex.getCause.getMessage.matches(".*No such file.*"), ex.getCause.getMessage())
  }

  test("Recursive fields in arrays and maps") {
    // Verifies schema for recursive proto in an array field & map field.
    val options = Map("recursive.fields.max.depth" -> "3")

    checkWithFileAndClassName("PersonWithRecursiveArray") {
      case (name, descFilePathOpt) =>
        val expectedSchema = StructType(
          // DDL: "proto STRUCT<name: string, friends: array<
          //    struct<name: string, friends: array<struct<name: string>>>>>"
          // Can not use DataType.fromDDL(), it does not support "containsNull: false" for arrays.
          StructField("proto",
            StructType( // 1st level
              StructField("name", StringType) :: StructField("friends", // 2nd level
                ArrayType(
                  StructType(StructField("name", StringType) :: StructField("friends", // 3rd level
                    ArrayType(
                      StructType(StructField("name", StringType) :: Nil), // 4th, array dropped
                      containsNull = false)
                  ):: Nil),
                  containsNull = false)
              ) :: Nil
            )
          ) :: Nil
        )

        val df = emptyBinaryDF.select(
          from_protobuf_wrapper($"binary", name, descFilePathOpt, options).as("proto")
        )
        assert(df.schema == expectedSchema)
    }

    checkWithFileAndClassName("PersonWithRecursiveMap") {
      case (name, descFilePathOpt) =>
        val expectedSchema = StructType(
          // DDL: "proto STRUCT<name: string, groups: map<
          //    struct<name: string, group: map<struct<name: string>>>>>"
          StructField("proto",
            StructType( // 1st level
              StructField("name", StringType) :: StructField("groups", // 2nd level
                MapType(
                  StringType,
                  StructType(StructField("name", StringType) :: StructField("groups", // 3rd level
                    MapType(
                      StringType,
                      StructType(StructField("name", StringType) :: Nil), // 4th, array dropped
                      valueContainsNull = false)
                  ):: Nil),
                  valueContainsNull = false)
              ) :: Nil
            )
          ) :: Nil
        )

        val df = emptyBinaryDF.select(
          from_protobuf_wrapper($"binary", name, descFilePathOpt, options).as("proto")
        )
        assert(df.schema == expectedSchema)
    }
  }

  test("Corner case: empty recursive proto fields should be dropped") {
    // This verifies that a empty proto like 'message A { A a = 1}' are completely dropped
    // irrespective of max depth setting.

    val options = Map("recursive.fields.max.depth" -> "4")

    // EmptyRecursiveProto at the top level. It will be an empty struct.
    checkWithFileAndClassName("EmptyRecursiveProto") {
      case (name, descFilePathOpt) =>
          val df = emptyBinaryDF.select(
            from_protobuf_wrapper($"binary", name, descFilePathOpt, options).as("empty_proto")
          )
        assert(df.schema == structFromDDL("empty_proto struct<>"))
    }

    // EmptyRecursiveProto at inner level.
    checkWithFileAndClassName("EmptyRecursiveProtoWrapper") {
      case (name, descFilePathOpt) =>
        val df = emptyBinaryDF.select(
          from_protobuf_wrapper($"binary", name, descFilePathOpt, options).as("wrapper")
        )
        // 'empty_recursive' field is dropped from the schema. Only "name" is present.
        assert(df.schema == structFromDDL("wrapper struct<name: string>"))
    }
  }

  def testFromProtobufWithOptions(
    df: DataFrame,
    expectedDf: DataFrame,
    options: java.util.HashMap[String, String],
    messageName: String): Unit = {
    val fromProtoDf = df.select(
      functions.from_protobuf($"value", messageName, testFileDesc, options) as 'sample)
    assert(expectedDf.schema === fromProtoDf.schema)
    checkAnswer(fromProtoDf, expectedDf)
  }
}
