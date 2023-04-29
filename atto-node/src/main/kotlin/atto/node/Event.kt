package atto.node

import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

interface Event {
}

@Component
class EventPublisher(private val publisher: ApplicationEventPublisher) {
    private val logger = KotlinLogging.logger {}

    fun publish(event: Event) {
        logger.trace { "$event" }
        publisher.publishEvent(event)
    }

}