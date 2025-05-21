package cash.atto.node.account

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes

@Component
class AccountMetricProvider(
    private val repository: AccountCrudRepository,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    private val count = AtomicLong(0)

    @OptIn(DelicateCoroutinesApi::class)
    @PostConstruct
    fun start() {
        Gauge
            .builder("account.height.count") { count.get() }
            .description("Current sum of all account heights")
            .register(meterRegistry)
        GlobalScope.launch {
            while (true) {
                try {
                    count.addAndGet(repository.sumHeight())
                    return@launch
                } catch (e: Exception) {
                    logger.error(e) { "Error while getting total height count from database" }
                    delay(1.minutes)
                }
            }
        }
    }

    @EventListener
    fun process(event: AccountUpdated) {
        count.incrementAndGet()
    }
}
