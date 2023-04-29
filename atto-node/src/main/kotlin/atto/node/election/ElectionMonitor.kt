package atto.node.election

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging
import atto.node.network.BroadcastNetworkMessage
import atto.node.network.BroadcastStrategy
import atto.node.network.NetworkMessagePublisher
import atto.node.transaction.TransactionService
import atto.node.vote.VoteService
import atto.protocol.transaction.AttoTransactionPush
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
class ElectionMonitor(
    private val messagePublisher: NetworkMessagePublisher,
    private val transactionService: TransactionService,
    private val voteService: VoteService,
) {
    private val logger = KotlinLogging.logger {}

    val ioScope = CoroutineScope(Dispatchers.IO + CoroutineName(this.javaClass.simpleName))

    @EventListener
    @Async
    fun process(event: ElectionFinished) {
        ioScope.launch {
            val transaction = event.transaction
            val votes = event.votes

            transactionService.save(transaction)
            voteService.saveAll(votes)
        }
    }

    @EventListener
    @Async
    fun process(event: ElectionExpiring) {
        val transaction = event.transaction
        val transactionPush = AttoTransactionPush(transaction.toAttoTransaction())

        logger.debug { "Expiring transaction will be rebroadcasted $transaction" }

        messagePublisher.publish(
            BroadcastNetworkMessage(
                BroadcastStrategy.VOTERS,
                emptySet(),
                transactionPush
            )
        )
    }
}