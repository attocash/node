package cash.atto.node.election

import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.BroadcastStrategy
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.TransactionSaveSource
import cash.atto.node.transaction.TransactionService
import cash.atto.node.vote.VoteService
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionPush
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class ElectionMonitor(
    private val thisNode: AttoNode,
    private val messagePublisher: NetworkMessagePublisher,
    private val transactionService: TransactionService,
    private val voteService: VoteService,
) {
    private val logger = KotlinLogging.logger {}

    @EventListener
    suspend fun process(event: ElectionConsensusReached) {
        val transaction = event.transaction
        val votes = event.votes

        transactionService.save(TransactionSaveSource.ELECTION, transaction)
        if (thisNode.isHistorical()) {
            voteService.saveAll(votes)
        }
    }

    @EventListener
    fun process(event: ElectionExpiring) {
        val transaction = event.transaction
        val transactionPush = AttoTransactionPush(transaction.toAttoTransaction())

        logger.info { "Expiring transaction will be rebroadcasted $transaction" }

        messagePublisher.publish(
            BroadcastNetworkMessage(
                BroadcastStrategy.VOTERS,
                emptySet(),
                transactionPush,
            ),
        )
    }
}
