package cash.atto.node.bootstrap.unchecked

import cash.atto.node.account.AccountUpdated
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

@Component
class UncheckedWorkTracker {
    private val generation = AtomicLong()

    fun currentGeneration(): Long = generation.get()

    fun markChanged() {
        generation.incrementAndGet()
    }

    @EventListener
    fun process(
        @Suppress("UNUSED_PARAMETER") event: AccountUpdated,
    ) {
        markChanged()
    }
}
