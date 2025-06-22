package cash.atto.node.network

import cash.atto.node.Event
import cash.atto.protocol.AttoNode
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant

data class NodeBanned(
    val address: InetAddress,
    override val timestamp: Instant = Instant.now(),
) : Event

data class NodeConnected(
    val connectionSocketAddress: InetSocketAddress,
    val node: AttoNode,
    override val timestamp: Instant = Instant.now(),
) : Event

data class NodeDisconnected(
    val connectionSocketAddress: InetSocketAddress,
    val node: AttoNode,
    override val timestamp: Instant = Instant.now(),
) : Event
