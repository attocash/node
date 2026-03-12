package cash.atto.node.election

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoSignedVote
import cash.atto.commons.AttoSigner
import cash.atto.commons.AttoUnit
import cash.atto.commons.AttoVote
import cash.atto.commons.toAtto
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toJavaInstant
import cash.atto.node.CacheSupport
import cash.atto.node.EventPublisher
import cash.atto.node.account.AccountRepository
import cash.atto.node.account.AccountUpdated
import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.BroadcastStrategy
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.PublicKeyHeight
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejected
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteValidated
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoVotePush
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random

@Service
class ElectionVoter(
    private val thisNode: AttoNode,
    private val signer: AttoSigner,
    private val voteWeighter: VoteWeighter,
    private val eventPublisher: EventPublisher,
    private val messagePublisher: NetworkMessagePublisher,
    private val accountRepository: AccountRepository,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    companion object {
        val MIN_WEIGHT = AttoAmount.from(AttoUnit.ATTO, BigDecimal.valueOf(1_000_000).toString()) // 1M
    }

    private val scope = CoroutineScope(Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher() + SupervisorJob())

    private val consensusMap = ConcurrentHashMap<PublicKeyHeight, Consensus>()

    override fun clear() {
        consensusMap.clear()
    }

    @PreDestroy
    fun close() {
        logger.info { "Election Voter is stopping..." }
        clear()
        scope.cancel()
    }

    private fun consensusFor(transaction: Transaction): Consensus? = consensusMap[transaction.toPublicKeyHeight()]

    @EventListener
    suspend fun process(event: ElectionStarted) {
        val transaction = event.transaction
        val publicKeyHeight = transaction.toPublicKeyHeight()
        val consensus = consensusMap.computeIfAbsent(publicKeyHeight) { Consensus(transaction) }
        consensus.start(event.timestamp)
    }

    @EventListener
    suspend fun process(event: ElectionConsensusChanged) {
        val consensus = consensusFor(event.transaction) ?: return
        consensus.update(event.transaction, event.timestamp)
    }

    @EventListener
    suspend fun process(event: ElectionConsensusReached) {
        val consensus = consensusFor(event.transaction) ?: return
        consensus.update(event.transaction, event.timestamp)
    }

    @EventListener
    suspend fun process(event: ElectionExpiring) {
        val consensus = consensusFor(event.transaction) ?: return
        consensus.reaffirm()
    }

    @EventListener
    suspend fun process(event: ElectionExpired) {
        val consensus = consensusFor(event.transaction) ?: return
        consensus.expire()
    }

    @EventListener
    suspend fun process(event: AccountUpdated) {
        if (event.source != TransactionSource.ELECTION) {
            return
        }

        val consensus = consensusFor(event.transaction) ?: return
        consensus.finalVote(event.transaction)
    }

    @EventListener
    suspend fun process(event: TransactionRejected) {
        if (event.reason != TransactionRejectionReason.OLD_TRANSACTION) {
            return
        }

        val account = accountRepository.findById(event.transaction.publicKey)
        if (account?.lastTransactionHash != event.transaction.hash) {
            return
        }

        val consensus = Consensus(event.transaction)
        consensus.finalVote(event.transaction)
    }

    private fun canVote(weight: AttoAmount): Boolean = thisNode.isVoter() && weight >= MIN_WEIGHT

    @OptIn(ExperimentalAtomicApi::class)
    private inner class Consensus(
        private var transaction: Transaction,
    ) {
        private val mutex = Mutex()
        private val started = AtomicBoolean(false)
        private val publicKeyHeight = transaction.toPublicKeyHeight()
        private var consensusTimestamp = transaction.block.timestamp.toJavaInstant()
        private var job: Job? = null

        suspend fun start(timestamp: Instant) =
            mutex.withLock {
                if (!started.compareAndSet(expectedValue = false, newValue = true)) {
                    return@withLock
                }
                applyConsensus(transaction, timestamp, forceVote = true)
            }

        private fun remove() {
            job?.cancel()
            if (consensusMap.remove(publicKeyHeight) != null) {
                logger.trace { "Removed ${transaction.hash} from the voter" }
            }
        }

        suspend fun update(
            transaction: Transaction,
            timestamp: Instant,
        ) = mutex.withLock {
            applyConsensus(transaction, timestamp)
        }

        suspend fun reaffirm() =
            mutex.withLock {
                publishVote(transaction, Instant.now())
            }

        suspend fun finalVote(transaction: Transaction) =
            mutex.withLock {
                publishVote(transaction, AttoVote.finalTimestamp.toJavaInstant())
                remove()
            }

        suspend fun expire() =
            mutex.withLock {
                remove()
            }

        private suspend fun publishVote(
            transaction: Transaction,
            timestamp: Instant,
            consensusChanged: Boolean = false,
        ) {
            val weight = voteWeighter.get()
            if (!canVote(weight)) {
                logger.trace { "This node can't vote yet" }
                return
            }

            job?.cancel()
            val newJob =
                scope.launch {
                    if (consensusChanged) {
                        val baseDelay = Election.ELECTION_STABILITY_MINIMAL_TIME.toMillis()
                        /*
                         * Extra delay spreads votes across a 2s window so that not all nodes
                         * cast their votes at the exact same instant, reducing the chance of
                         * a race condition where simultaneous votes could cause more
                         * consensus flips.
                         */
                        val extraDelay = Random.nextLong(0, 2001)
                        delay(baseDelay + extraDelay)
                    }

                    val attoVote =
                        AttoVote(
                            version = 0U.toAttoVersion(),
                            algorithm = thisNode.algorithm,
                            publicKey = thisNode.publicKey,
                            blockAlgorithm = transaction.algorithm,
                            blockHash = transaction.hash,
                            timestamp = timestamp.toAtto(),
                        )
                    val attoSignedVote =
                        AttoSignedVote(
                            vote = attoVote,
                            signature = signer.sign(attoVote),
                        )

                    val votePush =
                        AttoVotePush(
                            vote = attoSignedVote,
                        )

                    val strategy =
                        if (attoVote.isFinal()) {
                            BroadcastStrategy.EVERYONE
                        } else {
                            BroadcastStrategy.VOTERS
                        }

                    logger.debug { "Sending to $strategy $votePush" }

                    messagePublisher.publish(BroadcastNetworkMessage(strategy, emptySet(), votePush))
                    eventPublisher.publish(VoteValidated(transaction, Vote.from(weight, attoSignedVote)))
                }
            job = newJob
            newJob.join()
        }

        private suspend fun applyConsensus(
            transaction: Transaction,
            timestamp: Instant,
            forceVote: Boolean = false,
        ) {
            val oldTransaction = this.transaction
            val oldTimestamp = this.consensusTimestamp

            if (oldTimestamp >= timestamp) {
                return
            }

            this.transaction = transaction
            this.consensusTimestamp = timestamp

            if (!forceVote && oldTransaction == transaction) {
                return
            }

            logger.trace { "Consensus changed from ${oldTransaction.hash} to ${transaction.hash}" }

            publishVote(transaction, timestamp, consensusChanged = oldTransaction != transaction)
        }
    }
}
