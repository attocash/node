package atto.node.bootstrap.discovery

import atto.node.EventPublisher
import atto.node.account.AccountRepository
import atto.node.account.getByPublicKey
import atto.node.bootstrap.TransactionDiscovered
import atto.node.election.TransactionWeighter
import atto.node.network.*
import atto.node.transaction.TransactionRepository
import atto.node.transaction.toTransaction
import atto.node.vote.convertion.VoteConverter
import atto.node.vote.weight.VoteWeighter
import atto.protocol.bootstrap.AttoBootstrapTransactionPush
import atto.protocol.vote.AttoVoteRequest
import atto.protocol.vote.AttoVoteResponse
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

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    suspend fun broadcastSample() {
        val transactions = transactionRepository.getLastSample(1_000)
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

        val account = accountRepository.getByPublicKey(block.publicKey)

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

        val request = AttoVoteRequest(transaction.hash)
        networkMessagePublisher.publish(
            OutboundNetworkMessage(
                message.socketAddress,
                request
            )
        )
    }

    @EventListener
    suspend fun processVoteResponse(message: InboundNetworkMessage<AttoVoteResponse>) {
        val voteMap = message.payload.votes.groupBy({ it.hash }, { voteConverter.convert(it) })
        voteMap.forEach { hash, votes ->
            val weighter = transactionWeighterMap.computeIfPresent(hash) { _, weighter ->
                votes.filter { it.isFinal() }
                    .forEach { weighter.add(it) }
                weighter
            }

            if (weighter != null && weighter.totalFinalWeight() >= voteWeighter.getMinimalConfirmationWeight()) {
                logger.debug { "Discovered missing last transaction $hash" }
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
}