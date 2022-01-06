package org.atto.node

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

abstract class Event<T>(val payload: T)

@Component
class EventPublisher(private val publisher: ApplicationEventPublisher) {

    fun publish(event: Event<*>) {
        publisher.publishEvent(event)
    }

}