package org.atto.node.vote.validator

import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.atto.commons.*
import org.atto.node.EventPublisher
import org.atto.node.network.BroadcastNetworkMessage
import org.atto.node.network.BroadcastStrategy
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.transaction.TransactionConfirmed
import org.atto.node.transaction.TransactionObserved
import org.atto.node.vote.HashVoteRejected
import org.atto.node.vote.HashVoteValidated
import org.atto.node.vote.VoteRejectionReasons
import org.atto.node.vote.weight.VoteWeightService
import org.atto.protocol.Node
import org.atto.protocol.NodeFeature
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.transaction.TransactionStatus
import org.atto.protocol.vote.HashVote
import org.atto.protocol.vote.Vote
import org.atto.protocol.vote.VotePush
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.random.Random

@ExtendWith(MockKExtension::class)
internal class VoteValidatorTest {
    private val defaultTimeout = 200L

    private val transaction = createTransaction()

    @MockK
    lateinit var properties: VoteValidatorProperties

    @MockK
    lateinit var voteWeightService: VoteWeightService

    @RelaxedMockK
    lateinit var eventPublisher: EventPublisher

    @RelaxedMockK
    lateinit var messagePublisher: NetworkMessagePublisher

    lateinit var voteValidator: VoteValidator

    @BeforeEach
    fun start() {
        every { properties.groupMaxSize } returns 1_000
        every { properties.cacheMaxSize } returns 20_000
        every { properties.cacheExpirationTimeInSeconds } returns 60

        voteValidator = VoteValidator(
            properties,
            CoroutineScope(Dispatchers.Default),
            voteWeightService,
            thisNode,
            eventPublisher,
            messagePublisher
        )
        voteValidator.start()

        features.clear()
    }

    @AfterEach
    fun stop() {
        voteValidator.stop()
    }

    @Test
    fun `should reject when vote is invalid`() {
        // given
        every { voteWeightService.get(thisNode.publicKey) } returns 1_000UL
        val vote = Vote(
            publicKey = thisNode.publicKey,
            timestamp = Instant.now(),
            signature = AttoSignature(Random.nextBytes(ByteArray(64)))
        )
        val hashVote = HashVote(
            hash = transaction.hash,
            vote = vote,
            receivedTimestamp = Instant.now()
        )

        // when
        voteValidator.add(InboundNetworkMessage(thisNode.socketAddress, this, VotePush(hashVote)))

        // then
        verify(timeout = defaultTimeout) {
            eventPublisher.publish(
                HashVoteRejected(
                    thisNode.socketAddress,
                    VoteRejectionReasons.INVALID_VOTE,
                    hashVote
                )
            )
        }
    }

    @Test
    fun `should reject when node has no voting weight`() {
        // given
        every { voteWeightService.get(thisNode.publicKey) } returns 0UL
        val hashVote = createHashVote(transaction.hash, Instant.now())

        // when
        voteValidator.process(TransactionConfirmed(transaction))
        voteValidator.add(InboundNetworkMessage(thisNode.socketAddress, this, VotePush(hashVote)))

        // then
        verify(timeout = defaultTimeout) {
            eventPublisher.publish(
                HashVoteRejected(
                    thisNode.socketAddress,
                    VoteRejectionReasons.INVALID_VOTING_WEIGHT,
                    hashVote
                )
            )
        }
    }

    @Test
    fun `should not broadcast when this node is not voting node`() {
        // given
        every { voteWeightService.get(thisNode.publicKey) } returns 1UL
        val hashVote = createHashVote(transaction.hash, Instant.now())

        // when
        voteValidator.process(TransactionObserved(transaction))
        voteValidator.add(InboundNetworkMessage(thisNode.socketAddress, this, VotePush(hashVote)))

        // then
        verify(timeout = defaultTimeout) {
            eventPublisher.publish(HashVoteValidated(hashVote))
        }
        verify(exactly = 0) {
            messagePublisher.publish(any<BroadcastNetworkMessage<*>>())
        }
    }

    @Test
    fun `should not broadcast when vote is below minimal weight`() {
        // given
        features.add(NodeFeature.VOTING)
        every { voteWeightService.get(thisNode.publicKey) } returns 1UL
        every { voteWeightService.isAboveMinimalRebroadcastWeight(thisNode.publicKey) } returns false
        val hashVote = createHashVote(transaction.hash, Instant.now())

        // when
        voteValidator.process(TransactionObserved(transaction))
        voteValidator.add(InboundNetworkMessage(thisNode.socketAddress, this, VotePush(hashVote)))

        // then
        verify(timeout = defaultTimeout) {
            eventPublisher.publish(HashVoteValidated(hashVote))
        }
        verify(exactly = 0) {
            messagePublisher.publish(any<BroadcastNetworkMessage<*>>())
        }
    }

