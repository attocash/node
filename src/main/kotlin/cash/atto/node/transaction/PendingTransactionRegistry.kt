package cash.atto.node.transaction

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoTransaction
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

internal class PendingTransactionRegistry(
    private val streams: ConcurrentMap<AttoHash, Stream> = ConcurrentHashMap(),
) {
    operator fun get(hash: AttoHash): Stream? = streams[hash]

    fun register(hash: AttoHash): Registration {
        var created = false
        var stream: Stream? = null

        streams.compute(hash) { _, existing ->
            val value =
                existing ?: Stream().also {
                    created = true
                }
            value.waiters++
            stream = value
            value
        }

        return Registration(stream!!, created)
    }

    fun remove(
        hash: AttoHash,
        stream: Stream,
    ) {
        streams.computeIfPresent(hash) { _, existing ->
            if (existing === stream) {
                existing.waiters--
                existing.takeIf { it.waiters > 0 }
            } else {
                existing
            }
        }
    }

    suspend fun emit(
        hash: AttoHash,
        result: Result,
    ) {
        streams.remove(hash)?.flow?.emit(result)
    }

    suspend fun emit(
        hash: AttoHash,
        stream: Stream,
        result: Result,
    ) {
        if (streams.remove(hash, stream)) {
            stream.flow.emit(result)
        }
    }

    class Stream {
        val flow = MutableSharedFlow<Result>(replay = 1)

        // Registration and removal update this count under the per-hash map computation.
        var waiters = 0
    }

    class Registration(
        val stream: Stream,
        val created: Boolean,
    )

    sealed interface Result {
        data class Confirmed(
            val transaction: AttoTransaction,
        ) : Result

        data class Failed(
            val failure: Throwable,
        ) : Result
    }
}
