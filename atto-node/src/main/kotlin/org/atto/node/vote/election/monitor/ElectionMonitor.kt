package org.atto.node.vote.election.monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.atto.node.EventPublisher
import org.atto.node.network.BroadcastNetworkMessage
import org.atto.node.network.BroadcastStrategy
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.transaction.TransactionConfirmed
import org.atto.node.transaction.TransactionObserved
import org.atto.node.transaction.TransactionRepository
import org.atto.node.transaction.TransactionStaled
import org.atto.node.vote.election.ElectionObserver
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.transaction.TransactionPush
import org.atto.protocol.transaction.TransactionStatus
import org.atto.protocol.vote.HashVote
import org.springframework.stereotype.Service

@Service
class ElectionMonitor(
    private val scope: CoroutineScope,
    private val eventPublisher: EventPublisher,
    private val messagePublisher: NetworkMessagePublisher,
    private val transactionRepository: TransactionRepository
) : ElectionObserver {
    private val logger = KotlinLogging.logger {}

    override suspend fun observed(transaction: Transaction) {
        val event = TransactionObserved(transaction)
        eventPublisher.publish(event)
        logger.debug { "$event" }
    }

    override suspend fun confirmed(transaction: Transaction, hashVotes: Collection<HashVote>) {
        scope.launch {
            val confirmedTransaction = transaction.copy(status = TransactionStatus.CONFIRMED)
            transactionRepository.save(confirmedTransaction)
            val event = TransactionConfirmed(confirmedTransaction)
            eventPublisher.publish(event)
            logger.debug { "$event" }
        }
    }

    override suspend fun staling(transaction: Transaction) {
        val transactionPush = TransactionPush(transaction)
        messagePublisher.publish(
            BroadcastNetworkMessage(
                BroadcastStrategy.VOTERS,
                emptySet(),
                this,
                transactionPush
            )
        )
        logger.debug { "Staling transaction was rebroadcasted $transaction" }
    }

    override suspend fun staled(transaction: Transaction) {
        eventPublisher.publish(TransactionStaled(transaction))
        logger.debug { "Transaction staled $transaction" }
    }
}