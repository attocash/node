@file:OptIn(ExperimentalSerializationApi::class)

package atto.protocol

import atto.protocol.serializer.URISerializer
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber
import java.net.URI

@Serializable
data class AttoNode(
    @ProtoNumber(0) val network: AttoNetwork,
    @ProtoNumber(1) val protocolVersion: UShort,
    @ProtoNumber(2) val algorithm: AttoAlgorithm,
    @ProtoNumber(3) val publicKey: AttoPublicKey,
    @ProtoNumber(4) @Serializable(with = URISerializer::class) val publicUri: URI,
    @ProtoNumber(5) val features: Set<NodeFeature>
) {

    @Transient
    val minProtocolVersion: UShort = when (protocolVersion) {
        0.toUShort() -> 0u
        1.toUShort() -> 0u
        else -> (protocolVersion - 2u).toUShort()
    }

    @Transient
    val maxProtocolVersion = (protocolVersion + 2u).toUShort()

    fun isVoter(): Boolean {
        return features.contains(NodeFeature.VOTING)
    }

    fun isNotVoter(): Boolean {
        return !isVoter()
    }

    fun isHistorical(): Boolean {
        return features.contains(NodeFeature.HISTORICAL)
    }

    fun isNotHistorical(): Boolean {
        return !isHistorical()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AttoNode

        if (network != other.network) return false
        if (protocolVersion != other.protocolVersion) return false
        if (algorithm != other.algorithm) return false
        if (!publicKey.value.contentEquals(other.publicKey.value)) return false
        if (publicUri != other.publicUri) return false
        if (features != other.features) return false

        return true
    }

    override fun hashCode(): Int {
        var result = network.hashCode()
        result = 31 * result + protocolVersion.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + publicUri.hashCode()
        result = 31 * result + features.hashCode()
        return result
    }
}

@Serializable
enum class NodeFeature(val code: UByte) {
    @ProtoNumber(255)
    UNKNOWN(UByte.MAX_VALUE),

    @ProtoNumber(0)
    VOTING(0u),

    @ProtoNumber(1)
    HISTORICAL(1u);

    companion object {
        private val map = entries.associateBy(NodeFeature::code)
        fun from(code: UByte): NodeFeature {
            return NodeFeature.map.getOrDefault(code, UNKNOWN)
        }
    }
}