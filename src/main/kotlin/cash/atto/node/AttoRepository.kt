package cash.atto.node

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono

interface AttoRepository {
    suspend fun deleteAll()

    suspend fun executeAfterCompletion(callback: (Int) -> Unit) {
        val manager = TransactionSynchronizationManager.forCurrentTransaction().awaitSingle()
        manager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCompletion(status: Int): Mono<Void> =
                    Mono.fromRunnable {
                        callback.invoke(status)
                    }
            },
        )
    }
}
