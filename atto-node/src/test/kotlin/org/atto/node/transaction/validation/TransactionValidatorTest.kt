package org.atto.node.transaction.validation

import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.junit5.MockKExtension
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.atto.commons.*
import org.atto.node.EventPublisher
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.transaction.*
import org.atto.protocol.Node
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.transaction.TransactionPush
import org.atto.protocol.transaction.TransactionStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import java.util.stream.Stream
import kotlin.random.Random


@ExtendWith(MockKExtension::class)
internal class TransactionValidatorTest {
    private val defaultTimeout = 200L

    @MockK
    lateinit var properties: TransactionValidatorProperties

    @RelaxedMockK
    lateinit var eventPublisher: EventPublisher

    @RelaxedMockK
    lateinit var messagePublisher: NetworkMessagePublisher

    val transactionProperties = TransactionProperties().apply {
        this.cacheMaxSize = 0
        this.cacheExpirationTimeInSeconds = 1
    }

    val transactionRepository = MockTransactionRepository(transactionProperties)

    lateinit var transactionValidator: TransactionValidator


    @BeforeEach
    fun start() {
        every { properties.groupMaxSize } returns 1_000
        every { properties.cacheMaxSize } returns 20_000
        every { properties.cacheExpirationTimeInSeconds } returns 60

        transactionValidator = TransactionValidator(
            properties,
            CoroutineScope(Dispatchers.Default),
            thisNode,
            eventPublisher,
            messagePublisher,
            transactionRepository
        )
        transactionValidator.start()
    }

    @AfterEach
    fun stop() {
        transactionValidator.stop()
    }

    @ParameterizedTest
    @MethodSource("validTransactionProvider")
    fun `should publish TransactionValidate when transaction is valid`(
        existingTransactions: List<Transaction>,
        receivedTransaction: Transaction
    ) {
        // given
        existingTransactions.asSequence()
            .filter { it.status == TransactionStatus.CONFIRMED }
            .forEach { runBlocking { transactionRepository.save(it) } }

        // when
        transactionValidator.add(
            InboundNetworkMessage(
                thisNode.socketAddress,
                this,
                TransactionPush(receivedTransaction)
            )
        )

        // then
        verify(timeout = defaultTimeout) {
            eventPublisher.publish(TransactionValidated(receivedTransaction.copy(status = TransactionStatus.VALIDATED)))
        }
    }

    @ParameterizedTest
    @MethodSource("invalidTransactionProvider")
    fun `should publish TransactionRejected when transaction is invalid`(
        existingTransactions: List<Transaction>,
        receivedTransaction: Transaction,
        reason: TransactionRejectionReasons
    ) {
        // given
        existingTransactions.asSequence().forEach { runBlocking { transactionRepository.save(it) } }

        // when
        transactionValidator.add(
            InboundNetworkMessage(
                thisNode.socketAddress,
                this,
                TransactionPush(receivedTransaction)
            )
        )

        // then
        verify(timeout = defaultTimeout) {
            eventPublisher.publish(TransactionRejected(thisNode.socketAddress, reason, receivedTransaction))
        }
    }

