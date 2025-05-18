package cash.atto.node.transaction

import cash.atto.node.account.AccountUpdated
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Component
class TransactionMetricProvider(
    private val meterRegistry: MeterRegistry,
) {
    private val timers = ConcurrentHashMap<String, Timer>()

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
    }
}
