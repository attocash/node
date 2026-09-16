package cash.atto.node.election

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoBlock
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoSigner
import cash.atto.commons.AttoVote
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
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
import cash.atto.node.vote.VoteValidated
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoNode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.test.util.ReflectionTestUtils
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@ExtendWith(MockKExtension::class)
class ElectionVoterTest {
    @RelaxedMockK
    lateinit var thisNode: AttoNode

    @RelaxedMockK
    lateinit var signer: AttoSigner

    @RelaxedMockK
    lateinit var voteWeighter: VoteWeighter

    @RelaxedMockK
    lateinit var eventPublisher: EventPublisher

    @RelaxedMockK
    lateinit var messagePublisher: NetworkMessagePublisher

    @RelaxedMockK
    lateinit var account: Account

    @RelaxedMockK
    lateinit var accountRepository: AccountRepository

    @InjectMockKs
    lateinit var electionVoter: ElectionVoter

    @BeforeEach
    fun beforeEach() {
        electionVoter.clear()
        every { voteWeighter.get() } returns AttoAmount.MAX
        every { thisNode.isVoter() } returns true
        every { signer.publicKey } returns AttoPublicKey(Random.nextBytes(ByteArray(32)))
    }

    @AfterEach
    fun afterEach() {
        electionVoter.close()
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `should prepare final vote before saving and publish only after both finish`(signBeforeSave: Boolean) =
        runBlocking {
            // Given
            val transaction = Transaction.sample()
            val signingStarted = CompletableDeferred<Unit>()
            val signature = CompletableDeferred<AttoSignature>()
            coEvery { signer.sign(any<AttoVote>()) } coAnswers {
                if (firstArg<AttoVote>().isFinal()) {
                    signingStarted.complete(Unit)
                    signature.await()
                } else {
                    AttoSignature(Random.nextBytes(64))
                }
            }
            AnnotationConfigApplicationContext().use { context ->
                context.beanFactory.registerSingleton("electionVoter", electionVoter)
                context.refresh()
                val publisher = EventPublisher(context)
                publisher.publish(ElectionStarted(account, transaction))

                // When
                publisher.publish(ElectionConsensusReached(account, transaction, emptySet()))
                withTimeout(3_000) { signingStarted.await() }
                if (signBeforeSave) {
                    signature.complete(AttoSignature(Random.nextBytes(64)))
                }

                // Then
                verify(exactly = 0) {
                    messagePublisher.publish(match { (it as BroadcastNetworkMessage).strategy == BroadcastStrategy.EVERYONE })
                }
                verify(exactly = 0) {
                    eventPublisher.publish(match { it is VoteValidated && it.vote.isFinal() })
                }

                // When
                publisher.publish(AccountUpdated(TransactionSource.ELECTION, account, account, transaction))
                if (!signBeforeSave) {
                    verify(exactly = 0) {
                        messagePublisher.publish(match { (it as BroadcastNetworkMessage).strategy == BroadcastStrategy.EVERYONE })
                    }
                    signature.complete(AttoSignature(Random.nextBytes(64)))
                }

                // Then
                verify(exactly = 1, timeout = 3_000) {
                    messagePublisher.publish(match { (it as BroadcastNetworkMessage).strategy == BroadcastStrategy.EVERYONE })
                }
                verify(exactly = 1, timeout = 3_000) {
                    eventPublisher.publish(match { it is VoteValidated && it.transaction == transaction && it.vote.isFinal() })
                }
                coVerify(exactly = 1) { signer.sign(match<AttoVote> { it.isFinal() }) }
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `should cancel prepared final vote when election is discarded`(expire: Boolean) =
        runBlocking {
            // Given
            val transaction = Transaction.sample()
            val signingStarted = CompletableDeferred<Unit>()
            val signingCancelled = CompletableDeferred<Unit>()
            coEvery { signer.sign(any<AttoVote>()) } coAnswers {
                if (firstArg<AttoVote>().isFinal()) {
                    signingStarted.complete(Unit)
                    try {
                        CompletableDeferred<AttoSignature>().await()
                    } finally {
                        signingCancelled.complete(Unit)
                    }
                } else {
                    AttoSignature(Random.nextBytes(64))
                }
            }
            electionVoter.process(ElectionStarted(account, transaction))
            electionVoter.process(ElectionConsensusReached(account, transaction, emptySet()))
            withTimeout(3_000) { signingStarted.await() }

            // When
            if (expire) {
                electionVoter.process(ElectionExpired(account, transaction))
            } else {
                electionVoter.clear()
            }
            withTimeout(3_000) { signingCancelled.await() }
            electionVoter.process(AccountUpdated(TransactionSource.ELECTION, account, account, transaction))

            // Then
            verify(exactly = 0) {
                messagePublisher.publish(match { (it as BroadcastNetworkMessage).strategy == BroadcastStrategy.EVERYONE })
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["consensus", "change", "reaffirm", "expire", "save"])
    fun `should finish final voting despite delayed election handlers`(lateEvent: String) =
        runBlocking {
            // Given
            val transaction = Transaction.sample()
            val alternative = Transaction.sample()
            val signature = CompletableDeferred<AttoSignature>()
            coEvery { signer.sign(any<AttoVote>()) } coAnswers {
                if (firstArg<AttoVote>().isFinal()) {
                    signature.await()
                } else {
                    AttoSignature(Random.nextBytes(64))
                }
            }
            electionVoter.process(ElectionStarted(account, alternative))
            val saved = AccountUpdated(TransactionSource.ELECTION, account, account, transaction)

            @Suppress("UNCHECKED_CAST")
            val consensusMap = ReflectionTestUtils.getField(electionVoter, "consensusMap") as Map<PublicKeyHeight, Any>

            // When: deliver the save after a delayed handler reads the entry but before it uses it.
            val delayedLookupMap =
                object : ConcurrentHashMap<PublicKeyHeight, Any>(consensusMap) {
                    override fun get(key: PublicKeyHeight): Any? {
                        val consensus = super.get(key)
                        runBlocking { electionVoter.process(saved) }
                        return consensus
                    }
                }
            ReflectionTestUtils.setField(electionVoter, "consensusMap", delayedLookupMap)
            when (lateEvent) {
                "consensus" -> {
                    electionVoter.process(ElectionConsensusReached(account, transaction, emptySet()))
                }

                "change" -> {
                    electionVoter.process(ElectionConsensusChanged(account, transaction))
                }

                "reaffirm" -> {
                    electionVoter.process(ElectionExpiring(account, alternative))
                }

                "expire" -> {
                    electionVoter.process(ElectionExpired(account, alternative))
                }

                "save" -> {
                    electionVoter.process(saved)
                    electionVoter.process(saved)
                }
            }
            signature.complete(AttoSignature(Random.nextBytes(64)))

            // Then
            verify(exactly = 1, timeout = 3_000) {
                eventPublisher.publish(match { it is VoteValidated && it.transaction == transaction && it.vote.isFinal() })
            }
            verify(exactly = 1, timeout = 3_000) {
                messagePublisher.publish(match { (it as BroadcastNetworkMessage).strategy == BroadcastStrategy.EVERYONE })
            }
        }

    @Test
    fun `should never publish prepared final vote for a different saved transaction`() =
        runBlocking {
            // Given
            val preparedTransaction = Transaction.sample()
            val savedTransaction = Transaction.sample()
            val signingStarted = CompletableDeferred<Unit>()
            coEvery { signer.sign(any<AttoVote>()) } coAnswers {
                if (firstArg<AttoVote>().isFinal()) {
                    signingStarted.complete(Unit)
                }
                AttoSignature(Random.nextBytes(64))
            }
            electionVoter.process(ElectionStarted(account, preparedTransaction))
            electionVoter.process(ElectionConsensusReached(account, preparedTransaction, emptySet()))
            withTimeout(3_000) { signingStarted.await() }

            // When
            electionVoter.process(AccountUpdated(TransactionSource.ELECTION, account, account, savedTransaction))

            // Then
            verify(exactly = 1, timeout = 3_000) {
                eventPublisher.publish(match { it is VoteValidated && it.transaction == savedTransaction && it.vote.isFinal() })
            }
            verify(exactly = 0) {
                eventPublisher.publish(match { it is VoteValidated && it.transaction == preparedTransaction && it.vote.isFinal() })
            }
            coVerify(exactly = 1) { signer.sign(match<AttoVote> { it.isFinal() && it.blockHash == savedTransaction.hash }) }
        }

    @Test
    fun `should send vote when election starts`() {
        // given
        val transaction = Transaction.sample()
        val electionStarted = ElectionStarted(account, transaction)

        // when
        runBlocking {
            electionVoter.process(electionStarted)
        }

        // then
        verify(timeout = 3_000) {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.VOTERS
                },
            )
        }
        verify(timeout = 3_000) {
            eventPublisher.publish(
                match { event ->
                    event as VoteValidated
                    event.transaction == transaction
                },
            )
        }
    }

    @Test
    fun `should NOT send vote when consensus does NOT change`() {
        // given
        val transaction = Transaction.sample()

        // when
        runBlocking {
            electionVoter.process(ElectionStarted(account, transaction))
            electionVoter.process(ElectionConsensusChanged(account, transaction))
        }

        // then
        verify(exactly = 1, timeout = 3_000) {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.VOTERS
                },
            )
        }
        verify(exactly = 1, timeout = 3_000) {
            eventPublisher.publish(
                match { event ->
                    event as VoteValidated
                    event.transaction == transaction
                },
            )
        }
    }