    companion object {
        val seed = AttoSeed("1234567890123456789012345678901234567890123456789012345678901234".fromHexToByteArray())
        val privateKey = seed.toPrivateKey(0u)
        val anotherPrivateKey = seed.toPrivateKey(1u)
        val evenAnotherPrivateKey = seed.toPrivateKey(2u)

        val thisNode = Node(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0u,
            minimalProtocolVersion = 0u,
            publicKey = privateKey.toPublicKey(),
            socketAddress = InetSocketAddress(InetAddress.getLocalHost(), 8330),
            features = emptySet()
        )

        val anotherOpenBlock = AttoBlock(
            type = AttoBlockType.OPEN,
            version = 0u,
            publicKey = anotherPrivateKey.toPublicKey(),
            height = 0u,
            previous = AttoHash(ByteArray(32)),
            representative = anotherPrivateKey.toPublicKey(),
            link = AttoLink.from(AttoHash(Random.nextBytes(ByteArray(32)))),
            balance = AttoAmount(100u),
            amount = AttoAmount(100u),
            timestamp = Instant.now()
        )

        val anotherSendBlock = AttoBlock(
            type = AttoBlockType.SEND,
            version = 0u,
            publicKey = anotherPrivateKey.toPublicKey(),
            height = 1u,
            previous = anotherOpenBlock.getHash(),
            representative = anotherPrivateKey.toPublicKey(),
            link = AttoLink.from(privateKey.toPublicKey()),
            balance = AttoAmount(0u),
            amount = AttoAmount(100u),
            timestamp = Instant.now()
        )

        val evenAnotherSendBlock = AttoBlock(
            type = AttoBlockType.SEND,
            version = 0u,
            publicKey = evenAnotherPrivateKey.toPublicKey(),
            height = 1u,
            previous = AttoHash(ByteArray(32)),
            representative = evenAnotherPrivateKey.toPublicKey(),
            link = AttoLink.from(anotherPrivateKey.toPublicKey()),
            balance = AttoAmount(0u),
            amount = AttoAmount(100u),
            timestamp = Instant.now()
        )

        val anotherReceiveBlock = AttoBlock(
            type = AttoBlockType.RECEIVE,
            version = 0u,
            publicKey = anotherPrivateKey.toPublicKey(),
            height = 2u,
            previous = anotherSendBlock.getHash(),
            representative = anotherPrivateKey.toPublicKey(),
            link = AttoLink.from(AttoHash(ByteArray(32))),
            balance = AttoAmount(50u),
            amount = AttoAmount(50u),
            timestamp = Instant.now()
        )

        val openBlock = AttoBlock(
            type = AttoBlockType.OPEN,
            version = 0u,
            publicKey = privateKey.toPublicKey(),
            height = 0u,
            previous = AttoHash(ByteArray(32)),
            representative = privateKey.toPublicKey(),
            link = AttoLink.from(AttoHash(Random.nextBytes(ByteArray(32)))),
            balance = AttoAmount(100u),
            amount = AttoAmount(100u),
            timestamp = Instant.now()
        )

        val sendBlock = AttoBlock(
            type = AttoBlockType.SEND,
            version = 0u,
            publicKey = privateKey.toPublicKey(),
            height = 1u,
            previous = openBlock.getHash(),
            representative = privateKey.toPublicKey(),
            link = AttoLink.from(privateKey.toPublicKey()),
            balance = AttoAmount(0u),
            amount = AttoAmount(100u),
            timestamp = Instant.now()
        )

        val receiveBlock = AttoBlock(
            type = AttoBlockType.RECEIVE,
            version = 0u,
            publicKey = privateKey.toPublicKey(),
            height = 2u,
            previous = sendBlock.previous,
            representative = privateKey.toPublicKey(),
            link = AttoLink.from(anotherSendBlock.getHash()),
            balance = AttoAmount(50u),
            amount = AttoAmount(50u),
            timestamp = Instant.now()
        )

        @JvmStatic
        fun validTransactionProvider(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        )
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.OPEN,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 0u,
                            previous = AttoHash(ByteArray(32)),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherSendBlock.getHash()),
                            balance = AttoAmount(100u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    )
                ),
                Arguments.of(
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.SEND,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u, // invalid
                            previous = receiveBlock.getHash(),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherPrivateKey.toPublicKey()),
                            balance = AttoAmount(20u),
                            amount = AttoAmount(30u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    )
                ),
                Arguments.of(
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.RECEIVE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u,
                            previous = sendBlock.previous,
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherSendBlock.getHash()),
                            balance = AttoAmount(150u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    )
                ),
                Arguments.of(
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.CHANGE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u,
                            previous = receiveBlock.getHash(),
                            representative = AttoPublicKey(ByteArray(32)),
                            link = AttoLink.from(AttoHash(ByteArray(32))),
                            balance = AttoAmount(50u),
                            amount = AttoAmount(0u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    )
                ),
            )
        }

        @JvmStatic
        fun invalidTransactionProvider(): Stream<Arguments> {
            return Stream.of(
                Arguments.of( // 1: invalid transaction
                    arrayListOf<Transaction>(),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.OPEN,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 1u, // invalid
                            previous = AttoHash(ByteArray(32)),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(AttoHash(ByteArray(32))),
                            balance = AttoAmount(100u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey
                    ),
                    TransactionRejectionReasons.INVALID_TRANSACTION
                ),
                Arguments.of( // 2: OPEN transaction with invalid link - linked transaction not found
                    arrayListOf<Transaction>(),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.OPEN,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 0u,
                            previous = AttoHash(ByteArray(32)),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(AttoHash(ByteArray(32))),
                            balance = AttoAmount(100u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.LINK_NOT_FOUND
                ),
                Arguments.of( // 3: RECEIVE transaction with invalid link - linked transaction not found
                    arrayListOf<Transaction>(),
                    createTransaction(
                        TransactionStatus.CONFIRMED,
                        AttoBlock(
                            type = AttoBlockType.RECEIVE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 1u,
                            previous = AttoHash(ByteArray(32)),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(AttoHash(ByteArray(32))),
                            balance = AttoAmount(100u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.LINK_NOT_FOUND
                ),
                Arguments.of( // 4: OPEN transaction with invalid link - linked transaction is not SEND type
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherReceiveBlock,
                            anotherPrivateKey,
                        )
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.OPEN,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 0u,
                            previous = AttoHash(ByteArray(32)),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherReceiveBlock.getHash()), // invalid
                            balance = AttoAmount(100u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.INVALID_LINK
                ),
                Arguments.of( // 5: RECEIVE transaction with invalid link - linked transaction is not SEND type
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherReceiveBlock,
                            anotherPrivateKey,
                        )
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.RECEIVE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 1u,
                            previous = AttoHash(ByteArray(32)),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherReceiveBlock.getHash()), // invalid
                            balance = AttoAmount(100u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.INVALID_LINK
                ),
                Arguments.of( // 6: OPEN transaction with invalid link - linked transaction not confirmed
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.RECEIVED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        )
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.OPEN,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 0u,
                            previous = AttoHash(ByteArray(32)),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherSendBlock.getHash()), // not confirmed
                            balance = AttoAmount(100u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.LINK_NOT_CONFIRMED
                ),
                Arguments.of( // 7: RECEIVE transaction with invalid link - linked transaction not confirmed
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.RECEIVED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        )
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.RECEIVE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 1u,
                            previous = AttoHash(ByteArray(32)),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherSendBlock.getHash()), // not confirmed
                            balance = AttoAmount(100u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.LINK_NOT_CONFIRMED
                ),
                Arguments.of( // 8: OPEN transaction with invalid link - wrong amount
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        )
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.OPEN,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 0u,
                            previous = AttoHash(ByteArray(32)),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherSendBlock.getHash()),
                            balance = AttoAmount(50u),
                            amount = AttoAmount(50u), // wrong amount
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.INVALID_AMOUNT
                ),
                Arguments.of( // 9: SEND transaction with invalid previous - previous transaction not found
                    arrayListOf<Transaction>(),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.SEND,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 1u,
                            previous = AttoHash(ByteArray(32)), // invalid
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherPrivateKey.toPublicKey()),
                            balance = AttoAmount(50u),
                            amount = AttoAmount(50u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.ACCOUNT_NOT_FOUND
                ),
                Arguments.of( // 10: RECEIVE transaction with invalid previous - previous transaction not found
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        )
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.RECEIVE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 1u,
                            previous = AttoHash(ByteArray(32)), // invalid
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherSendBlock.getHash()),
                            balance = AttoAmount(100u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.ACCOUNT_NOT_FOUND
                ),
                Arguments.of( // 11: SEND transaction with invalid previous - previous transaction not found
                    arrayListOf<Transaction>(),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.CHANGE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 1u,
                            previous = AttoHash(ByteArray(32)), // invalid
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(AttoHash(ByteArray(32))),
                            balance = AttoAmount(100u),
                            amount = AttoAmount(0u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.ACCOUNT_NOT_FOUND
                ),
                Arguments.of( // 12: SEND transaction with invalid previous - old transaction
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            sendBlock,
                            privateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.SEND,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = receiveBlock.height, // invalid
                            previous = sendBlock.previous,
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherPrivateKey.toPublicKey()),
                            balance = AttoAmount(50u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.OLD_TRANSACTION
                ),
                Arguments.of( // 13: RECEIVE transaction with invalid previous - old transaction
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            sendBlock,
                            privateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.RECEIVE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = receiveBlock.height, // invalid
                            previous = sendBlock.previous,
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherSendBlock.getHash()),
                            balance = AttoAmount(50u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.OLD_TRANSACTION
                ),
                Arguments.of( // 14: CHANGE transaction with invalid previous - old transaction
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            sendBlock,
                            privateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.CHANGE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 1u, // invalid
                            previous = AttoHash(ByteArray(32)),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(AttoHash(ByteArray(32))),
                            balance = AttoAmount(100u),
                            amount = AttoAmount(0u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.OLD_TRANSACTION
                ),
                Arguments.of( // 15: SEND transaction with invalid previous - previous not found
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            openBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.SEND,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u, // invalid
                            previous = sendBlock.previous,
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherPrivateKey.toPublicKey()),
                            balance = AttoAmount(50u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.PREVIOUS_NOT_FOUND
                ),
                Arguments.of( // 16: RECEIVE transaction with invalid previous - previous not found
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            openBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.RECEIVE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u, // invalid
                            previous = sendBlock.previous,
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherSendBlock.getHash()),
                            balance = AttoAmount(50u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.PREVIOUS_NOT_FOUND
                ),
                Arguments.of( // 17: CHANGE transaction with invalid previous - previous not found
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            openBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.CHANGE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u, // invalid
                            previous = AttoHash(ByteArray(32)),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(AttoHash(ByteArray(32))),
                            balance = AttoAmount(100u),
                            amount = AttoAmount(0u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.PREVIOUS_NOT_FOUND
                ),
                Arguments.of( // 18: SEND transaction with invalid previous - previous not found
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.VALIDATED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.SEND,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u, // invalid
                            previous = sendBlock.previous,
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherPrivateKey.toPublicKey()),
                            balance = AttoAmount(50u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.PREVIOUS_NOT_CONFIRMED
                ),
                Arguments.of( // 19: RECEIVE transaction with invalid previous - previous not found
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.VALIDATED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.RECEIVE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u, // invalid
                            previous = sendBlock.previous,
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherSendBlock.getHash()),
                            balance = AttoAmount(50u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.PREVIOUS_NOT_CONFIRMED
                ),
                Arguments.of( // 20: CHANGE transaction with invalid previous - previous not found
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.VALIDATED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.CHANGE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u, // invalid
                            previous = AttoHash(ByteArray(32)),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(AttoHash(ByteArray(32))),
                            balance = AttoAmount(100u),
                            amount = AttoAmount(0u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.PREVIOUS_NOT_CONFIRMED
                ),
                Arguments.of( // 21: CHANGE transaction with invalid previous - change to the same rep
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.CHANGE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u,
                            previous = AttoHash(ByteArray(32)),
                            representative = receiveBlock.representative, // invalid
                            link = AttoLink.from(AttoHash(ByteArray(32))),
                            balance = AttoAmount(100u),
                            amount = AttoAmount(0u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.INVALID_CHANGE
                ),
                Arguments.of( // 22: SEND transaction with invalid previous - invalid balance
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.SEND,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u,
                            previous = receiveBlock.getHash(),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherPrivateKey.toPublicKey()),
                            balance = AttoAmount(50u), // invalid
                            amount = AttoAmount(50u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.INVALID_BALANCE
                ),
                Arguments.of( // 23: RECEIVE transaction with invalid previous - invalid balance
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.RECEIVE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u,
                            previous = sendBlock.previous,
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherSendBlock.getHash()),
                            balance = AttoAmount(50u), // invalid
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.INVALID_BALANCE
                ),
                Arguments.of( // 24: CHANGE transaction with invalid previous - invalid balance
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.CHANGE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u,
                            previous = receiveBlock.getHash(),
                            representative = AttoPublicKey(ByteArray(32)),
                            link = AttoLink.from(AttoHash(ByteArray(32))),
                            balance = AttoAmount(100u),  // invalid
                            amount = AttoAmount(0u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.INVALID_BALANCE
                ),
                Arguments.of( // 25: RECEIVE transaction with invalid link - linked transaction has another target account
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            evenAnotherSendBlock,
                            evenAnotherPrivateKey,
                        )
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.RECEIVE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 1u,
                            previous = AttoHash(ByteArray(32)),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(evenAnotherSendBlock.getHash()), // not confirmed
                            balance = AttoAmount(100u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.INVALID_LINK
                ),
                Arguments.of( // 26: OPEN transaction with invalid link - linked transaction has another target account
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            evenAnotherSendBlock,
                            evenAnotherPrivateKey,
                        )
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.OPEN,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 0u,
                            previous = AttoHash(ByteArray(32)),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(evenAnotherSendBlock.getHash()),  // invalid link
                            balance = AttoAmount(100u),
                            amount = AttoAmount(100u),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.INVALID_LINK
                ),
                Arguments.of( // 27: RECEIVE transaction with invalid link - linked transaction has timestamp afterwards
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        )
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.RECEIVE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 1u,
                            previous = AttoHash(ByteArray(32)),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherSendBlock.getHash()),
                            balance = AttoAmount(100u),
                            amount = AttoAmount(100u),
                            timestamp = anotherSendBlock.timestamp.minusSeconds(1)  // not confirmed
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.INVALID_TIMESTAMP
                ),
                Arguments.of( // 28: OPEN transaction with invalid link - linked transaction has timestamp afterwards
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        )
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.OPEN,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 0u,
                            previous = AttoHash(ByteArray(32)),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherSendBlock.getHash()),
                            balance = AttoAmount(100u),
                            amount = AttoAmount(100u),
                            timestamp = anotherSendBlock.timestamp // invalid link
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.INVALID_TIMESTAMP
                ),
                Arguments.of( // 29: SEND transaction with invalid previous - invalid balance
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.SEND,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u,
                            previous = receiveBlock.getHash(),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherPrivateKey.toPublicKey()),
                            balance = AttoAmount(0u),
                            amount = AttoAmount(50u),
                            timestamp = anotherSendBlock.timestamp.minusSeconds(1)  // invalid
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.INVALID_TIMESTAMP
                ),
                Arguments.of( // 30: RECEIVE transaction with invalid previous - invalid balance
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.RECEIVE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u,
                            previous = sendBlock.previous,
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherSendBlock.getHash()),
                            balance = AttoAmount(150u),
                            amount = AttoAmount(100u),
                            timestamp = anotherSendBlock.timestamp  // invalid
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.INVALID_TIMESTAMP
                ),
                Arguments.of( // 31: CHANGE transaction with invalid previous - invalid balance
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.CHANGE,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u,
                            previous = receiveBlock.getHash(),
                            representative = AttoPublicKey(ByteArray(32)),
                            link = AttoLink.from(AttoHash(ByteArray(32))),
                            balance = AttoAmount(50u),
                            amount = AttoAmount(0u),
                            timestamp = anotherSendBlock.timestamp  // invalid
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.INVALID_TIMESTAMP
                ),
                Arguments.of( // 32: SEND transaction with invalid previous - invalid balance (underflow)
                    arrayListOf(
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            anotherSendBlock,
                            anotherPrivateKey,
                        ),
                        createTransaction(
                            TransactionStatus.CONFIRMED,
                            receiveBlock,
                            privateKey,
                        ),
                    ),
                    createTransaction(
                        TransactionStatus.RECEIVED,
                        AttoBlock(
                            type = AttoBlockType.SEND,
                            version = 0u,
                            publicKey = privateKey.toPublicKey(),
                            height = 3u,
                            previous = receiveBlock.getHash(),
                            representative = privateKey.toPublicKey(),
                            link = AttoLink.from(anotherPrivateKey.toPublicKey()),
                            balance = AttoAmount(51u), // overflow
                            amount = AttoAmount(ULong.MAX_VALUE),
                            timestamp = Instant.now()
                        ),
                        privateKey,
                    ),
                    TransactionRejectionReasons.INVALID_BALANCE
                )
            )
        }

        private fun createTransaction(
            status: TransactionStatus,
            block: AttoBlock,
            privateKey: AttoPrivateKey
        ): Transaction {
            val work = if (block.type == AttoBlockType.OPEN) {
                AttoWork.work(block.publicKey, thisNode.network)
            } else {
                AttoWork.work(block.previous, thisNode.network)
            }
            return Transaction(
                status = status,
                block = block,
                signature = privateKey.sign(block.getHash().value),
                work = work,
                receivedTimestamp = Instant.now()
            )
        }
    }
}