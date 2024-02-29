package atto.node.election

import atto.node.network.BroadcastNetworkMessage
import atto.node.network.BroadcastStrategy
import atto.node.network.NetworkMessagePublisher
import atto.node.transaction.TransactionSaveSource
import atto.node.transaction.TransactionService
import atto.node.vote.VoteService
import atto.protocol.AttoNode
import atto.protocol.transaction.AttoTransactionPush
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
                transactionPush
            )
        )
    }
}