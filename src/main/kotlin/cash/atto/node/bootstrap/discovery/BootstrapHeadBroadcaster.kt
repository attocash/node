package cash.atto.node.bootstrap.discovery

import cash.atto.node.bootstrap.unchecked.UncheckedTransactionRepository
import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.BroadcastStrategy
import cash.atto.node.network.NodeConnectionManager
import cash.atto.node.transaction.TransactionRepository
import cash.atto.protocol.AttoBootstrapTransactionPush
import cash.atto.protocol.AttoNode
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class BootstrapHeadBroadcaster(
    private val thisNode: AttoNode,
    private val transactionRepository: TransactionRepository,
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val nodeConnectionManager: NodeConnectionManager,
) {
    private val mutex = Mutex()

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    suspend fun broadcastSample() {
        if (thisNode.isNotHistorical()) {
            return
        }

        mutex.withLock {
            if (uncheckedTransactionRepository.count() > 0) {
                return
            }

            transactionRepository
                .getLastSample(10)
                .map { AttoBootstrapTransactionPush(it.toAttoTransaction()) }
                .map { BroadcastNetworkMessage(BroadcastStrategy.EVERYONE, setOf(), it) }
                .collect { nodeConnectionManager.send(it) }
        }
    }
}
