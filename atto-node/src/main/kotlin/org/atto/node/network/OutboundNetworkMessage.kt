package org.atto.node.network

import org.atto.protocol.network.AttoMessage
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.net.InetSocketAddress

interface NetworkMessage<T : AttoMessage> {
    val payload: T
}

data class OutboundNetworkMessage<T : AttoMessage>(
    val socketAddress: InetSocketAddress,
    override val payload: T
) : NetworkMessage<T>

data class InboundNetworkMessage<T : AttoMessage>(
    val socketAddress: InetSocketAddress,
    override val payload: T
) : NetworkMessage<T>

enum class BroadcastStrategy(val percentage: Int) {
    EVERYONE(100),
    VOTERS(100),
}

data class BroadcastNetworkMessage<T : AttoMessage>(
    val strategy: BroadcastStrategy,
    val exceptions: Set<InetSocketAddress>,
    override val payload: T,
) : NetworkMessage<T>

@Component
class NetworkMessagePublisher(private val publisher: ApplicationEventPublisher) {

    fun publish(message: NetworkMessage<*>) {
        publisher.publishEvent(message)
    }

}