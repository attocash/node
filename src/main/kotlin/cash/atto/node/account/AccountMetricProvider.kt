package cash.atto.node.account

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.springframework.context.annotation.DependsOn
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
@DependsOn("flywayInitializer")
class AccountMetricProvider(
    private val repository: AccountCrudRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val count = AtomicLong(0)

    @OptIn(DelicateCoroutinesApi::class)
    @PostConstruct
    fun start() {
        Gauge
            .builder("account.height.count") { count.get() }
            .description("Current sum of all account heights")
            .register(meterRegistry)

        runBlocking {
            count.addAndGet(repository.sumHeight())
        }
    }

    @EventListener
    fun process(event: AccountUpdated) {
        count.incrementAndGet()
    }
}
