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
import cash.atto.protocol.AttoNode
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
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

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
    private val peers = ConcurrentHashMap<URI, AttoNode>()
    private val activeSessionCache =
        Caffeine
            .newBuilder()
            .ticker { TimeUnit.MILLISECONDS.toNanos(clock.millis()) }
            .scheduler(Scheduler.systemScheduler())
            .expireAfterWrite(SESSION_TIMEOUT)
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

        peers[node.publicUri] = node
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
        val slots = availableSessionSlots()
        if (slots == 0) {
            return 0
        }

        val gaps =
            uncheckedTransactionRepository
                .findGaps(slots + activeSessions.size)
                .toList()

        if (gaps.isEmpty()) {
            return 0
        }

        gapRows.increment(gaps.size.toDouble())
        return gaps
            .asSequence()
            .filterNot { activeSessions.containsKey(it.publicKey) }
            .take(slots)
            .sumOf { startGapRequest(it) }
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

    private fun availableSessionSlots(): Int {
        if (peers.isEmpty()) {
            return 0
        }

        val queueSlots =
            maxOf(
                0,
                discoveryQueue.remainingTargetCapacity() / requestBudget - activeSessions.size,
            )
        if (queueSlots == 0) {
            return 0
        }

        val sessionsByPeer =
            activeSessions
                .values
                .groupingBy(GapSession::peer)
                .eachCount()
        val peerSlots =
            peers.values.sumOf { peer ->
                maxOf(0, peer.parallelStreamLimit() - (sessionsByPeer[peer.publicUri] ?: 0))
            }
        return minOf(queueSlots, peerSlots)
    }

    private fun startGapRequest(view: GapView): Int {
        val selectedPeer = selectPeer() ?: return 0
        val startHeight = view.startHeight(requestBudget.toULong())
        val session =
            GapSession(
                publicKey = view.publicKey,
                peer = selectedPeer,
                startHeight = startHeight,
                endHeight = view.endHeight,
                initialExpectedHash = view.expectedEndHash,
                discoveryQueue = discoveryQueue,
            )
        activeSessions[view.publicKey] = session
        if (!peers.containsKey(selectedPeer)) {
            activeSessions.remove(view.publicKey, session)
            return 0
        }

        val requestedTransactions = view.endHeight.value - startHeight.value + 1UL
        val message =
            DirectNetworkMessage(
                selectedPeer,
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

    private fun selectPeer(): URI? {
        val sessionsByPeer =
            activeSessions
                .values
                .groupingBy(GapSession::peer)
                .eachCount()
        val candidates =
            peers.values
                .mapNotNull { peer ->
                    val count = sessionsByPeer[peer.publicUri] ?: 0
                    if (count < peer.parallelStreamLimit()) peer.publicUri to count else null
                }
        val minimumCount = candidates.minOfOrNull { it.second } ?: return null
        val leastLoaded = candidates.filter { it.second == minimumCount }
        return leastLoaded[Random.nextInt(leastLoaded.size)].first
    }

    private fun AttoNode.parallelStreamLimit(): Int =
        if (supportsParallelTransactionStreams()) {
            AttoTransactionStreamRequest.MAX_PARALLEL_STREAMS
        } else {
            1
        }

    private companion object {
        val SESSION_TIMEOUT: Duration = Duration.ofMinutes(1)
    }
}

private fun GapView.startHeight(requestBudget: ULong): AttoHeight {
    val count = endHeight - startHeight + 1U
    if (count.value > requestBudget) {
        return endHeight - requestBudget + 1U
    }
    return startHeight
}