    @Test
    fun `should send vote when consensus changes`() {
        // given
        val transactionA = Transaction.sample()
        val transactionB = Transaction.sample()

        // when
        runBlocking {
            electionVoter.process(ElectionStarted(account, transactionA))
            electionVoter.process(ElectionStarted(account, transactionB))
            electionVoter.process(ElectionConsensusChanged(account, transactionB))
        }

        // then
        verify(exactly = 2, timeout = 30_000) {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.VOTERS
                },
            )
        }
        verify(exactly = 1, timeout = 3_000) {
            eventPublisher.publish(
                match { event ->
                    event as VoteValidated
                    event.transaction == transactionA
                },
            )
        }
        verify(exactly = 1, timeout = 3_000) {
            eventPublisher.publish(
                match { event ->
                    event as VoteValidated
                    event.transaction == transactionB
                },
            )
        }
    }

    @Test
    fun `should send vote when election starts with older timestamp than block`() {
        // given
        val transaction = Transaction.sample()
        val olderTimestamp =
            java.time.Instant
                .now()
                .minusSeconds(10)
        val electionStarted = ElectionStarted(account, transaction, olderTimestamp)

        // when
        runBlocking {
            electionVoter.process(electionStarted)
        }

        // then — node must still vote even though election timestamp is older than block timestamp
        verify(timeout = 3_000) {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.VOTERS
                },
            )
        }
        verify(timeout = 3_000) {
            eventPublisher.publish(
                match { event ->
                    event as VoteValidated
                    event.transaction == transaction
                },
            )
        }
    }

    @Test
    fun `should NOT overwrite newer consensus with older one for different transaction`() {
        // given
        val transactionNewer = Transaction.sample()
        val transactionOlder = Transaction.sample()

        val base = java.time.Instant.now()
        val newerTimestamp = base.plusSeconds(10)
        val olderTimestamp = base.minusSeconds(10)

        // when
        runBlocking {
            // First, consensus on newer transaction
            electionVoter.process(ElectionStarted(account, transactionNewer, newerTimestamp))

            // Then, an older consensus attempt for a different transaction must be ignored
            electionVoter.process(ElectionConsensusChanged(account, transactionOlder, olderTimestamp))
        }

        // then
        verify(exactly = 1, timeout = 3_000) {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.VOTERS
                },
            )
        }
        verify(exactly = 1, timeout = 3_000) {
            eventPublisher.publish(
                match { event ->
                    event as VoteValidated
                    event.transaction == transactionNewer
                },
            )
        }
        verify(exactly = 0, timeout = 3_000) {
            eventPublisher.publish(
                match { event ->
                    event as VoteValidated
                    event.transaction == transactionOlder
                },
            )
        }
    }

    @Test
    fun `should NOT send additional vote when consensus reached`() {
        // given
        val transaction = Transaction.sample()

        // when
        runBlocking {
            electionVoter.process(ElectionStarted(account, transaction))
            electionVoter.process(ElectionConsensusReached(account, transaction, emptySet()))
        }

        // then — only the initial vote from ElectionStarted, no extra vote from ConsensusReached
        verify(exactly = 1, timeout = 3_000) {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.VOTERS
                },
            )
        }
        verify(exactly = 1, timeout = 3_000) {
            eventPublisher.publish(
                match { event ->
                    event as VoteValidated
                    event.transaction == transaction
                },
            )
        }
    }

    @Test
    fun `should send only vote and final vote in election flow`() {
        // given
        val transaction = Transaction.sample()

        // when
        runBlocking {
            electionVoter.process(ElectionStarted(account, transaction))
            electionVoter.process(ElectionConsensusReached(account, transaction, emptySet()))
        }

        // then
        verify(exactly = 1, timeout = 3_000) {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.VOTERS
                },
            )
        }

        // when
        runBlocking {
            electionVoter.process(AccountUpdated(TransactionSource.ELECTION, account, account, transaction))
        }

        // then
        verify(exactly = 1, timeout = 3_000) {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.EVERYONE
                },
            )
        }
    }

    @Test
    fun `should send final vote when account is updated`() {
        // given
        val transaction = Transaction.sample()

        // when
        runBlocking {
            electionVoter.process(ElectionStarted(account, transaction))
        }

        // then
        verify(exactly = 1, timeout = 3_000) {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.VOTERS
                },
            )
        }

        // when
        runBlocking {
            electionVoter.process(AccountUpdated(TransactionSource.ELECTION, account, account, transaction))
        }

        // then
        verify(exactly = 1, timeout = 3_000) {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.EVERYONE
                },
            )
        }
        verify(timeout = 3_000) {
            eventPublisher.publish(
                match { event ->
                    event as VoteValidated
                    event.transaction == transaction
                },
            )
        }
    }

    @Test
    fun `should send final vote when receive a old transaction`() {
        // given
        val transaction = Transaction.sample()
        every { account.publicKey } returns transaction.publicKey
        every { account.lastTransactionHash } returns transaction.hash
        coEvery { accountRepository.findById(transaction.publicKey) } returns account

        // when
        runBlocking {
            electionVoter.process(ElectionStarted(account, transaction))
        }

        // then — initial vote (VOTERS)
        verify(exactly = 1, timeout = 3_000) {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.VOTERS
                },
            )
        }

        // when
        runBlocking {
            electionVoter.process(TransactionRejected(TransactionRejectionReason.OLD_TRANSACTION, "Test", account, transaction))
        }

        // then — final vote (EVERYONE)
        verify(exactly = 1, timeout = 3_000) {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.EVERYONE
                },
            )
        }
    }

    private fun AttoBlock.Companion.sample(): AttoBlock =
        AttoReceiveBlock(
            version = 0U.toAttoVersion(),
            network = AttoNetwork.LOCAL,
            algorithm = AttoAlgorithm.V1,
            publicKey = signer.publicKey,
            height = 2U.toAttoHeight(),
            balance = AttoAmount.MAX,
            timestamp = AttoInstant.now(),
            previous = AttoHash(Random.nextBytes(ByteArray(32))),
            sendHashAlgorithm = AttoAlgorithm.V1,
            sendHash = AttoHash(Random.nextBytes(ByteArray(32))),
        )

    private fun Transaction.Companion.sample(): Transaction =
        Transaction(
            AttoBlock.sample(),
            AttoSignature(Random.nextBytes(ByteArray(64))),
            AttoWork(Random.nextBytes(ByteArray(8))),
        )
}
