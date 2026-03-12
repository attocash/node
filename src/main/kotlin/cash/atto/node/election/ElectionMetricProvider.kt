package cash.atto.node.election

import cash.atto.node.vote.VoteValidated
import cash.atto.protocol.AttoNode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PostConstruct
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

@Component
class ElectionMetricProvider(
    private val thisNode: AttoNode,
    private val meterRegistry: MeterRegistry,
) {
    private val lastVoteLatency = AtomicReference(Duration.ZERO)
    private val lastFinalVoteLatency = AtomicReference(Duration.ZERO)

    @PostConstruct
    fun start() {
        meterRegistry.gauge("election.vote.latency", Tags.of("type", "non-final"), lastVoteLatency) { it.get().toMillis().toDouble() }
        meterRegistry.gauge("election.vote.latency", Tags.of("type", "final"), lastFinalVoteLatency) { it.get().toMillis().toDouble() }
    }

    @EventListener
    fun process(event: VoteValidated) {
        if (event.vote.publicKey != thisNode.publicKey) {
            return
        }

        val latency = Duration.between(event.transaction.receivedAt, event.timestamp)

        if (event.vote.isFinal()) {
            lastFinalVoteLatency.set(latency)
        } else {
            lastVoteLatency.set(latency)
        }
    }
}
