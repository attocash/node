package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoHash
import cash.atto.node.EventPublisher
import cash.atto.node.account.AccountRepository
import cash.atto.node.account.getByAlgorithmAndPublicKey
import cash.atto.node.bootstrap.TransactionDiscovered
import cash.atto.node.election.ElectionVoter
import cash.atto.node.election.TransactionElection
import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.BroadcastStrategy
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.TransactionRepository
import cash.atto.node.transaction.toTransaction
import cash.atto.node.vote.convertion.VoteConverter
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoBootstrapTransactionPush
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoVoteStreamCancel
import cash.atto.protocol.AttoVoteStreamRequest
import cash.atto.protocol.AttoVoteStreamResponse
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.map
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class LastDiscoverer(
    private val thisNode: AttoNode,
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val networkMessagePublisher: NetworkMessagePublisher,
    private val eventPublisher: EventPublisher,
    private val voteConverter: VoteConverter,
    private val voteWeighter: VoteWeighter,
) {
    private val logger = KotlinLogging.logger {}

    private val transactionElectionMap =
        Caffeine
            .newBuilder()
            .maximumSize(100_000)
            .build<AttoHash, TransactionElection>()
            .asMap()

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    suspend fun broadcastSample() {
        if (thisNode.isNotHistorical()) {
            return
        }

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

        logger.trace { "Received bootstrap head transaction ${transaction.hash}" }

        val account = accountRepository.getByAlgorithmAndPublicKey(block.algorithm, block.publicKey, block.network)

        if (account.height.toULong() >= block.height.value) {
            logger.trace {
                "Received ${transaction.hash} with height ${transaction.height} however ${account.publicKey} " +
                    "account already has ${account.height}"
            }
            return
        }

        transactionElectionMap.computeIfAbsent(transaction.hash) {
            val election =
                TransactionElection(transaction) {
                    voteWeighter.getMinimalConfirmationWeight()
                }

            val request = AttoVoteStreamRequest(transaction.hash)
            networkMessagePublisher.publish(
                DirectNetworkMessage(
                    message.publicUri,
                    request,
                ),
            )

            logger.trace { "Bootstrap election for ${transaction.hash} started" }

            election
        }
    }

    @EventListener
    suspend fun processVoteResponse(message: InboundNetworkMessage<AttoVoteStreamResponse>) {
        val blockHash = message.payload.blockHash
        val vote = voteConverter.convert(blockHash, message.payload.vote)

        logger.trace { "Received bootstrap $vote" }

        if (vote.weight < ElectionVoter.MIN_WEIGHT) {
            logger.trace { "Vote below ${ElectionVoter.MIN_WEIGHT} min vote weight" }

            return
        }

        transactionElectionMap.computeIfPresent(blockHash) { _, election ->
            election.add(vote)
            if (election.isConsensusReached()) {
                logger.debug { "Discovered missing last transaction $blockHash" }

                val request = AttoVoteStreamCancel(blockHash)
                networkMessagePublisher.publish(DirectNetworkMessage(message.publicUri, request))
                eventPublisher.publish(TransactionDiscovered(null, election.transaction, election.votes.values))

                null
            } else {
                logger.trace { "Consensus not reached yet. Added to election queue" }

                election
            }
        }
    }
}
