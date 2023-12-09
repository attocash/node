package atto.node

import kotlinx.coroutines.CoroutineName
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

    val defaultScope = CoroutineScope(Dispatchers.Default + CoroutineName(this.javaClass.simpleName))

    fun publish(event: Event) {
        defaultScope.launch {
            try {
                logger.trace { "$event" }
                publisher.publishEvent(event)
            } catch (e: Exception) {
                logger.error(e) { "$event failed" }
            }
        }
    }

}