package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.node.CacheSupport
import cash.atto.node.bootstrap.unchecked.GapView
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionRepository
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.network.NodeConnected
import cash.atto.node.network.NodeDisconnected
import cash.atto.protocol.AttoTransactionStreamRequest
import cash.atto.protocol.AttoTransactionStreamResponse
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.flow.toList
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Component
class GapDiscoverer(
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val networkMessagePublisher: NetworkMessagePublisher,
    private val discoveryQueue: DiscoveryQueue,
    private val discoveryProperties: DiscoveryProperties,
    meterRegistry: MeterRegistry,
    private val clock: Clock,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}
    private val peers = ConcurrentHashMap.newKeySet<URI>()
    private val activeSessionCache =
        Caffeine
            .newBuilder()
            .ticker { TimeUnit.MILLISECONDS.toNanos(clock.millis()) }
            .scheduler(Scheduler.systemScheduler())
            .expireAfterWrite(AttoTransactionStreamRequest.TIMEOUT)
            .build<AttoPublicKey, GapSession>()
    private val activeSessions = activeSessionCache.asMap()
    private val requestBudget =
        minOf(
            AttoTransactionStreamRequest.MAX_TRANSACTIONS,
            discoveryProperties.capacity.toULong(),
        ).toInt()

    private val gapRows =
        Counter
            .builder("transactions.unchecked.gap.rows")
            .description("Missing unchecked transaction ranges returned")
            .register(meterRegistry)

    @EventListener
    fun add(nodeEvent: NodeConnected) {
        val node = nodeEvent.node
        if (!node.isHistorical()) {
            return
        }

        peers += node.publicUri
    }

    @EventListener
    fun remove(nodeEvent: NodeDisconnected) {
        val publicUri = nodeEvent.node.publicUri
        peers.remove(publicUri)
        activeSessions
            .values
            .filter { it.peer == publicUri }
            .forEach { activeSessions.remove(it.publicKey, it) }
    }

    suspend fun discover(): Int {
        activeSessionCache.cleanUp()
        val availablePeers = availablePeers()
        if (availablePeers.isEmpty()) {
            return 0
        }

        var remainingBudget = remainingRequestBudget()
        if (remainingBudget == 0) {
            return 0
        }

        val sessionLimit = minOf(availablePeers.size, remainingBudget)
        val gaps =
            uncheckedTransactionRepository
                .findGaps(sessionLimit + activeSessions.size)
                .toList()

        if (gaps.isEmpty()) {
            return 0
        }

        gapRows.increment(gaps.size.toDouble())
        var requested = 0
        val peers = availablePeers.iterator()
        for (gap in gaps) {
            if (remainingBudget == 0 || !peers.hasNext()) {
                break
            }
            if (activeSessions.containsKey(gap.publicKey)) {
                continue
            }

            val count = startGapRequest(gap, peers.next(), remainingBudget)
            requested += count
            remainingBudget -= count
        }
        return requested
    }

    @EventListener
    suspend fun process(message: InboundNetworkMessage<AttoTransactionStreamResponse>) {
        val publicKey = message.payload.transaction.block.publicKey
        val session = activeSessions[publicKey] ?: return
        if (session.offer(message)) {
            activeSessions.replace(publicKey, session, session)
        }
        if (session.isComplete()) {
            activeSessions.remove(publicKey, session)
        }
    }

    override fun clear() {
        activeSessions.clear()
    }

    internal fun activeSessionCount(): Int {
        activeSessionCache.cleanUp()
        return activeSessions.size
    }

    private fun availablePeers(): List<URI> {
        val activePeers = activeSessions.values.mapTo(mutableSetOf(), GapSession::peer)
        return peers.filterNot(activePeers::contains)
    }

    private fun remainingRequestBudget(): Int {
        val reserved = activeSessions.values.sumOf(GapSession::remainingResponseCount)
        return maxOf(0, discoveryQueue.remainingTargetCapacity() - reserved)
    }

    private fun startGapRequest(
        view: GapView,
        peer: URI,
        remainingBudget: Int,
    ): Int {
        val transactionLimit = minOf(requestBudget, remainingBudget)
        val startHeight = view.startHeight(transactionLimit.toULong())
        val session =
            GapSession(
                publicKey = view.publicKey,
                peer = peer,
                startHeight = startHeight,
                endHeight = view.endHeight,
                initialExpectedHash = view.expectedEndHash,
                discoveryQueue = discoveryQueue,
            )
        activeSessions[view.publicKey] = session
        if (!peers.contains(peer)) {
            activeSessions.remove(view.publicKey, session)
            return 0
        }

        val requestedTransactions = view.endHeight.value - startHeight.value + 1UL
        val message =
            DirectNetworkMessage(
                peer,
                AttoTransactionStreamRequest(view.publicKey, startHeight, view.endHeight),
                expectedResponseCount = requestedTransactions,
            )
        try {
            networkMessagePublisher.publish(message)
            logger.trace {
                "Starting gap discovery for account ${view.publicKey}. " +
                    "Requesting transactions from $startHeight to ${view.endHeight}"
            }
            return requestedTransactions.toInt()
        } catch (e: Exception) {
            activeSessions.remove(view.publicKey, session)
            throw e
        }
    }
}

private fun GapView.startHeight(requestBudget: ULong): AttoHeight {
    val count = endHeight - startHeight + 1U
    if (count.value > requestBudget) {
        return endHeight - requestBudget + 1U
    }
    return startHeight
}
