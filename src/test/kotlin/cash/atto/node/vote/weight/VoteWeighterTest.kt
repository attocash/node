package cash.atto.node.vote.weight

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoBlock
import cash.atto.commons.AttoChangeBlock
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoVersion
import cash.atto.node.account.Account
import cash.atto.node.account.AccountUpdated
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteValidated
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import kotlin.random.Random

class VoteWeighterTest {
    @Test
    fun `should load weights with latest vote timestamps`() {
        val publicKey = randomPublicKey()
        val timestamp = Instant.now()
        val voteWeighter =
            voteWeighter(
                Weight(
                    representativePublicKey = publicKey,
                    weight = AttoAmount(100UL),
                    lastVoteTimestamp = timestamp,
                ),
            )

        voteWeighter.init()

        assertEquals(AttoAmount(100UL), voteWeighter.get(publicKey))
        assertEquals(timestamp, voteWeighter.getLatestVoteTimestamp(publicKey))
    }

    @Test
    fun `should keep newest validated vote timestamp in cache`() {
        val publicKey = randomPublicKey()
        val existingTimestamp = Instant.now().minusSeconds(10)
        val newerTimestamp = Instant.now()
        val olderTimestamp = existingTimestamp.minusSeconds(10)
        val voteWeighter =
            voteWeighter(
                Weight(
                    representativePublicKey = publicKey,
                    weight = AttoAmount(100UL),
                    lastVoteTimestamp = existingTimestamp,
                ),
            )
        voteWeighter.init()

        voteWeighter.listen(VoteValidated(Transaction.sample(), Vote.sample(publicKey, newerTimestamp)))
        voteWeighter.listen(VoteValidated(Transaction.sample(), Vote.sample(publicKey, olderTimestamp)))

        assertEquals(newerTimestamp, voteWeighter.getLatestVoteTimestamp(publicKey))
        assertEquals(AttoAmount(100UL), voteWeighter.get(publicKey))
    }

    @Test
    fun `should use epoch as latest vote timestamp for unknown representative`() {
        val voteWeighter = voteWeighter()
        val publicKey = randomPublicKey()
        voteWeighter.init()

        assertEquals(Weight.NEVER_VOTED_AT, voteWeighter.getLatestVoteTimestamp(publicKey))
    }

    @Test
    fun `should retry change until previous representative weight is available`() =
        runTest {
            // Given
            val representativeA = randomPublicKey()
            val representativeB = randomPublicKey()
            val representativeC = randomPublicKey()
            val balance = AttoAmount(100UL)
            val voteWeighter = voteWeighter(Weight(representativeA, balance))
            voteWeighter.init()
            val firstChange = change(representativeA, representativeB, balance)
            val secondChange = change(representativeB, representativeC, balance)

            // When
            val pending =
                async(start = CoroutineStart.UNDISPATCHED) {
                    voteWeighter.listen(secondChange)
                }

            // Then
            assertFalse(pending.isCompleted)

            // When
            voteWeighter.listen(firstChange)
            pending.await()

            // Then
            assertEquals(AttoAmount.MIN, voteWeighter.get(representativeA))
            assertEquals(AttoAmount.MIN, voteWeighter.get(representativeB))
            assertEquals(balance, voteWeighter.get(representativeC))
        }

    @Test
    fun `should retry receive until previous balance is available`() =
        runTest {
            // Given
            val representative = randomPublicKey()
            val voteWeighter = voteWeighter()
            voteWeighter.init()
            val firstReceive = receive(representative, AttoAmount.MIN, AttoAmount(100UL))
            val secondReceive = receive(representative, AttoAmount(100UL), AttoAmount(150UL))

            // When
            val pending =
                async(start = CoroutineStart.UNDISPATCHED) {
                    voteWeighter.listen(secondReceive)
                }

            // Then
            assertFalse(pending.isCompleted)

            // When
            voteWeighter.listen(firstReceive)
            pending.await()

            // Then
            assertEquals(AttoAmount(150UL), voteWeighter.get(representative))
        }

    private fun voteWeighter(vararg weights: Weight): VoteWeighter {
        val weightService = mockk<WeightService>()
        coEvery { weightService.refresh() } returns weights.toList().asFlow()
        return VoteWeighter(
            thisNode = sampleNode(),
            properties =
                VoteWeightProperties().apply {
                    minimalConfirmationWeight = "1"
                    confirmationThreshold = 65
                    minimalRebroadcastWeight = "1"
                    samplePeriodInDays = 1
                },
            weightService = weightService,
            genesisTransaction = Transaction.sample(),
        )
    }

    private fun sampleNode(): AttoNode =
        AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0U.toUShort(),
            algorithm = AttoAlgorithm.V1,
            publicKey = randomPublicKey(),
            publicUri = URI("ws://127.0.0.1:8081"),
            features = setOf(NodeFeature.VOTING),
        )

    private fun change(
        previousRepresentative: AttoPublicKey,
        representative: AttoPublicKey,
        balance: AttoAmount,
    ): AccountUpdated {
        val block = mockk<AttoChangeBlock>()
        every { block.representativePublicKey } returns representative
        every { block.balance } returns balance
        return accountUpdated(block, previousRepresentative, representative, balance)
    }

    private fun receive(
        representative: AttoPublicKey,
        previousBalance: AttoAmount,
        balance: AttoAmount,
    ): AccountUpdated {
        val block = mockk<AttoReceiveBlock>()
        every { block.balance } returns balance
        return accountUpdated(block, representative, representative, previousBalance)
    }

    private fun accountUpdated(
        block: AttoBlock,
        previousRepresentative: AttoPublicKey,
        representative: AttoPublicKey,
        previousBalance: AttoAmount,
    ): AccountUpdated {
        val previousAccount = mockk<Account>()
        every { previousAccount.representativePublicKey } returns previousRepresentative
        every { previousAccount.balance } returns previousBalance
        val updatedAccount = mockk<Account>()
        every { updatedAccount.representativePublicKey } returns representative
        val transaction = mockk<Transaction>()
        every { transaction.block } returns block
        return AccountUpdated(TransactionSource.BOOTSTRAP, previousAccount, updatedAccount, transaction)
    }

    private fun Transaction.Companion.sample(): Transaction =
        Transaction(
            AttoReceiveBlock(
                version = 0U.toAttoVersion(),
                network = AttoNetwork.LOCAL,
                algorithm = AttoAlgorithm.V1,
                publicKey = randomPublicKey(),
                height = AttoHeight(2UL),
                balance = AttoAmount.MAX,
                timestamp = AttoInstant.now(),
                previous = AttoHash(Random.nextBytes(ByteArray(32))),
                sendHashAlgorithm = AttoAlgorithm.V1,
                sendHash = AttoHash(Random.nextBytes(ByteArray(32))),
            ),
            AttoSignature(Random.nextBytes(ByteArray(64))),
            AttoWork(Random.nextBytes(ByteArray(8))),
        )

    private fun Vote.Companion.sample(
        publicKey: AttoPublicKey,
        receivedAt: Instant,
    ): Vote =
        Vote(
            hash = AttoHash(Random.nextBytes(ByteArray(32))),
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = publicKey,
            blockAlgorithm = AttoAlgorithm.V1,
            blockHash = AttoHash(Random.nextBytes(ByteArray(32))),
            timestamp = Instant.now(),
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            weight = AttoAmount(100UL),
            receivedAt = receivedAt,
        )

    private fun randomPublicKey(): AttoPublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
}
