package cash.atto.node.bootstrap.discovery

import cash.atto.node.bootstrap.unchecked.UncheckedTransaction
import java.time.Instant

enum class DiscoverySource {
    GAP,
    HEAD,
    DEPENDENCY,
    SEND,
}

internal data class PendingDiscovery(
    val transaction: UncheckedTransaction,
    val source: DiscoverySource,
    val enqueuedAt: Instant,
)

internal val DiscoverySource.metricTag: String
    get() = name.lowercase()