    @Test
    fun `should not broadcast when this node is below minimal weight`() {
        // given
        features.add(NodeFeature.VOTING)
        every { voteWeightService.get(thisNode.publicKey) } returns 1UL
        every { voteWeightService.isAboveMinimalRebroadcastWeight(thisNode.publicKey) } returnsMany listOf(true, false)
        val hashVote = createHashVote(transaction.hash, Instant.now())

        // when
        voteValidator.process(TransactionObserved(transaction))
        voteValidator.add(InboundNetworkMessage(thisNode.socketAddress, this, VotePush(hashVote)))

        // then
        verify(timeout = defaultTimeout) {
            eventPublisher.publish(HashVoteValidated(hashVote))
        }
        verify(exactly = 0) {
            messagePublisher.publish(any<BroadcastNetworkMessage<*>>())
        }
    }

    @Test
    fun `should broadcast to EVERYONE when vote is final`() {
        // given
        features.add(NodeFeature.VOTING)
        every { voteWeightService.get(thisNode.publicKey) } returns 1UL
        every { voteWeightService.isAboveMinimalRebroadcastWeight(thisNode.publicKey) } returns true
        val hashVote = createHashVote(transaction.hash, Vote.finalTimestamp)

        // when
        voteValidator.process(TransactionObserved(transaction))
        voteValidator.add(InboundNetworkMessage(thisNode.socketAddress, this, VotePush(hashVote)))

        // then
        verify(timeout = defaultTimeout) {
            eventPublisher.publish(HashVoteValidated(hashVote))
        }
        verify {
            messagePublisher.publish(
                BroadcastNetworkMessage(
                    BroadcastStrategy.EVERYONE,
                    setOf(thisNode.socketAddress),
                    voteValidator,
                    VotePush(hashVote)
                )
            )
        }
    }

    @Test
    fun `should broadcast to VOTERS when vote is not final`() {
        // given
        features.add(NodeFeature.VOTING)
        every { voteWeightService.get(thisNode.publicKey) } returns 1UL
        every { voteWeightService.isAboveMinimalRebroadcastWeight(thisNode.publicKey) } returns true
        val hashVote = createHashVote(transaction.hash, Instant.now())

        // when
        voteValidator.process(TransactionObserved(transaction))
        voteValidator.add(InboundNetworkMessage(thisNode.socketAddress, this, VotePush(hashVote)))

        // then
        verify(timeout = defaultTimeout) {
            eventPublisher.publish(HashVoteValidated(hashVote))
        }
        verify {
            messagePublisher.publish(
                BroadcastNetworkMessage(
                    BroadcastStrategy.VOTERS,
                    setOf(thisNode.socketAddress),
                    voteValidator,
                    VotePush(hashVote)
                )
            )
        }
    }

    companion object {
        val seed = AttoSeed("1234567890123456789012345678901234567890123456789012345678901234".fromHexToByteArray())
        val privateKey = seed.toPrivateKey(0u)
        val features = HashSet<NodeFeature>()

        val thisNode = Node(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0u,
            minimalProtocolVersion = 0u,
            publicKey = privateKey.toPublicKey(),
            socketAddress = InetSocketAddress(InetAddress.getLocalHost(), 8330),
            features = features
        )

        fun createHashVote(hash: AttoHash, timestamp: Instant): HashVote {
            val hashedVote = AttoHashes.hash(32, hash.value + timestamp.toByteArray())
            val signature = privateKey.sign(hashedVote)
            val vote = Vote(
                timestamp = timestamp,
                publicKey = thisNode.publicKey,
                signature = signature
            )
            return HashVote(
                hash = hash,
                vote = vote,
                receivedTimestamp = Instant.now()
            )
        }

        fun createTransaction(): Transaction {
            val block = AttoBlock(
                type = AttoBlockType.RECEIVE,
                version = 0u,
                publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
                height = 1u,
                previous = AttoHash(Random.nextBytes(ByteArray(32))),
                representative = AttoPublicKey(Random.nextBytes(ByteArray(32))),
                link = AttoLink.from(AttoHash(ByteArray(32))),
                balance = AttoAmount(1u),
                amount = AttoAmount(1u),
                timestamp = Instant.now()
            )
            return Transaction(
                block = block,
                signature = AttoSignature(Random.nextBytes(ByteArray(64))),
                work = AttoWork(Random.nextBytes(ByteArray(8))),
                hash = AttoHash(Random.nextBytes(ByteArray(32))),
                status = TransactionStatus.RECEIVED,
                receivedTimestamp = Instant.now()
            )
        }
    }
}