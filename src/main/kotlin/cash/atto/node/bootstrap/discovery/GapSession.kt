package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.PreviousSupport
import cash.atto.node.bootstrap.TransactionDiscovered
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.transaction.toTransaction
import cash.atto.protocol.AttoTransactionStreamResponse
import kotlinx.coroutines.sync.Mutex
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal class GapSession(
    val publicKey: AttoPublicKey,
    val peer: URI,
    val startHeight: AttoHeight,
    val endHeight: AttoHeight,
    val initialExpectedHash: AttoHash,
    private val discoveryQueue: DiscoveryQueue,
    private val clock: Clock,
) {
    private val drainMutex = Mutex()
    private val responses = ConcurrentHashMap<AttoHeight, InboundNetworkMessage<AttoTransactionStreamResponse>>()
    private val progress = AtomicReference<Progress>(Progress.Active(endHeight, initialExpectedHash))

    @Volatile
    private var lastSuccessfulProgressAt: Instant = clock.instant()

    suspend fun offer(message: InboundNetworkMessage<AttoTransactionStreamResponse>): GapSessionStatus {
        val block = message.payload.transaction.block
        val current = progress.get()
        if (
            current !is Progress.Active ||
            message.publicUri != peer ||
            block.publicKey != publicKey ||
            block.height !in startHeight..endHeight ||
            block.height > current.expectedHeight
        ) {
            return status()
        }

        val previous = responses.putIfAbsent(block.height, message)
        val latest = progress.get()
        if (
            previous == null &&
            (latest !is Progress.Active || block.height > latest.expectedHeight)
        ) {
            responses.remove(block.height, message)
            return status()
        }

        return drain()
    }

    suspend fun retry(): GapSessionStatus = drain()

    fun isExpired(
        now: Instant,
        timeout: Duration,
    ): Boolean =
        !drainMutex.isLocked &&
            progress.get() is Progress.Active &&
            !lastSuccessfulProgressAt.isAfter(now.minus(timeout))

    internal fun bufferedResponseCount(): Int = responses.size

    internal fun status(): GapSessionStatus =
        when (progress.get()) {
            is Progress.Active -> GapSessionStatus.ACTIVE
            Progress.Completed -> GapSessionStatus.COMPLETED
            Progress.Invalid -> GapSessionStatus.INVALID
        }

    private suspend fun drain(): GapSessionStatus {
        if (!drainMutex.tryLock()) {
            return status()
        }

        try {
            while (true) {
                val current = progress.get()
                if (current !is Progress.Active) {
                    return status()
                }

                val message = responses[current.expectedHeight] ?: return GapSessionStatus.ACTIVE
                val transaction = message.payload.transaction
                if (transaction.hash != current.expectedHash) {
                    progress.set(Progress.Invalid)
                    responses.clear()
                    return GapSessionStatus.INVALID
                }

                discoveryQueue.queue(
                    TransactionDiscovered(null, transaction.toTransaction(), emptyList()),
                    DiscoverySource.GAP,
                )

                val next =
                    if (current.expectedHeight == startHeight) {
                        Progress.Completed
                    } else {
                        Progress.Active(
                            current.expectedHeight - 1UL,
                            (transaction.block as PreviousSupport).previous,
                        )
                    }
                progress.set(next)
                lastSuccessfulProgressAt = clock.instant()
                responses.remove(current.expectedHeight, message)

                if (next == Progress.Completed) {
                    responses.clear()
                    return GapSessionStatus.COMPLETED
                }
            }
        } finally {
            drainMutex.unlock()
        }
    }

    private sealed interface Progress {
        data class Active(
            val expectedHeight: AttoHeight,
            val expectedHash: AttoHash,
        ) : Progress

        data object Completed : Progress

        data object Invalid : Progress
    }
}

internal enum class GapSessionStatus {
    ACTIVE,
    COMPLETED,
    INVALID,
}
