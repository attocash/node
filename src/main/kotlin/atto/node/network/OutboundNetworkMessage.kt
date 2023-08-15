package atto.node.network

import atto.protocol.network.AttoMessage
import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.ResolvableType
import org.springframework.core.ResolvableTypeProvider
import org.springframework.stereotype.Component
import java.net.InetSocketAddress

interface NetworkMessage<T : AttoMessage> : ResolvableTypeProvider {
    val payload: T
    override fun getResolvableType(): ResolvableType {
        return ResolvableType.forClassWithGenerics(this.javaClass, ResolvableType.forInstance(payload))
    }
}

data class OutboundNetworkMessage<T : AttoMessage>(
    val socketAddress: InetSocketAddress,
    override val payload: T
) : NetworkMessage<T>

data class InboundNetworkMessage<T : AttoMessage>(
    val socketAddress: InetSocketAddress,
    override val payload: T
) : NetworkMessage<T>

enum class BroadcastStrategy {
    EVERYONE,
//    MINORITY,
    VOTERS,
//    HISTORICAL,
}

data class BroadcastNetworkMessage<T : AttoMessage>(
    val strategy: BroadcastStrategy,
    val exceptions: Set<InetSocketAddress> = setOf(),
    override val payload: T,
) : NetworkMessage<T>

@Component
class NetworkMessagePublisher(private val publisher: ApplicationEventPublisher) {
    private val logger = KotlinLogging.logger {}
    fun publish(message: NetworkMessage<*>) {
        logger.trace { "$message" }
        publisher.publishEvent(message)
    }

}