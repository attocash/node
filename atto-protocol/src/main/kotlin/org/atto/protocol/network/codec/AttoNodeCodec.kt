package org.atto.protocol.network.codec

import org.atto.commons.AttoByteBuffer
import org.atto.protocol.AttoNode
import org.atto.protocol.NodeFeature
import kotlin.math.min

class AttoNodeCodec : AttoCodec<AttoNode> {

    override fun fromByteBuffer(byteBuffer: AttoByteBuffer): AttoNode? {
        if (byteBuffer.size < AttoNode.size) {
            return null
        }

        val featuresSize = min(byteBuffer.getByte(55).toInt(), AttoNode.maxFeaturesSize)
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

    override fun toByteBuffer(t: AttoNode): AttoByteBuffer {
        val byteBuffer = AttoByteBuffer(AttoNode.size)

        byteBuffer
            .add(t.network) // 3 [0..<3]
            .add(t.protocolVersion) // 2 [3..<5]
            .add(t.publicKey) // 32 [5..<37]
            .add(t.socketAddress) // 16 ip + 2 port [37..<55]
            .add(t.features.size.toByte()) // 1 [55..<56]

        t.features.asSequence()
            .map { it.code }
            .sorted()
            .forEach { byteBuffer.add(it) }

        return byteBuffer
    }

}