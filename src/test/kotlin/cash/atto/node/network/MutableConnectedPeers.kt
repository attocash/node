package cash.atto.node.network

import cash.atto.protocol.AttoNode
import java.net.InetSocketAddress
import java.net.URI

internal class MutableConnectedPeers : ConnectedPeers {
    private val peers = LinkedHashMap<URI, ConnectedPeer>()

    override fun snapshot(): List<ConnectedPeer> = peers.values.toList()

    override fun isConnected(publicUri: URI): Boolean = publicUri in peers

    fun connect(
        node: AttoNode,
        socketAddress: InetSocketAddress = InetSocketAddress(node.publicUri.host, node.publicUri.port),
    ) {
        connect(ConnectedPeer(node, socketAddress))
    }

    fun connect(peer: ConnectedPeer) {
        peers[peer.node.publicUri] = peer
    }

    fun disconnect(publicUri: URI) {
        peers.remove(publicUri)
    }

    fun clear() {
        peers.clear()
    }
}
