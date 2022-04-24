package org.atto.node.network.peer

import org.atto.node.Event
import org.atto.protocol.AttoNode
import java.net.InetSocketAddress

data class Peer(val connectionSocketAddress: InetSocketAddress, val node: AttoNode) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Peer

        if (connectionSocketAddress != other.connectionSocketAddress) return false
        if (node != other.node) return false

        return true
    }

    override fun hashCode(): Int {
        var result = connectionSocketAddress.hashCode()
        result = 31 * result + node.hashCode()
        return result
    }

    override fun toString(): String {
        return "Peer(connectionSocketAddress=$connectionSocketAddress, node=$node)"
    }


}

interface PeerEvent : Event<Peer>

data class PeerAddedEvent(override val payload: Peer) : PeerEvent

data class PeerRemovedEvent(override val payload: Peer) : PeerEvent
