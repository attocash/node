package cash.atto.node.bootstrap.unchecked

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.DependsOn
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Component
@DependsOn("flywayInitializer")
class UncheckedTransactionMetricProvider(
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val count = AtomicLong(0)

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    suspend fun calculate() {
        count.set(uncheckedTransactionRepository.fastCount())
    }

    @PostConstruct
    fun start() {
        Gauge
            .builder("transactions.unchecked.count") { count.get() }
            .description("Current number of unchecked transactions")
            .register(meterRegistry)
    }
}
