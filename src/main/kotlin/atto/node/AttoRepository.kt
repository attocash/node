package atto.node

import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono

interface AttoRepository {
    suspend fun deleteAll()

    suspend fun executeAfterCommit(callback: () -> Unit) {
        val manager = TransactionSynchronizationManager.forCurrentTransaction().awaitSingle()
        manager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit(): Mono<Void> =
                    Mono.fromRunnable {
                        callback.invoke()
                    }
            },
        )
    }
}
