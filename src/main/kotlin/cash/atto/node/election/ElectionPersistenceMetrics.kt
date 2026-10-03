package cash.atto.node.election

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.core.Ordered
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit

internal class ElectionPersistenceMetrics(
    meterRegistry: MeterRegistry,
) {
    private val clock = meterRegistry.config().clock()
    private val timers =
        listOf("acquire_begin", "body", "commit", "after_commit", "cleanup").associateWith { phase ->
            Timer
                .builder("elections.persistence.phase")
                .description("Transaction phases for successfully completed election persistence batches")
                .tag("phase", phase)
                .register(meterRegistry)
        }

    fun start(): Sample = Sample(clock.monotonicTime())

    inner class Sample(
        private val startedAt: Long,
    ) : TransactionSynchronization,
        Ordered {
        private var bodyStartedAt: Long? = null
        private var bodyFinishedAt: Long? = null
        private var committedAt: Long? = null
        private var completingAt: Long? = null

        override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

        suspend fun startBody(transaction: ReactiveTransaction) {
            bodyStartedAt = clock.monotonicTime()
            if (!transaction.isNewTransaction) return

            val manager = TransactionSynchronizationManager.forCurrentTransaction().awaitSingle()
            if (manager.isSynchronizationActive) {
                manager.registerSynchronization(this)
            }
        }

        fun finishBody() {
            bodyFinishedAt = clock.monotonicTime()
        }

        override fun afterCommit(): Mono<Void> =
            Mono.fromRunnable {
                committedAt = clock.monotonicTime()
            }

        override fun afterCompletion(status: Int): Mono<Void> =
            Mono.fromRunnable {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    completingAt = clock.monotonicTime()
                }
            }

        fun record() {
            val finishedAt = clock.monotonicTime()
            // A joined transaction has not completed yet; never publish a partial phase population.
            val bodyStartedAt = bodyStartedAt ?: return
            val bodyFinishedAt = bodyFinishedAt ?: return
            val committedAt = committedAt ?: return
            val completingAt = completingAt ?: return

            timers.getValue("acquire_begin").record(bodyStartedAt - startedAt, TimeUnit.NANOSECONDS)
            timers.getValue("body").record(bodyFinishedAt - bodyStartedAt, TimeUnit.NANOSECONDS)
            timers.getValue("commit").record(committedAt - bodyFinishedAt, TimeUnit.NANOSECONDS)
            timers.getValue("after_commit").record(completingAt - committedAt, TimeUnit.NANOSECONDS)
            timers.getValue("cleanup").record(finishedAt - completingAt, TimeUnit.NANOSECONDS)
        }
    }
}
