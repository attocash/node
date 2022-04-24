package org.atto.protocol.network.peer

import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.AttoMessageType
import java.net.InetSocketAddress

data class AttoKeepAlive(val neighbours: List<InetSocketAddress>) :
    AttoMessage {
    init {
        require(neighbours.size <= 8) { "Just 8 nodes are supported" }
    }

    companion object {
        const val size = 144
    }

    override fun messageType(): AttoMessageType {
        return AttoMessageType.KEEP_ALIVE
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AttoKeepAlive

        if (neighbours != other.neighbours) return false

        return true
    }

    override fun hashCode(): Int {
        return neighbours.hashCode()
    }
}