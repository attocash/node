package org.atto.node.election

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.atto.node.network.BroadcastNetworkMessage
import org.atto.node.network.BroadcastStrategy
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.transaction.TransactionService
import org.atto.node.vote.VoteService
import org.atto.protocol.transaction.AttoTransactionPush
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class ElectionMonitor(
    private val messagePublisher: NetworkMessagePublisher,
    private val transactionService: TransactionService,
    private val voteService: VoteService,
) {
    private val logger = KotlinLogging.logger {}

    val ioScope = CoroutineScope(Dispatchers.IO + CoroutineName("ElectionMonitor"))

    @EventListener
    fun process(event: ElectionFinished) {
        ioScope.launch {
            val account = event.account
            val transaction = event.transaction
            val votes = event.votes

            transactionService.save(transaction)
            voteService.saveAll(votes)
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
    }
}