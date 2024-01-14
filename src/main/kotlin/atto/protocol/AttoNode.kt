package atto.protocol

import atto.protocol.serializer.InetSocketAddressSerializer
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoByteBuffer
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import com.fasterxml.jackson.annotation.JsonIgnore
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.protobuf.ProtoNumber
import java.net.InetSocketAddress
import kotlin.math.min

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AttoNode(
    @ProtoNumber(0) val network: AttoNetwork,
    @ProtoNumber(1) val protocolVersion: UShort,
    @ProtoNumber(2) val algorithm: AttoAlgorithm,
    @ProtoNumber(3) val publicKey: AttoPublicKey,
    @ProtoNumber(4) @Serializable(with = InetSocketAddressSerializer::class)
    val socketAddress: InetSocketAddress,
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

    companion object {
        val maxFeaturesSize = 10
        val size = 57

        fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoNode? {
            if (byteBuffer.size < size) return null

            return AttoNode(
                network = byteBuffer.getNetwork(),
                protocolVersion = byteBuffer.getUShort(),
                algorithm = byteBuffer.getAlgorithm(),
                publicKey = byteBuffer.getPublicKey(),
                socketAddress = byteBuffer.getInetSocketAddress(),
                features = byteBuffer.getFeatures()
            )
        }

        private fun AttoByteBuffer.getFeatures(): Set<NodeFeature> {
            val featuresSize = min(this.getByte(56).toInt(), maxFeaturesSize)
            val features = HashSet<NodeFeature>(featuresSize)
            for (i in 0 until featuresSize) {
                val feature = NodeFeature.from(this.getUByte(57 + i))
                if (feature != NodeFeature.UNKNOWN) {
                    features.add(feature)
                }
            }
            return features.toSet()
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
        val byteBuffer = AttoByteBuffer(size + features.size)

        byteBuffer
            .add(network)
            .add(protocolVersion)
            .add(algorithm)
            .add(publicKey)
            .add(socketAddress)
            .add(features.size.toByte())

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
        if (algorithm != other.algorithm) return false
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