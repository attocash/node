package cash.atto.node.bootstrap.discovery

import cash.atto.node.bootstrap.TransactionDiscovered
import cash.atto.node.bootstrap.unchecked.UncheckedTransaction
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionService
import cash.atto.node.bootstrap.unchecked.toUncheckedTransaction
import kotlinx.coroutines.channels.Channel
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class DiscoveryProcessor(
    val uncheckedTransactionService: UncheckedTransactionService,
) {
    private val buffer = Channel<UncheckedTransaction>(Channel.UNLIMITED)

    @EventListener
    suspend fun process(event: TransactionDiscovered) {
        buffer.send(event.transaction.toUncheckedTransaction())
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
    suspend fun flush() {
        val batch = mutableListOf<UncheckedTransaction>()

        do {
            val transaction = buffer.tryReceive().getOrNull()
            transaction?.let { batch.add(it) }
        } while (batch.size < 1000 && transaction != null)

        if (batch.isNotEmpty()) {
            uncheckedTransactionService.save(batch)
        }
    }
}
