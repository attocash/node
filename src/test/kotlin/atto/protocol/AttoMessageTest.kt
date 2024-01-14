package atto.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.schema.ProtoBufSchemaGenerator


@OptIn(ExperimentalSerializationApi::class)
fun main() {
    val descriptors = messageSerializerMap.values.map { it.descriptor }
    println(ProtoBufSchemaGenerator.generateSchemaText(descriptors))
}