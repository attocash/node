package org.atto.node.network

import org.atto.node.Event
import java.net.InetAddress

data class NodeBanned(val address: InetAddress) : Event