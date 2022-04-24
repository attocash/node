package org.atto.protocol

import com.fasterxml.jackson.annotation.JsonIgnore
import org.atto.commons.AttoNetwork
import org.atto.commons.AttoPublicKey
import java.net.InetSocketAddress

data class AttoNode(
    val network: AttoNetwork,
    val protocolVersion: UShort,
    val minimalProtocolVersion: UShort,
    val publicKey: AttoPublicKey,
    val socketAddress: InetSocketAddress,
    val features: Set<NodeFeature>
) {
    companion object {
        val maxFeaturesSize = 5
        val size = 56 + maxFeaturesSize
    }

    @JsonIgnore
    fun isVoter(): Boolean {
        return features.contains(NodeFeature.VOTING)
    }

    @JsonIgnore
    fun isHistorical(): Boolean {
        return features.contains(NodeFeature.HISTORICAL)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AttoNode

        if (network != other.network) return false
        if (protocolVersion != other.protocolVersion) return false
        if (!publicKey.value.contentEquals(other.publicKey.value)) return false
        if (socketAddress != other.socketAddress) return false
        if (features != other.features) return false

        return true
    }

    override fun hashCode(): Int {
        var result = network.hashCode()
        result = 31 * result + protocolVersion.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + socketAddress.hashCode()
        result = 31 * result + features.hashCode()
        return result
    }
}

enum class NodeFeature(val code: UByte) {
    VOTING(1u),
    HISTORICAL(2u),

    UNKNOWN(UByte.MAX_VALUE);

    companion object {
        private val map = values().associateBy(NodeFeature::code)
        fun from(code: UByte): NodeFeature {
            return map.getOrDefault(code, UNKNOWN)
        }
    }
}