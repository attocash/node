package cash.atto.node

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono
import java.time.Instant

interface Event {
    val timestamp: Instant
}

@Component
class EventPublisher(
    private val publisher: ApplicationEventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    fun publish(event: Event) {
        logger.trace { "$event" }
        publisher.publishEvent(event)
    }

    suspend fun publishAfterCommit(event: Event) {
        val manager = TransactionSynchronizationManager.forCurrentTransaction().awaitSingle()
        manager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit(): Mono<Void> =
                    Mono.fromRunnable {
                        publish(event)
                    }
            },
        )
    }
}
