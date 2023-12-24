package atto.protocol

import cash.atto.commons.AttoByteBuffer
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import com.fasterxml.jackson.annotation.JsonIgnore
import java.net.InetSocketAddress
import kotlin.math.min

data class AttoNode(
    val network: AttoNetwork,
    val protocolVersion: UShort,
    val publicKey: AttoPublicKey,
    val socketAddress: InetSocketAddress,
    val features: Set<NodeFeature>
) {
    val minProtocolVersion: UShort = when (protocolVersion) {
        0.toUShort() -> 0u
        1.toUShort() -> 0u
        else -> (protocolVersion - 2u).toUShort()
    }

    val maxProtocolVersion = (protocolVersion + 2u).toUShort()

    companion object {
        val maxFeaturesSize = 5
        val size = 56 + AttoNode.Companion.maxFeaturesSize

        fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoNode? {
            if (byteBuffer.size < size) {
                return null
            }

            val featuresSize = min(byteBuffer.getByte(55).toInt(), maxFeaturesSize)
            val features = HashSet<NodeFeature>(featuresSize)
            for (i in 0 until featuresSize) {
                val feature = NodeFeature.from(byteBuffer.getUByte(56 + i))
                if (feature != NodeFeature.UNKNOWN) {
                    features.add(feature)
                }
            }

            return AttoNode(
                network = byteBuffer.getNetwork(0),
                protocolVersion = byteBuffer.getUShort(),
                publicKey = byteBuffer.getPublicKey(),
                socketAddress = byteBuffer.getInetSocketAddress(),
                features = features
            )
        }
    }

    @JsonIgnore
    fun isVoter(): Boolean {
        return features.contains(NodeFeature.VOTING)
    }

    @JsonIgnore
    fun isNotVoter(): Boolean {
        return !isVoter()
    }

    @JsonIgnore
    fun isHistorical(): Boolean {
        return features.contains(NodeFeature.HISTORICAL)
    }

    fun toByteBuffer(): AttoByteBuffer {
        val byteBuffer = AttoByteBuffer(size)

        byteBuffer
            .add(network) // 3 [0..<3]
            .add(protocolVersion) // 2 [3..<5]
            .add(publicKey) // 32 [5..<37]
            .add(socketAddress) // 16 ip + 2 port [37..<55]
            .add(features.size.toByte()) // 1 [55..<56]

        features.asSequence()
            .map { it.code }
            .sorted()
            .forEach { byteBuffer.add(it) }

        return byteBuffer
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
        private val map = entries.associateBy(NodeFeature::code)
        fun from(code: UByte): NodeFeature {
            return NodeFeature.map.getOrDefault(code, UNKNOWN)
        }
    }
}