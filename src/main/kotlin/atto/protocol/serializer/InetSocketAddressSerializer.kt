package atto.protocol.serializer

import cash.atto.commons.AttoByteBuffer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.net.InetSocketAddress

object InetSocketAddressSerializer : KSerializer<InetSocketAddress> {
    override val descriptor = PrimitiveSerialDescriptor("InetSocketAddress", PrimitiveKind.BYTE)

    override fun serialize(encoder: Encoder, value: InetSocketAddress) {
        val byteArray = AttoByteBuffer(18).add(value).toByteArray()
        encoder.encodeSerializableValue(ByteArraySerializer(), byteArray)
    }

    override fun deserialize(decoder: Decoder): InetSocketAddress {
        val byteArray = decoder.decodeSerializableValue(ByteArraySerializer())
        return AttoByteBuffer(byteArray).getInetSocketAddress()
    }
}