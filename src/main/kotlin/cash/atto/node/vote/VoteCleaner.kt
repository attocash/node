package cash.atto.node.vote

import cash.atto.commons.AttoHash
import cash.atto.node.CacheSupport
import cash.atto.node.DemandDrivenWorker
import cash.atto.node.account.AccountUpdated
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.runBlocking
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

@Component
class VoteCleaner(
    private val voteRepository: VoteRepository,
    private val clock: Clock,
) : CacheSupport {
    companion object {
        private const val BATCH_SIZE = 1_000
        private val STARTUP_CLEANUP_GRACE = Duration.ofMinutes(5)
    }

    private val buffer = ConcurrentLinkedDeque<AttoHash>()
    private val bufferDepth = AtomicInteger()
    private val worker = DemandDrivenWorker("vote-cleaner", drain = ::drain)

    @EventListener
    fun process(event: AccountUpdated) {
        val staleBlockHash = event.previousAccount.lastTransactionHash
        if (staleBlockHash == event.updatedAccount.lastTransactionHash) {
            return
        }

        buffer.addLast(staleBlockHash)
        bufferDepth.incrementAndGet()
        worker.request()
    }

    @PostConstruct
    fun deleteStaleVotesOnStartup() {
        runBlocking {
            voteRepository.deleteStale(clock.instant().minus(STARTUP_CLEANUP_GRACE))
        }
    }

    @PreDestroy
    fun stop() {
        worker.cancel()
    }

    fun getBufferSize(): Int = bufferDepth.get()

    private suspend fun drain() {
        while (bufferDepth.get() > 0) {
            flushBatch(BATCH_SIZE)
        }
    }

    override fun clear() {
        buffer.clear()
        bufferDepth.set(0)
    }

    private suspend fun flushBatch(size: Int): Int {
        val blockHashes = drainBatch(size)
        if (blockHashes.isEmpty()) {
            return 0
        }

        try {
            voteRepository.deleteByBlockHashes(blockHashes.distinct())
            return blockHashes.size
        } catch (e: Exception) {
            requeue(blockHashes)
            throw e
        }
    }

    private fun drainBatch(size: Int): List<AttoHash> {
        val blockHashes = mutableListOf<AttoHash>()

        for (i in 1..size) {
            val blockHash = buffer.pollFirst() ?: break
            blockHashes += blockHash
        }

        bufferDepth.addAndGet(-blockHashes.size)
        return blockHashes
    }

    private fun requeue(blockHashes: List<AttoHash>) {
        blockHashes.asReversed().forEach(buffer::addFirst)
        bufferDepth.addAndGet(blockHashes.size)
    }
}
