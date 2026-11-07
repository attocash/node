package cash.atto.node.network

import cash.atto.protocol.AttoNode
import java.net.InetSocketAddress
import java.net.URI

interface ConnectedPeers {
    // Detached metadata; a peer may disconnect immediately after this query.
    fun snapshot(): List<ConnectedPeer>

    fun isConnected(publicUri: URI): Boolean
}

data class ConnectedPeer(
    val node: AttoNode,
    val socketAddress: InetSocketAddress,
)
