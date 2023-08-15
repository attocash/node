package atto.node.bootstrap.discovery

import atto.node.account.AccountRepository
import atto.node.network.*
import atto.node.transaction.TransactionRepository
import atto.node.transaction.toTransaction
import atto.node.vote.convertion.VoteConverter
import atto.protocol.bootstrap.AttoBootstrapTransactionPush
import atto.protocol.vote.AttoVoteRequest
import atto.protocol.vote.AttoVoteResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class LastDiscoverer(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val networkMessagePublisher: NetworkMessagePublisher,
    private val dependencyDiscoverer: DependencyDiscoverer,
    private val voteConverter: VoteConverter,
) {
    private val ioScope = CoroutineScope(Dispatchers.IO + CoroutineName(this.javaClass.simpleName))

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    fun broadcastSample() {
        ioScope.launch {
            val transactions = transactionRepository.getLastSample(1_000)
            transactions
                .map { AttoBootstrapTransactionPush(it.toAttoTransaction()) }
                .map { BroadcastNetworkMessage(BroadcastStrategy.EVERYONE, setOf(), it) }
                .collect { networkMessagePublisher.publish(it) }
        }
    }

    @EventListener
    @Async
    fun processPush(message: InboundNetworkMessage<AttoBootstrapTransactionPush>) {
        val response = message.payload
        val transaction = response.transaction
        val block = transaction.block

        ioScope.launch {
            val account = accountRepository.getByPublicKey(block.publicKey)

            if (account.height >= block.height) {
                return@launch
            }

            dependencyDiscoverer.add(null, transaction.toTransaction())

            val request = AttoVoteRequest(transaction.hash)
            networkMessagePublisher.publish(
                OutboundNetworkMessage(
                    message.socketAddress,
                    request
                )
            )
        }
    }

    @EventListener
    @Async
    fun processVoteResponse(message: InboundNetworkMessage<AttoVoteResponse>) = runBlocking {
        val attoVotes = message.payload.votes
        attoVotes.asSequence()
            .map { voteConverter.convert(it) }
            .forEach { dependencyDiscoverer.process(it) }
    }
}