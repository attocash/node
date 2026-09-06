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
import java.time.Instant

sealed interface NetworkMessage<T : AttoMessage> : ResolvableTypeProvider {
    val payload: T
    val timestamp: Instant

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
    override val timestamp: Instant = Instant.now(),
) : NetworkMessage<T>

data class DirectNetworkMessage<T : AttoMessage>(
    val publicUri: URI,
    override val payload: T,
    val expectedResponseCount: ULong = 0UL,
    override val timestamp: Instant = Instant.now(),
) : NetworkMessage<T>

enum class BroadcastStrategy {
    EVERYONE,
    VOTERS,
}

data class BroadcastNetworkMessage<T : AttoMessage>(
    val strategy: BroadcastStrategy,
    val exceptions: Set<URI> = setOf(),
    override val payload: T,
    override val timestamp: Instant = Instant.now(),
) : NetworkMessage<T> {
    fun accepts(node: AttoNode): Boolean {
        if (exceptions.contains(node.publicUri)) {
            return false
        }

        return when (strategy) {
            BroadcastStrategy.EVERYONE -> true
            BroadcastStrategy.VOTERS -> node.isVoter()
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
