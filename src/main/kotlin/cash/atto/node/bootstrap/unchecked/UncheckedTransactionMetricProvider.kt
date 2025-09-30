package cash.atto.node.bootstrap.unchecked

import cash.atto.node.bootstrap.TransactionResolved
import cash.atto.node.bootstrap.UncheckedTransactionSaved
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.springframework.context.annotation.DependsOn
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes

@Component
@DependsOn("flywayInitializer")
class UncheckedTransactionMetricProvider(
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    private val count = AtomicLong(0)

    @OptIn(DelicateCoroutinesApi::class)
    @PostConstruct
    fun start() {
        Gauge
            .builder("transactions.unchecked.count") { count.get() }
            .description("Current number of unchecked transactions")
            .register(meterRegistry)
        GlobalScope.launch {
            while (true) {
                try {
                    count.set(uncheckedTransactionRepository.countAll())
                } catch (e: Exception) {
                    logger.error(e) { "Error while getting unchecked transaction count from database" }
                    delay(1.minutes)
                }
            }
        }
    }
}
