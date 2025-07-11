package cash.atto.node.election

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoBlock
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoSigner
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
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejected
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.vote.VoteValidated
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoNode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
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
        every { voteWeighter.get() } returns AttoAmount.MAX
        every { thisNode.isVoter() } returns true
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
        verify {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.VOTERS
                },
            )
        }
        verify {
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
        verify(exactly = 1) {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.VOTERS
                },
            )
        }
        verify(exactly = 1) {
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
        verify(exactly = 2, timeout = 10_000) {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.VOTERS
                },
            )
        }
        verify(exactly = 1) {
            eventPublisher.publish(
                match { event ->
                    event as VoteValidated
                    event.transaction == transactionA
                },
            )
        }
        verify(exactly = 1) {
            eventPublisher.publish(
                match { event ->
                    event as VoteValidated
                    event.transaction == transactionB
                },
            )
        }
    }

    @Test
    fun `should send final vote when consensus reached`() {
        // given
        val transaction = Transaction.sample()

        // when
        runBlocking {
            electionVoter.process(ElectionConsensusReached(account, transaction, emptySet()))
        }

        // then
        verify(exactly = 1) {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.VOTERS
                },
            )
        }
        verify(exactly = 1) {
            eventPublisher.publish(
                match { event ->
                    event as VoteValidated
                    event.transaction == transaction
                },
            )
        }
    }

    @Test
    fun `should send final vote when account is updated`() {
        // given
        val transaction = Transaction.sample()
        val accountUpdated = AccountUpdated(TransactionSource.ELECTION, account, account, transaction)

        // when
        runBlocking {
            electionVoter.process(accountUpdated)
        }

        // then
        verify {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.EVERYONE
                },
            )
        }
        verify {
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
            electionVoter.process(TransactionRejected(TransactionRejectionReason.OLD_TRANSACTION, "Test", account, transaction))
        }

        // then
        verify(exactly = 1) {
            messagePublisher.publish(
                match { message ->
                    message as BroadcastNetworkMessage
                    message.strategy == BroadcastStrategy.EVERYONE
                },
            )
        }
        verify(exactly = 1) {
            eventPublisher.publish(
                match { event ->
                    event as VoteValidated
                    event.transaction == transaction
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
            timestamp = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()),
            previous = AttoHash(Random.nextBytes(ByteArray(32))),
            sendHashAlgorithm = AttoAlgorithm.V1,
            sendHash = AttoHash(Random.Default.nextBytes(ByteArray(32))),
        )

    private fun Transaction.Companion.sample(): Transaction =
        Transaction(
            AttoBlock.sample(),
            AttoSignature(Random.nextBytes(ByteArray(64))),
            AttoWork(Random.nextBytes(ByteArray(8))),
        )
}
