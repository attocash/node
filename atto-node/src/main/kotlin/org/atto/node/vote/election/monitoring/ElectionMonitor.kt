package org.atto.node.vote.election.monitoring

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.atto.node.EventPublisher
import org.atto.node.account.Account
import org.atto.node.network.BroadcastNetworkMessage
import org.atto.node.network.BroadcastStrategy
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.transaction.*
import org.atto.node.vote.Vote
import org.atto.node.vote.VoteService
import org.atto.node.vote.election.ElectionObserver
import org.atto.protocol.transaction.AttoTransactionPush
import org.springframework.stereotype.Service

@Service
class ElectionMonitor(
    private val eventPublisher: EventPublisher,
    private val messagePublisher: NetworkMessagePublisher,
    private val transactionService: TransactionService,
    private val voteService: VoteService,
) : ElectionObserver {
    private val logger = KotlinLogging.logger {}

    override suspend fun observed(account: Account, transaction: Transaction) {
        val event = TransactionObserved(account, transaction)
        eventPublisher.publish(event)
    }

    override suspend fun confirmed(account: Account, transaction: Transaction, votes: Collection<Vote>) {
        CoroutineScope(Dispatchers.Default).launch {
            transactionService.save(transaction)
            voteService.saveAll(votes)

            val event = TransactionConfirmed(account, transaction)
            eventPublisher.publish(event)
        }
    }

    override suspend fun staling(account: Account, transaction: Transaction) {
        val transactionPush = AttoTransactionPush(transaction.toAttoTransaction())
        messagePublisher.publish(
            BroadcastNetworkMessage(
                BroadcastStrategy.VOTERS,
                emptySet(),
                transactionPush
            )
        )
        logger.debug { "Staling transaction was rebroadcasted $transaction" }
    }

    override suspend fun staled(account: Account, transaction: Transaction) {
        val event = TransactionStaled(account, transaction)
        eventPublisher.publish(event)
        logger.debug { "Transaction staled $transaction" }
    }
}