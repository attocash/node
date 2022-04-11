package org.atto.node.bootstrap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.atto.commons.AttoHash
import org.atto.node.EventPublisher
import org.atto.node.network.peer.Peer
import org.atto.node.network.peer.PeerAddedEvent
import org.atto.node.network.peer.PeerRemovedEvent
import org.atto.node.transaction.TransactionRejectionReasons
import org.atto.node.transaction.TransactionRepository
import org.atto.protocol.transaction.Transaction
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.net.InetSocketAddress
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@Service
class TransactionDiscoverer(
    private val scope: CoroutineScope,
    private val eventPublisher: EventPublisher,
    private val transactionRepository: TransactionRepository
) {
    private val supportedRejectionReasons = setOf(
        TransactionRejectionReasons.ACCOUNT_NOT_FOUND,
        TransactionRejectionReasons.PREVIOUS_NOT_FOUND,
        TransactionRejectionReasons.LINK_NOT_FOUND
    )

    private val jobMap = ConcurrentHashMap<InetSocketAddress, MutableSet<DiscoveryJob>>()

    private val peerHolder = PeerHolder()

    @EventListener
    fun add(peerEvent: PeerAddedEvent) {
        val peer = peerEvent.payload
        if (peer.node.isHistorical()) {
            peerHolder.add(peer)
        }
    }

    @EventListener
    fun remove(peerEvent: PeerRemovedEvent) {
        val peer = peerEvent.payload
        peerHolder.add(peer)
    }

    @EventListener
    fun process(event: TransactionVoted) {
        if (!supportedRejectionReasons.contains(event.rejectionReason)) {
            return
        }

        scope.launch {
            val transaction = event.transaction
            if (event.rejectionReason == TransactionRejectionReasons.LINK_NOT_FOUND) {
                findSingle(transaction.hash)
            } else if (event.rejectionReason == TransactionRejectionReasons.ACCOUNT_NOT_FOUND) {
                findMultiple(transaction, 0u, transaction.block.height)
            } else if (event.rejectionReason == TransactionRejectionReasons.PREVIOUS_NOT_FOUND) {
                transactionRepository.findLastConfirmedByPublicKeyId(transaction.block.publicKey)?.let {
                    findMultiple(transaction, it.block.height + 1u, transaction.block.height)
                }
            }
        }
    }

    private suspend fun findSingle(hash: AttoHash) {
        val peer = peerHolder.get()
        val range = SingeTransactionRage(hash)

        val job = DiscoveryJob(
            range,
            onReceive = {
                eventPublisher.publish(TransactionVoted(it, null))
            },
            onExpire = {
                findSingle(hash)
                // remove from the list
            }
        )
        jobMap.compute(peer.connectionSocketAddress) { k, v ->
            val jobs = v ?: HashSet()
            jobs.add(job)
            jobs
        }

        // TODO: Send Single
    }

    private suspend fun findMultiple(transaction: Transaction, startHeight: ULong, endHeight: ULong) {
        val peer = peerHolder.get()
        val range = MultipleTransactionRage(transaction, startHeight, endHeight)

        val job = DiscoveryJob(
            range,
            onReceive = {
                eventPublisher.publish(TransactionVoted(it, null))
            },
            onExpire = {
                findMultiple(transaction, startHeight, endHeight)
                // remove from the list
            }
        )
        jobMap.compute(peer.connectionSocketAddress) { k, v ->
            val jobs = v ?: HashSet()
            jobs.add(job)
            jobs
        }
    }


    class DiscoveryJob(
        private val monitor: TransactionMonitor,
        private val onReceive: (Transaction) -> Unit,
        private val onExpire: suspend () -> Unit
    ) {
        private val timeout = 10_000L
        private val started = System.currentTimeMillis()

        fun isCompleted(): Boolean {
            return !monitor.hasNext()
        }

        fun isExpired(): Boolean {
            val diff = System.currentTimeMillis() - started
            return timeout < diff
        }

        suspend fun checkExpired(): Boolean {
            if (isExpired()) {
                onExpire.invoke()
                return true
            }
            return false
        }

        fun received(transaction: Transaction) {
            if (transaction.hash == monitor.next()) {
                onReceive.invoke(transaction)
            }
            // TODO what to do else?
        }
    }


    interface TransactionMonitor {
        fun hasNext(): Boolean
        fun next(): AttoHash?
    }

    class SingeTransactionRage(@Volatile private var hash: AttoHash?) : TransactionMonitor {

        override fun hasNext(): Boolean {
            return hash != null
        }

        override fun next(): AttoHash? {
            try {
                return hash
            } finally {
                hash = null
            }
        }

    }

    class MultipleTransactionRage(
        transaction: Transaction,
        private val startHeight: ULong,
        private val endHeight: ULong
    ) : TransactionMonitor {
        private val publicKey = transaction.block.publicKey

        @Volatile
        private var previous = transaction.block.previous

        override fun hasNext(): Boolean {
            TODO("Not yet implemented")
        }

        override fun next(): AttoHash? {
            TODO("Not yet implemented")
        }
    }

    class PeerHolder {
        private val peers = ConcurrentHashMap<InetSocketAddress, Peer>()

        private var lastUpdate = LocalDateTime.now()
        private var peer: Peer? = null

        fun add(peer: Peer) {
            peers[peer.connectionSocketAddress] = peer
        }

        fun remove(peer: Peer) {
            peers.remove(peer.connectionSocketAddress)
        }

        suspend fun get(): Peer {
            while (peer == null || isExpired()) {
                peer = getRandomPeer()
                if (peer == null) {
                    delay(1_000)
                }
            }
            return peer!!
        }

        private fun isExpired(): Boolean {
            return LocalDateTime.now().isAfter(lastUpdate.plusSeconds(10))
        }

        private fun getRandomPeer(): Peer? {
            val peerList = peers.values.toList()
            if (peerList.isEmpty()) {
                return null
            }
            return peerList[Random.nextInt(peerList.size)]
        }
    }
}