package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoHash
import cash.atto.commons.ReceiveSupport
import cash.atto.node.CacheSupport
import cash.atto.node.DuplicateDetector
import cash.atto.node.bootstrap.TransactionDiscovered
import cash.atto.node.bootstrap.TransactionStuck
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionRepository
import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.BroadcastStrategy
import cash.atto.node.network.ConnectedPeers
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.transaction.toTransaction
import cash.atto.node.vote.Vote
import cash.atto.protocol.AttoTransactionRequest
import cash.atto.protocol.AttoTransactionResponse
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration
import kotlin.time.Duration.Companion.minutes

@Component
class SendDiscoverer(
    private val networkMessagePublisher: NetworkMessagePublisher,
    private val discoveryQueue: DiscoveryQueue,
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    meterRegistry: MeterRegistry,
    private val connectedPeers: ConnectedPeers,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}
    private val skippedForGap =
        Counter
            .builder("transactions.discovery.send.skipped")
            .description("Send responses skipped because a higher unchecked transaction lets gap discovery recover them")
            .register(meterRegistry)

    private val unknownHashCache =
        Caffeine
            .newBuilder()
            .scheduler(Scheduler.systemScheduler())
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(100_000)
            .build<AttoHash, AttoHash>()
            .asMap()

    private val duplicateDetector = DuplicateDetector<AttoHash>(1.minutes)

    @EventListener
    fun process(event: TransactionDiscovered) {
        process(event.reason, event.transaction, event.votes)
    }

    @EventListener
    fun process(event: TransactionStuck) {
        process(event.reason, event.transaction, listOf())
    }

    private fun process(
        reason: TransactionRejectionReason?,
        transaction: Transaction,
        votes: Collection<Vote>,
    ) {
        if (reason != TransactionRejectionReason.RECEIVABLE_NOT_FOUND) {
            return
        }

        if (discoveryQueue.isAtCapacity()) {
            return
        }

        val block = transaction.block as ReceiveSupport

        if (duplicateDetector.isDuplicate(transaction.hash)) {
            return
        }

        if (unknownHashCache.putIfAbsent(block.sendHash, block.sendHash) != null) {
            return
        }

        val request = AttoTransactionRequest(block.sendHash)

        val socketAddress = representativeUri(votes)
        val message =
            if (socketAddress != null) {
                DirectNetworkMessage(socketAddress, request)
            } else {
                BroadcastNetworkMessage(BroadcastStrategy.EVERYONE, setOf(), request)
            }

        networkMessagePublisher.publish(message)
    }

    @EventListener
    suspend fun process(message: InboundNetworkMessage<AttoTransactionResponse>) {
        val response = message.payload
        val transaction = response.transaction

        if (unknownHashCache.remove(transaction.hash) == null) {
            return
        }

        val block = transaction.block
        if (uncheckedTransactionRepository.hasHigherTransaction(block.publicKey, block.height) != 0L) {
            skippedForGap.increment()
            logger.debug {
                "Skipping missing send transaction ${transaction.hash} at height ${block.height} because account " +
                    "${block.publicKey} has a higher unchecked transaction"
            }
            return
        }

        logger.debug { "Discovered missing send transaction ${transaction.hash}" }
        discoveryQueue.queue(
            TransactionDiscovered(null, transaction.toTransaction(), listOf()),
            DiscoverySource.SEND,
        )
    }

    private fun representativeUri(votes: Collection<Vote>): URI? {
        val peers = connectedPeers.snapshot()
        return votes.firstNotNullOfOrNull { vote ->
            peers.firstOrNull { it.node.publicKey == vote.publicKey }?.node?.publicUri
        }
    }

    override fun clear() {
        unknownHashCache.clear()
    }
}
