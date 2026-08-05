package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.node.CacheSupport
import cash.atto.node.bootstrap.unchecked.GapView
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionRepository
import cash.atto.node.bootstrap.unchecked.UncheckedWorkTracker
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.network.NodeConnected
import cash.atto.node.network.NodeDisconnected
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionStreamRequest
import cash.atto.protocol.AttoTransactionStreamResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Component
class GapDiscoverer(
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val networkMessagePublisher: NetworkMessagePublisher,
    private val discoveryQueue: DiscoveryQueue,
    private val workTracker: UncheckedWorkTracker,
    private val discoveryProperties: DiscoveryProperties,
    meterRegistry: MeterRegistry,
    private val clock: Clock,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}
    private val resolveMutex = Mutex()
    private val peers = ConcurrentHashMap<URI, AttoNode>()
    private val activeSessions = ConcurrentHashMap<AttoPublicKey, GapSession>()
    private val requestBudget =
        minOf(
            AttoTransactionStreamRequest.MAX_TRANSACTIONS,
            discoveryProperties.capacity.toULong(),
        ).toInt()

    private val gapQueryTimer =
        Timer
            .builder("transactions.unchecked.gap.query")
            .description("Time finding missing unchecked transaction ranges")
            .register(meterRegistry)
    private val gapRows =
        Counter
            .builder("transactions.unchecked.gap.rows")
            .description("Missing unchecked transaction ranges returned")
            .register(meterRegistry)

    @Volatile
    private var idleGeneration = Long.MIN_VALUE

    @Volatile
    private var nextMaintenanceAt = Instant.EPOCH

    @EventListener
    fun add(nodeEvent: NodeConnected) {
        val node = nodeEvent.node
        if (!node.isHistorical()) {
            return
        }

        peers[node.publicUri] = node
        resetIdleSuppression()
    }

    @EventListener
    fun remove(nodeEvent: NodeDisconnected) {
        val publicUri = nodeEvent.node.publicUri
        peers.remove(publicUri)
        activeSessions.values
            .filter { it.peer == publicUri }
            .forEach { activeSessions.remove(it.publicKey, it) }
    }

    // A retry may wait for queue space, so this must not occupy Spring's shared fixed-delay scheduler.
    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.SECONDS)
    suspend fun maintainSessions() {
        if (!resolveMutex.tryLock()) {
            return
        }

        try {
            retryActiveSessions()
        } finally {
            resolveMutex.unlock()
        }
    }

    suspend fun discoverIfDue(): GapDiscoveryResult {
        if (!resolveMutex.tryLock()) {
            return GapDiscoveryResult.Busy
        }

        try {
            val slots = availableSessionSlots()
            if (slots == 0 || shouldSkipIdleScan()) {
                return GapDiscoveryResult.Idle
            }

            val startingGeneration = workTracker.currentGeneration()
            val queryStartedAt = System.nanoTime()
            val gaps =
                try {
                    uncheckedTransactionRepository
                        .findGaps(slots + activeSessions.size)
                        .toList()
                } finally {
                    gapQueryTimer.record(System.nanoTime() - queryStartedAt, TimeUnit.NANOSECONDS)
                }

            if (gaps.isNotEmpty()) {
                gapRows.increment(gaps.size.toDouble())
                gaps
                    .asSequence()
                    .filterNot { activeSessions.containsKey(it.publicKey) }
                    .take(slots)
                    .forEach { startGapRequest(it) }
            }

            updateIdleSuppression(gaps.isNotEmpty(), startingGeneration)
            return GapDiscoveryResult.Queried
        } finally {
            resolveMutex.unlock()
        }
    }

    @EventListener
    suspend fun process(message: InboundNetworkMessage<AttoTransactionStreamResponse>) {
        val publicKey = message.payload.transaction.block.publicKey
        val session = activeSessions[publicKey] ?: return
        closeIfFinished(session, session.offer(message))
    }

    override fun clear() {
        activeSessions.clear()
        resetIdleSuppression()
    }

    internal fun activeSessionCount(): Int = activeSessions.size

    private suspend fun retryActiveSessions() {
        activeSessions.values.toList().forEach { session ->
            if (session.isExpired(clock.instant(), SESSION_TIMEOUT)) {
                activeSessions.remove(session.publicKey, session)
            } else {
                closeIfFinished(session, session.retry())
            }
        }
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

        val sessionsByPeer = activeSessions.values.groupingBy(GapSession::peer).eachCount()
        val peerSlots =
            peers.values.sumOf { peer ->
                maxOf(0, peer.parallelStreamLimit() - (sessionsByPeer[peer.publicUri] ?: 0))
            }
        return minOf(queueSlots, peerSlots)
    }

    private fun startGapRequest(view: GapView) {
        val selectedPeer = selectPeer() ?: return
        val startHeight = view.startHeight(requestBudget.toULong())
        val session =
            GapSession(
                publicKey = view.publicKey,
                peer = selectedPeer,
                startHeight = startHeight,
                endHeight = view.endHeight,
                initialExpectedHash = view.expectedEndHash,
                discoveryQueue = discoveryQueue,
                clock = clock,
            )
        if (activeSessions.putIfAbsent(view.publicKey, session) != null) {
            return
        }
        if (!peers.containsKey(selectedPeer)) {
            activeSessions.remove(view.publicKey, session)
            return
        }

        val message =
            DirectNetworkMessage(
                selectedPeer,
                AttoTransactionStreamRequest(view.publicKey, startHeight, view.endHeight),
                expectedResponseCount = view.endHeight.value - startHeight.value + 1UL,
            )
        try {
            networkMessagePublisher.publish(message)
            logger.trace {
                "Starting gap discovery for account ${view.publicKey}. " +
                    "Requesting transactions from $startHeight to ${view.endHeight}"
            }
        } catch (e: Exception) {
            activeSessions.remove(view.publicKey, session)
            throw e
        }
    }

    private fun selectPeer(): URI? {
        val sessionsByPeer = activeSessions.values.groupingBy(GapSession::peer).eachCount()
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

    private fun closeIfFinished(
        session: GapSession,
        status: GapSessionStatus,
    ) {
        if (status != GapSessionStatus.ACTIVE) {
            activeSessions.remove(session.publicKey, session)
        }
    }

    private fun updateIdleSuppression(
        foundGaps: Boolean,
        startingGeneration: Long,
    ) {
        val endingGeneration = workTracker.currentGeneration()
        if (!foundGaps && startingGeneration == endingGeneration) {
            idleGeneration = endingGeneration
            nextMaintenanceAt = clock.instant().plusSeconds(discoveryProperties.idleQueryFallbackInSeconds)
        } else {
            resetIdleSuppression()
        }
    }

    private fun resetIdleSuppression() {
        idleGeneration = Long.MIN_VALUE
        nextMaintenanceAt = Instant.EPOCH
    }

    private fun shouldSkipIdleScan(): Boolean =
        workTracker.currentGeneration() == idleGeneration &&
            clock.instant().isBefore(nextMaintenanceAt)

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

sealed interface GapDiscoveryResult {
    data object Idle : GapDiscoveryResult

    data object Busy : GapDiscoveryResult

    data object Queried : GapDiscoveryResult
}

private fun GapView.startHeight(requestBudget: ULong): AttoHeight {
    val count = endHeight - startHeight + 1U
    if (count.value > requestBudget) {
        return endHeight - requestBudget + 1U
    }
    return startHeight
}
