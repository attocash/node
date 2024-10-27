package cash.atto.node

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono

interface Event

@Component
class EventPublisher(
    private val publisher: ApplicationEventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    val defaultScope = CoroutineScope(Dispatchers.Default)

    @PreDestroy
    fun destroy() {
        defaultScope.cancel()
    }

    fun publish(event: Event) {
        if (!defaultScope.isActive) {
            logger.warn { "Scope is not active anymore. Ignoring $event" }
            return
        }
        defaultScope.launch {
            try {
                publishSync(event)
            } catch (e: Exception) {
                logger.error(e) { "Exception while publishing $event" }
            }
        }
    }

    fun publishSync(event: Event) {
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
