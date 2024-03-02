package atto.node

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono

interface Event {
}

@Component
class EventPublisher(private val publisher: ApplicationEventPublisher) {
    private val logger = KotlinLogging.logger {}

    val defaultScope = CoroutineScope(Dispatchers.Default + attoCoroutineExceptionHandler)

    @PreDestroy
    fun destroy() {
        defaultScope.cancel()
    }

    fun publish(event: Event) {
        defaultScope.launch {
            publishSync(event)
        }
    }

    fun publishSync(event: Event) {
        logger.trace { "$event" }
        publisher.publishEvent(event)
    }

    suspend fun publishAfterCommit(event: Event) {
        val manager = TransactionSynchronizationManager.forCurrentTransaction().awaitSingle()
        manager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit(): Mono<Void> {
                return Mono.fromRunnable {
                    publish(event)
                }
            }
        })
    }

}