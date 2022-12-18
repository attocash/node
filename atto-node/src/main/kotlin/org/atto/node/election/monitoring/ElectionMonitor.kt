package org.atto.node.election.monitoring

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.atto.node.EventPublisher
import org.atto.node.election.ElectionExpiring
import org.atto.node.election.ElectionFinished
import org.atto.node.network.BroadcastNetworkMessage
import org.atto.node.network.BroadcastStrategy
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.transaction.TransactionSaved
import org.atto.node.transaction.TransactionService
import org.atto.node.vote.VoteService
import org.atto.protocol.transaction.AttoTransactionPush
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class ElectionMonitor(
    private val messagePublisher: NetworkMessagePublisher,
    private val eventPublisher: EventPublisher,
    private val transactionService: TransactionService,
    private val voteService: VoteService,
) {
    private val logger = KotlinLogging.logger {}

    @EventListener
    fun process(event: ElectionFinished) = runBlocking {
        launch {
            val account = event.account
            val transaction = event.transaction
            val votes = event.votes

            transactionService.save(transaction)
            voteService.saveAll(votes)

            eventPublisher.publish(TransactionSaved(account, transaction))
        }
    }

    @EventListener
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

        //TODO: ask for votes
    }
}