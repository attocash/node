package cash.atto.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.schema.ProtoBufSchemaGenerator

@OptIn(ExperimentalSerializationApi::class)
fun main() {
    println(ProtoBufSchemaGenerator.generateSchemaText(AttoMessage.serializer().descriptor))
}
