package cash.atto.node.transaction

import cash.atto.node.account.AccountUpdated
import cash.atto.node.election.ElectionConsensusReached
import cash.atto.node.election.ElectionStarted
import cash.atto.node.vote.VoteValidated
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Component
class TransactionMetricProvider(
    private val meterRegistry: MeterRegistry,
) {
    private val timers = ConcurrentHashMap<String, Timer>()
    private val pipelineTimers = ConcurrentHashMap<String, Timer>()
    private val rejectionCounters = ConcurrentHashMap<String, Counter>()
    private val droppedCounter =
        Counter
            .builder("transactions.dropped")
            .description("Transactions dropped before confirmation")
            .register(meterRegistry)

    @EventListener
    fun process(event: TransactionReceived) {
        recordPipelineLatency("received", event.transaction, event.timestamp)
    }

    @EventListener
    fun process(event: TransactionValidated) {
        recordPipelineLatency("validated", event.transaction, event.timestamp)
    }

    @EventListener
    fun process(event: ElectionStarted) {
        recordPipelineLatency("election_started", event.transaction, event.timestamp)
    }

    @EventListener
    fun process(event: VoteValidated) {
        val stage =
            if (event.vote.isFinal()) {
                "final_vote_validated"
            } else {
                "vote_validated"
            }
        recordPipelineLatency(stage, event.transaction, event.timestamp)
    }

    @EventListener
    fun process(event: ElectionConsensusReached) {
        recordPipelineLatency("consensus_reached", event.transaction, event.timestamp)
    }

    @EventListener
    fun process(accountUpdated: AccountUpdated) {
        val transaction = accountUpdated.transaction
        val duration = Instant.now().toEpochMilli() - transaction.receivedAt.toEpochMilli()
        val type = transaction.block.type.name

        val timer =
            timers.computeIfAbsent(type) {
                Timer
                    .builder("transactions.confirmation.time")
                    .description("Time taken to confirm a transaction after seen it first time")
                    .tag("type", type)
                    .register(meterRegistry)
            }

        timer.record(duration, TimeUnit.MILLISECONDS)
        recordPipelineLatency("account_updated", transaction, Instant.now())
    }

    @EventListener
    fun process(rejected: TransactionRejected) {
        val reason = rejected.reason.name
        val counter =
            rejectionCounters.computeIfAbsent(reason) {
                Counter
                    .builder("transactions.rejected")
                    .description("Transactions rejected before confirmation")
                    .tag("reason", reason)
                    .register(meterRegistry)
            }

        counter.increment()
    }

    @EventListener
    fun process(dropped: TransactionDropped) {
        droppedCounter.increment()
    }

    private fun recordPipelineLatency(
        stage: String,
        transaction: Transaction,
        timestamp: Instant,
    ) {
        val duration = Duration.between(transaction.receivedAt, timestamp)
        if (duration.isNegative) {
            return
        }

        val type = transaction.block.type.name
        val key = "$stage:$type"
        val timer =
            pipelineTimers.computeIfAbsent(key) {
                Timer
                    .builder("transactions.pipeline.latency")
                    .description("Cumulative transaction latency by pipeline stage")
                    .tag("stage", stage)
                    .tag("type", type)
                    .register(meterRegistry)
            }

        timer.record(duration)
    }
}
