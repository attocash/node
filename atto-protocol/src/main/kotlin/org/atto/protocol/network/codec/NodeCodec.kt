package org.atto.protocol.network.codec

import org.atto.commons.AttoNetwork
import org.atto.commons.AttoPublicKey
import org.atto.commons.toUShort
import org.atto.protocol.Node
import org.atto.protocol.NodeFeature
import org.atto.protocol.toByteArray
import org.atto.protocol.toInetSocketAddress
import java.nio.ByteBuffer

class NodeCodec : Codec<Node> {

    override fun fromByteArray(byteArray: ByteArray): Node? {
        if (byteArray.size < Node.size) {
            return null
        }

        val featuresSize = Math.min(byteArray[55].toInt(), Node.maxFeaturesSize)
        val features = HashSet<NodeFeature>(featuresSize)
        for (i in 0 until featuresSize) {
            val feature = NodeFeature.from(byteArray[56 + i].toUByte())
            if (feature != NodeFeature.UNKNOWN) {
                features.add(feature)
            }
        }

        val protocolVersion = byteArray.sliceArray(3 until 5).toUShort()

        return Node(
            network = AttoNetwork.fromCode(byteArray.sliceArray(0 until 3).toString(Charsets.UTF_8)),
            protocolVersion = protocolVersion,
            minimalProtocolVersion = protocolVersion,
            publicKey = AttoPublicKey(byteArray.sliceArray(5 until 37)),
            socketAddress = byteArray.sliceArray(37 until 55).toInetSocketAddress(),
            features = features
        )
    }

    override fun toByteArray(t: Node): ByteArray {
        val byteBuffer = ByteBuffer.allocate(Node.size)
            .put(t.network.environment.toByteArray(Charsets.UTF_8))
            .putShort(t.protocolVersion.toShort())
            .put(t.publicKey.value)
            .put(t.socketAddress.toByteArray())
            .put(t.features.size.toByte())

        for (feature in t.features) {
            byteBuffer.put(feature.code.toByte())
        }

        return byteBuffer.array()
    }

}