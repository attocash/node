package org.atto.node

import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

abstract class Event<T>(val payload: T)

@Component
class EventPublisher(private val publisher: ApplicationEventPublisher) {
    private val logger = KotlinLogging.logger {}

    fun publish(event: Event<*>) {
        publisher.publishEvent(event)
        logger.debug { "$event" }
    }

}