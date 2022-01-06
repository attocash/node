package org.atto.node.network.peer

import org.atto.node.Event
import org.atto.protocol.Node
import java.net.InetSocketAddress

data class Peer(val connectionSocketAddress: InetSocketAddress, val node: Node) {

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

abstract class PeerEvent(peer: Peer) : Event<Peer>(peer)

class PeerAddedEvent(peer: Peer) : PeerEvent(peer)

class PeerRemovedEvent(peer: Peer) : PeerEvent(peer)
