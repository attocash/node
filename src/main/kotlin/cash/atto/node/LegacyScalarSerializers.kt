package cash.atto.node

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoInstant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object LegacyAmountSerializer : KSerializer<AttoAmount> {
    override val descriptor = ULong.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: AttoAmount,
    ) = ULong.serializer().serialize(encoder, value.raw)

    override fun deserialize(decoder: Decoder) = AttoAmount(ULong.serializer().deserialize(decoder))
}

object LegacyHeightSerializer : KSerializer<AttoHeight> {
    override val descriptor = ULong.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: AttoHeight,
    ) = ULong.serializer().serialize(encoder, value.value)

    override fun deserialize(decoder: Decoder) = AttoHeight(ULong.serializer().deserialize(decoder))
}

object LegacyInstantSerializer : KSerializer<AttoInstant> {
    override val descriptor = Long.serializer().descriptor

    override fun serialize(
        encoder: Encoder,
        value: AttoInstant,
    ) = encoder.encodeLong(value.toEpochMilliseconds())

    override fun deserialize(decoder: Decoder) = AttoInstant.fromEpochMilliseconds(decoder.decodeLong())
}
