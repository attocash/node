package atto.node.network

import atto.node.Event
import java.net.InetAddress
import java.net.InetSocketAddress

data class NodeBanned(val address: InetAddress) : Event

data class NodeDisconnected(val connectionSocketAddress: InetSocketAddress) : Event