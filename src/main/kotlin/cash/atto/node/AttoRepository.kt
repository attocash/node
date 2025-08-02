package cash.atto.node

import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono

interface AttoRepository {
    suspend fun deleteAll()
}

suspend fun getCurrentTransaction(): TransactionSynchronizationManager? =
    TransactionSynchronizationManager
        .forCurrentTransaction()
        .onErrorResume(NoTransactionException::class.java) { Mono.empty() }
        .awaitFirstOrNull()

suspend fun executeAfterCompletion(callback: (Int) -> Unit) {
    val manager = getCurrentTransaction()!!
    manager.registerSynchronization(
        object : TransactionSynchronization {
            override fun afterCompletion(status: Int): Mono<Void> =
                Mono.fromRunnable {
                    callback.invoke(status)
                }
        },
    )
}

suspend fun executeAfterCommit(callback: () -> Unit) {
    val manager = getCurrentTransaction()!!
    manager.registerSynchronization(
        object : TransactionSynchronization {
            override fun afterCommit(): Mono<Void> =
                Mono.fromRunnable {
                    callback.invoke()
                }
        },
    )
}
