package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoHash
import cash.atto.node.DuplicateDetector
import cash.atto.node.bootstrap.TransactionDiscovered
import cash.atto.node.bootstrap.unchecked.UncheckedTransaction
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionService
import cash.atto.node.bootstrap.unchecked.toUncheckedTransaction
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

@Component
class DiscoveryProcessor(
    val uncheckedTransactionService: UncheckedTransactionService,
) {
    private val mutex = Mutex()
    private val duplicateDetector = DuplicateDetector<AttoHash>(2.minutes)
    private val buffer = Channel<UncheckedTransaction>(Channel.UNLIMITED)

    @EventListener
    suspend fun process(event: TransactionDiscovered) {
        if (duplicateDetector.isDuplicate(event.transaction.hash)) {
            return
        }
        buffer.send(event.transaction.toUncheckedTransaction())
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.SECONDS)
    suspend fun flush() {
        if (mutex.isLocked) {
            return
        }
        mutex.withLock {
            do {
                val batch = mutableListOf<UncheckedTransaction>()

                do {
                    val transaction = buffer.tryReceive().getOrNull()
                    transaction?.let { batch.add(it) }
                } while (batch.size < 1000 && transaction != null)

                if (batch.isNotEmpty()) {
                    uncheckedTransactionService.save(batch)
                }
            } while (batch.isNotEmpty())
        }
    }
}
