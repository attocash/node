package atto.node.bootstrap.discovery

import atto.node.EventPublisher
import atto.node.account.AccountRepository
import atto.node.account.getByAlgorithmAndPublicKey
import atto.node.bootstrap.TransactionDiscovered
import atto.node.election.ElectionVoter
import atto.node.election.TransactionWeighter
import atto.node.network.*
import atto.node.transaction.TransactionRepository
import atto.node.transaction.toTransaction
import atto.node.vote.convertion.VoteConverter
import atto.node.vote.weight.VoteWeighter
import atto.protocol.bootstrap.AttoBootstrapTransactionPush
import atto.protocol.vote.AttoVoteStreamCancel
import atto.protocol.vote.AttoVoteStreamRequest
import atto.protocol.vote.AttoVoteStreamResponse
import cash.atto.commons.AttoHash
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.flow.map
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class LastDiscoverer(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val networkMessagePublisher: NetworkMessagePublisher,
    private val eventPublisher: EventPublisher,
    private val voteConverter: VoteConverter,
    private val voteWeighter: VoteWeighter,
) {
    private val logger = KotlinLogging.logger {}

    private val transactionWeighterMap = Caffeine.newBuilder()
        .maximumSize(100_000)
        .build<AttoHash, TransactionWeighter>()
        .asMap()

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    suspend fun broadcastSample() {
        val transactions = transactionRepository.getLastSample(20)
        transactions
            .map { AttoBootstrapTransactionPush(it.toAttoTransaction()) }
            .map { BroadcastNetworkMessage(BroadcastStrategy.EVERYONE, setOf(), it) }
            .collect { networkMessagePublisher.publish(it) }
    }

    @EventListener
    suspend fun processPush(message: InboundNetworkMessage<AttoBootstrapTransactionPush>) {
        val response = message.payload
        val transaction = response.transaction.toTransaction()
        val block = transaction.block

        val account = accountRepository.getByAlgorithmAndPublicKey(block.algorithm, block.publicKey)

        if (account.height >= block.height) {
            return
        }

        if (transactionWeighterMap.putIfAbsent(
                transaction.hash,
                TransactionWeighter(account, transaction)
            ) != null
        ) {
            return
        }

        val request = AttoVoteStreamRequest(transaction.hash)
        networkMessagePublisher.publish(
            DirectNetworkMessage(
                message.publicUri,
                request
            )
        )
    }

    @EventListener
    suspend fun processVoteResponse(message: InboundNetworkMessage<AttoVoteStreamResponse>) {
        val blockHash = message.payload.blockHash
        val vote = voteConverter.convert(blockHash, message.payload.vote)

        if (vote.weight < ElectionVoter.MIN_WEIGHT) {
            return
        }

        val weighter = transactionWeighterMap.computeIfPresent(blockHash) { _, weighter ->
            weighter.add(vote)
            weighter
        }

        if (weighter != null && weighter.totalFinalWeight() >= voteWeighter.getMinimalConfirmationWeight()) {
            logger.debug { "Discovered missing last transaction $blockHash" }
            val request = AttoVoteStreamCancel(blockHash)
            networkMessagePublisher.publish(
                DirectNetworkMessage(
                    message.publicUri,
                    request
                )
            )
            eventPublisher.publish(
                TransactionDiscovered(
                    null,
                    weighter.transaction,
                    weighter.votes.values.toList()
                )
            )
        }
    }
}