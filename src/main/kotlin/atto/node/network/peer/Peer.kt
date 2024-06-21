package atto.node.network.peer

import atto.node.Event
import atto.protocol.AttoNode
import java.net.InetSocketAddress

data class Peer(
    val connectionSocketAddress: InetSocketAddress,
    val node: AttoNode,
) {
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

    override fun toString(): String = "Peer(connectionSocketAddress=$connectionSocketAddress, node=$node)"
}

data class PeerConnected(
    val peer: Peer,
) : Event

data class PeerAuthorized(
    val peer: Peer,
) : Event

data class PeerRemoved(
    val peer: Peer,
) : Event

data class PeerRejected(
    val reason: PeerRejectionReason,
    val peer: Peer,
) : Event

enum class PeerRejectionReason {
    INVALID_HANDSHAKE_ANSWER,
}
