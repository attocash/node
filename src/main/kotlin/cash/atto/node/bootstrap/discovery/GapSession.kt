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
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

internal class GapSession(
    val publicKey: AttoPublicKey,
    val peer: URI,
    val startHeight: AttoHeight,
    val endHeight: AttoHeight,
    val initialExpectedHash: AttoHash,
    private val discoveryQueue: DiscoveryQueue,
) {
    private val drainMutex = Mutex()
    private val responses = ConcurrentHashMap<AttoHeight, InboundNetworkMessage<AttoTransactionStreamResponse>>()
    private val progress = AtomicReference<Progress>(Progress.Active(endHeight, initialExpectedHash))

    suspend fun offer(message: InboundNetworkMessage<AttoTransactionStreamResponse>): Boolean {
        val block = message.payload.transaction.block
        val current = progress.get()
        if (
            current !is Progress.Active ||
            message.publicUri != peer ||
            block.height !in startHeight..endHeight ||
            block.height > current.expectedHeight
        ) {
            return false
        }

        val newlyStored = responses.putIfAbsent(block.height, message) == null
        val latest = progress.get()
        if (
            newlyStored &&
            (latest !is Progress.Active || block.height > latest.expectedHeight)
        ) {
            responses.remove(block.height, message)
            return false
        }

        return drain(newlyStored)
    }

    internal fun bufferedResponseCount(): Int = responses.size

    fun isComplete(): Boolean = progress.get() !is Progress.Active

    private suspend fun drain(newlyStored: Boolean): Boolean {
        return drainMutex.withLock {
            var accepted = newlyStored
            var current = progress.get()
            while (current is Progress.Active) {
                val message = responses[current.expectedHeight] ?: return@withLock accepted
                val transaction = message.payload.transaction
                if (transaction.hash != current.expectedHash) {
                    progress.set(Progress.Invalid)
                    responses.clear()
                    return@withLock false
                }

                discoveryQueue.queue(
                    TransactionDiscovered(null, transaction.toTransaction(), emptyList()),
                    DiscoverySource.GAP,
                )

                accepted = true
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
                responses.remove(current.expectedHeight, message)
                if (next == Progress.Completed) {
                    responses.clear()
                }
                current = next
            }
            accepted
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
