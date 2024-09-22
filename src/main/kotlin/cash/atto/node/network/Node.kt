package cash.atto.node.network

import cash.atto.node.Event
import cash.atto.protocol.AttoNode
import java.net.InetAddress
import java.net.InetSocketAddress

data class NodeBanned(
    val address: InetAddress,
) : Event

data class NodeConnected(
    val connectionSocketAddress: InetSocketAddress,
    val node: AttoNode,
) : Event

data class NodeDisconnected(
    val connectionSocketAddress: InetSocketAddress,
    val node: AttoNode,
) : Event
