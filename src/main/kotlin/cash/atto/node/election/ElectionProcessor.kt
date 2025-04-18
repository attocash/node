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
import kotlinx.coroutines.channels.Channel
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.TimeUnit

@Service
class ElectionProcessor(
    private val thisNode: AttoNode,
    private val messagePublisher: NetworkMessagePublisher,
    private val accountService: AccountService,
    private val voteService: VoteService,
) {
    private val logger = KotlinLogging.logger {}

    private val buffer = Channel<ElectionConsensusReached>(Channel.UNLIMITED)

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

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MILLISECONDS)
    @Transactional
    suspend fun flush() {
        val events = mutableListOf<ElectionConsensusReached>()

        for (i in 1..1000) {
            val event = buffer.tryReceive().getOrNull() ?: break
            events += event
        }

        if (events.isEmpty()) return

        val transactions = events.map { it.transaction }

        accountService.add(TransactionSource.ELECTION, transactions)

        if (thisNode.isHistorical()) {
            val finalVotes = events.flatMap { it.votes }.filter { it.isFinal() }
            voteService.saveAll(finalVotes)
        }
    }
}
