package org.atto.node.network

import org.atto.protocol.network.AttoMessage
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.PayloadApplicationEvent
import org.springframework.stereotype.Component
import java.net.InetSocketAddress

class OutboundNetworkMessage<T : AttoMessage>(val socketAddress: InetSocketAddress, source: Any, payload: T) :
    PayloadApplicationEvent<T>(source, payload)

class InboundNetworkMessage<T : AttoMessage>(val socketAddress: InetSocketAddress, source: Any, payload: T) :
    PayloadApplicationEvent<T>(source, payload)

enum class BroadcastStrategy(val percentage: Int) {
    EVERYONE(100),
    MAJORITY(65),
    MINORITY(40),
    VOTERS(100),
}

data class BroadcastNetworkMessage<T : AttoMessage>(
    val strategy: BroadcastStrategy,
    val exceptions: Set<InetSocketAddress>,
    internal val source: Any,
    internal val payload: T
) : PayloadApplicationEvent<T>(source, payload)

@Component
class NetworkMessagePublisher(private val publisher: ApplicationEventPublisher) {

    fun publish(message: OutboundNetworkMessage<*>) {
        publisher.publishEvent(message)
    }

    fun publish(message: InboundNetworkMessage<*>) {
        publisher.publishEvent(message)
    }

    fun publish(message: BroadcastNetworkMessage<*>) {
        publisher.publishEvent(message)
    }

}