package cash.atto.node.election

import cash.atto.node.vote.VoteValidated
import cash.atto.protocol.AttoNode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PostConstruct
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class ElectionMetricProvider(
    private val thisNode: AttoNode,
    private val meterRegistry: MeterRegistry,
) {
    private lateinit var voteLatencyTimer: Timer
    private lateinit var finalVoteLatencyTimer: Timer

    @PostConstruct
    fun start() {
        voteLatencyTimer =
            Timer
                .builder("election.vote.latency")
                .tag("type", "all")
                .register(meterRegistry)

        finalVoteLatencyTimer =
            Timer
                .builder("election.vote.latency")
                .tag("type", "final")
                .register(meterRegistry)
    }

    @EventListener
    fun process(event: VoteValidated) {
        if (event.vote.publicKey != thisNode.publicKey) {
            return
        }

        val latency = Duration.between(event.transaction.receivedAt, event.timestamp)

        voteLatencyTimer.record(latency)
        if (event.vote.isFinal()) {
            finalVoteLatencyTimer.record(latency)
        }
    }
}
