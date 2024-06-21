package atto.node.network

import atto.node.Event
import java.net.InetAddress
import java.net.URI

data class NodeBanned(
    val address: InetAddress,
) : Event

data class NodeDisconnected(
    val publicUri: URI,
) : Event
