//package org.atto.node.bootstrap
//
//import org.atto.node.network.peer.Peer
//import org.atto.node.network.peer.PeerAddedEvent
//import org.atto.node.network.peer.PeerRemovedEvent
//import org.springframework.context.event.EventListener
//import org.springframework.stereotype.Component
//import java.net.InetSocketAddress
//import java.util.concurrent.ConcurrentHashMap
//
//@Component
//class PeerHolder {
//    val peers = ConcurrentHashMap<InetSocketAddress, Peer>()
//
//    @EventListener
//    fun add(peerEvent: PeerAddedEvent) {
//        val peer = peerEvent.payload
//        if (peer.node.isHistorical()) {
//            peers[peer.connectionSocketAddress] = peer
//        }
//    }
//
//    @EventListener
//    fun remove(peerEvent: PeerRemovedEvent) {
//        val peer = peerEvent.payload
//        peers.remove(peer.connectionSocketAddress)
//    }
//
//
//    fun getAll(): List<Peer> {
//        return peers.values.toList()
//    }
//}