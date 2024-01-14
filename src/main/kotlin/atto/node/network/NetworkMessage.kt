package atto.node.network

import atto.protocol.AttoMessage
import atto.protocol.AttoNode
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

data class InboundNetworkMessage<T : AttoMessage>(
    val socketAddress: InetSocketAddress,
    override val payload: T
) : NetworkMessage<T>

interface OutboundNetworkMessage<T : AttoMessage> : NetworkMessage<T> {
    fun accepts(target: InetSocketAddress, node: AttoNode?): Boolean

}

data class DirectNetworkMessage<T : AttoMessage>(
    val socketAddress: InetSocketAddress,
    override val payload: T
) : OutboundNetworkMessage<T> {
    override fun accepts(target: InetSocketAddress, node: AttoNode?): Boolean {
        return socketAddress == target
    }
}

enum class BroadcastStrategy {
    EVERYONE,
    VOTERS,
}

data class BroadcastNetworkMessage<T : AttoMessage>(
    val strategy: BroadcastStrategy,
    val exceptions: Set<InetSocketAddress> = setOf(),
    override val payload: T,
) : OutboundNetworkMessage<T> {
    override fun accepts(target: InetSocketAddress, node: AttoNode?): Boolean {
        if (exceptions.contains(target)) {
            return false
        }

        return when (strategy) {
            BroadcastStrategy.EVERYONE -> {
                true
            }

            BroadcastStrategy.VOTERS -> {
                node?.isVoter() ?: false
            }
        }
    }
}

@Component
class NetworkMessagePublisher(private val publisher: ApplicationEventPublisher) {
    private val logger = KotlinLogging.logger {}
    fun publish(message: NetworkMessage<*>) {
        logger.trace { "$message" }
        publisher.publishEvent(message)
    }

}