package atto.node.bootstrap.discovery

import atto.node.EventPublisher
import atto.node.bootstrap.TransactionDiscovered
import atto.node.bootstrap.unchecked.GapView
import atto.node.network.DirectNetworkMessage
import atto.node.network.InboundNetworkMessage
import atto.node.network.NetworkMessagePublisher
import atto.node.network.peer.PeerAdded
import atto.node.network.peer.PeerRemoved
import atto.node.transaction.toTransaction
import atto.protocol.transaction.AttoTransactionStreamRequest
import atto.protocol.transaction.AttoTransactionStreamResponse
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.PreviousSupport
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.reactive.asFlow
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Component
class GapDiscoverer(
    private val databaseClient: DatabaseClient,
    private val networkMessagePublisher: NetworkMessagePublisher,
    private val eventPublisher: EventPublisher
) {
    private val logger = KotlinLogging.logger {}

    private val peers = ConcurrentHashMap.newKeySet<URI>()

    private val pointerMap = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(1))
        .build<AttoPublicKey, TransactionPointer>()
        .asMap()


    @EventListener
    fun add(peerEvent: PeerAdded) {
        val peer = peerEvent.peer
        if (!peer.node.isHistorical()) {
            return
        }
        peers.add(peer.node.publicUri)
    }

    @EventListener
    fun remove(peerEvent: PeerRemoved) {
        val peer = peerEvent.peer
        peers.remove(peer.node.publicUri)
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    suspend fun resolve() {
        val peers = peers.toList()

        if (peers.isEmpty()) {
            return
        }


        val gaps = databaseClient.sql(
            """
                                SELECT algorithm, public_key, account_height, transaction_height, previous_transaction_hash FROM (
                                        SELECT  ROW_NUMBER() OVER(PARTITION BY ut.public_key ORDER BY ut.height DESC) AS row_num,
                                                ut.algorithm algorithm,
                                                ut.public_key public_key,
                                                COALESCE(a.height, 0) account_height,
                                                ut.height transaction_height,
                                                ut.previous previous_transaction_hash
                                        FROM unchecked_transaction ut
                                        LEFT JOIN account a on ut.public_key = a.public_key and ut.height > a.height
                                        ORDER BY ut.public_key, ut.height ) ready
                                WHERE transaction_height > account_height + row_num
                                AND row_num = 1
                """
        ).map { row, _ ->
            GapView(
                AttoAlgorithm.valueOf(row.get("algorithm", String::class.javaObjectType)!!),
                AttoPublicKey(row.get("public_key", ByteArray::class.java)!!),
                row.get("account_height", Long::class.javaObjectType)!!.toULong(),
                row.get("transaction_height", Long::class.javaObjectType)!!.toULong(),
                AttoHash(row.get("previous_transaction_hash", ByteArray::class.java)!!)
            )
        }.all().asFlow() // https://github.com/spring-projects/spring-data-relational/issues/1394

        gaps.filter { pointerMap[it.publicKey] == null }
            .onEach {
                pointerMap[it.publicKey] = TransactionPointer(
                    it.startHeight(),
                    it.endHeight(),
                    it.previousTransactionHash
                )
            }
            .map { AttoTransactionStreamRequest(it.publicKey, it.startHeight(), it.endHeight()) }
            .map { DirectNetworkMessage(peers[Random.nextInt(peers.size)], it) }
            .collect { networkMessagePublisher.publish(it) }
    }

    @EventListener
    fun process(message: InboundNetworkMessage<AttoTransactionStreamResponse>) {
        val response = message.payload
        val transaction = response.transaction

        val block = transaction.block
        val pointer = pointerMap[block.publicKey]
        if (transaction.hash != pointer?.currentHash) {
            return
        }
        if (pointer.initialHeight == block.height) {
            pointerMap.remove(block.publicKey)
        } else if (block is PreviousSupport) {
            pointerMap[block.publicKey] = TransactionPointer(
                pointer.initialHeight,
                pointer.currentHeight - 1UL,
                block.previous
            )
        }
        logger.debug { "Discovered gap transaction ${transaction.hash}" }
        eventPublisher.publish(TransactionDiscovered(null, transaction.toTransaction(), listOf()))
    }

}

private data class TransactionPointer(
    val initialHeight: ULong,
    val currentHeight: ULong,
    val currentHash: AttoHash,
    val timestamp: Instant = Instant.now()
)

private fun GapView.startHeight(): ULong {
    val maxCount = AttoTransactionStreamRequest.MAX_TRANSACTIONS
    val count = this.transactionHeight - this.accountHeight
    if (count > maxCount) {
        return this.transactionHeight - maxCount
    }
    return this.accountHeight + 1U
}

private fun GapView.endHeight(): ULong {
    return this.transactionHeight
}