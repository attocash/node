package cash.atto.node.network.guardian

import cash.atto.node.CacheSupport
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.protocol.AttoTransactionStreamRequest
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class ExpectedResponses(
    private val clock: Clock,
) : CacheSupport {
    private val responsesByPeer = ConcurrentHashMap<URI, Credit>()

    internal val peerCount: Int
        get() = responsesByPeer.size

    fun record(message: DirectNetworkMessage<*>) {
        if (message.expectedResponseCount == 0UL) {
            return
        }
        val now = clock.instant()
        val expiresAt = message.timestamp.plus(AttoTransactionStreamRequest.TIMEOUT)
        if (!now.isBefore(expiresAt)) {
            return
        }
        responsesByPeer.compute(message.publicUri) { _, credit ->
            val remaining = credit?.takeIf { now.isBefore(it.expiresAt) }
            Credit(
                count = (remaining?.count ?: 0UL) + message.expectedResponseCount,
                expiresAt = maxOf(remaining?.expiresAt ?: expiresAt, expiresAt),
            )
        }
    }

    fun consume(publicUri: URI): Boolean {
        val now = clock.instant()
        var expected = false
        responsesByPeer.computeIfPresent(publicUri) { _, credit ->
            if (!now.isBefore(credit.expiresAt)) {
                return@computeIfPresent null
            }
            expected = true
            if (credit.count == 1UL) null else credit.copy(count = credit.count - 1UL)
        }
        return expected
    }

    fun removeExpired() {
        val now = clock.instant()
        responsesByPeer.keys.forEach { publicUri ->
            responsesByPeer.computeIfPresent(publicUri) { _, credit -> credit.takeIf { now.isBefore(it.expiresAt) } }
        }
    }

    override fun clear() {
        responsesByPeer.clear()
    }

    private data class Credit(
        val count: ULong,
        val expiresAt: Instant,
    )
}
