package atto.node.network

import atto.node.Event
import java.net.InetAddress

data class NodeBanned(val address: InetAddress) : Event