package cash.atto.node.transaction.priotization

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.stereotype.Component

@ExperimentalCoroutinesApi
@Component
class TransactionPrioritizationInfoContributor(
    val prioritizer: TransactionPrioritizer,
) : InfoContributor {
    override fun contribute(builder: Info.Builder) {
        val election =
            mapOf(
                "queue-size" to prioritizer.getQueueSize(),
                "buffer-size" to prioritizer.getBufferSize(),
            )
        builder.withDetail("transaction-prioritizer", election)
    }
}
