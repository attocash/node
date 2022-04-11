package org.atto.protocol.network.codec

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.Node
import org.atto.protocol.NodeFeature
import kotlin.math.min

class NodeCodec : Codec<Node> {

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): Node? {
        if (byteBuffer.size < Node.size) {
            return null
        }

        val featuresSize = min(byteBuffer.getByte(55).toInt(), Node.maxFeaturesSize)
        val features = HashSet<NodeFeature>(featuresSize)
        for (i in 0 until featuresSize) {
            val feature = NodeFeature.from(byteBuffer.getUByte(56 + i))
            if (feature != NodeFeature.UNKNOWN) {
                features.add(feature)
            }
        }

        val protocolVersion = byteBuffer.getUShort(3)

        return Node(
            network = byteBuffer.getNetwork(0),
            protocolVersion = protocolVersion,
            minimalProtocolVersion = protocolVersion,
            publicKey = byteBuffer.getPublicKey(5),
            socketAddress = byteBuffer.getInetSocketAddress(37),
            features = features
        )
    }

    override fun toByteBuffer(t: Node): AttoByteBuffer {
        val byteBuffer = AttoByteBuffer(Node.size)

        byteBuffer
            .add(t.network)
            .add(t.protocolVersion)
            .add(t.publicKey)
            .add(t.socketAddress)
            .add(t.features.size.toByte())

        for (feature in t.features) {
            byteBuffer.add(feature.code)
        }

        return byteBuffer
    }

}