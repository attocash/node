package cash.atto.node.election

import cash.atto.node.account.AccountService
import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.BroadcastStrategy
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.vote.VoteService
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionPush
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.util.concurrent.TimeUnit

@Service
class ElectionProcessor(
    private val thisNode: AttoNode,
    private val messagePublisher: NetworkMessagePublisher,
    private val accountService: AccountService,
    private val voteService: VoteService,
    transactionManager: ReactiveTransactionManager,
) {
    private val logger = KotlinLogging.logger {}

    private val buffer = Channel<ElectionConsensusReached>(Channel.UNLIMITED)

    private val transactionalOperator = TransactionalOperator.create(transactionManager)
    private val mutex = Mutex()

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

    @EventListener
    suspend fun process(event: ElectionConsensusReached) {
        buffer.send(event)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Scheduled(fixedRate = 10, timeUnit = TimeUnit.MILLISECONDS)
    suspend fun flush() {
        mutex.withLock {
            while (!buffer.isEmpty) {
                flushBatch(1_000)
            }
        }
    }

    private suspend fun flushBatch(size: Int) {
        val events = mutableListOf<ElectionConsensusReached>()

        try {
            for (i in 1..size) {
                val event = buffer.tryReceive().getOrNull() ?: break
                events += event
            }

            if (events.isEmpty()) return

            val transactions = events.map { it.transaction }

            transactionalOperator.executeAndAwait {
                accountService.add(TransactionSource.ELECTION, transactions)

                if (thisNode.isHistorical()) {
                    val finalVotes = events.flatMap { it.votes }.filter { it.isFinal() }
                    voteService.saveAll(finalVotes)
                }
            }
        } catch (e: Exception) {
            events.forEach {
                buffer.send(it)
            }
            delay(10_000)
            throw e
        }
    }
}
