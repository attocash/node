package cash.atto.node.network

import cash.atto.protocol.AttoMessage
import cash.atto.protocol.AttoNode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.ResolvableType
import org.springframework.core.ResolvableTypeProvider
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.net.URI

sealed interface NetworkMessage<T : AttoMessage> : ResolvableTypeProvider {
    val payload: T

    override fun getResolvableType(): ResolvableType =
        ResolvableType.forClassWithGenerics(this.javaClass, ResolvableType.forInstance(payload))
}

enum class MessageSource {
    WEBSOCKET,
    REST,
}

data class InboundNetworkMessage<T : AttoMessage>(
    val source: MessageSource,
    val publicUri: URI,
    val socketAddress: InetSocketAddress,
    override val payload: T,
) : NetworkMessage<T>

interface OutboundNetworkMessage<T : AttoMessage> : NetworkMessage<T> {
    fun accepts(
        target: URI,
        node: AttoNode?,
    ): Boolean
}

data class DirectNetworkMessage<T : AttoMessage>(
    val publicUri: URI,
    override val payload: T,
    val expectedResponseCount: ULong = 0UL,
) : OutboundNetworkMessage<T> {
    override fun accepts(
        target: URI,
        node: AttoNode?,
    ): Boolean = publicUri == target
}

enum class BroadcastStrategy {
    EVERYONE,
    VOTERS,
}

data class BroadcastNetworkMessage<T : AttoMessage>(
    val strategy: BroadcastStrategy,
    val exceptions: Set<URI> = setOf(),
    override val payload: T,
) : OutboundNetworkMessage<T> {
    override fun accepts(
        target: URI,
        node: AttoNode?,
    ): Boolean {
        if (exceptions.contains(target)) {
            return false
        }

        return when (strategy) {
            BroadcastStrategy.EVERYONE -> {
                node != null
            }

            BroadcastStrategy.VOTERS -> {
                node?.isVoter() ?: false
            }
        }
    }
}

@Component
class NetworkMessagePublisher(
    private val publisher: ApplicationEventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    fun publish(message: NetworkMessage<*>) {
        logger.trace { "$message" }
        publisher.publishEvent(message)
    }
}
