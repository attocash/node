package atto.node

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

interface Event {
}

@Component
class EventPublisher(private val publisher: ApplicationEventPublisher) {
    private val logger = KotlinLogging.logger {}

    val defaultScope = CoroutineScope(Dispatchers.Default + attoCoroutineExceptionHandler)

    fun publish(event: Event) {
        defaultScope.launch {
            logger.trace { "$event" }
            publisher.publishEvent(event)
        }
    }

}