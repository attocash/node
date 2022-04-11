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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.test.assertEquals

@ExtendWith(MockKExtension::class)
class TransactionValidatorTest {
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

    @Test
    fun `should publish TransactionValidate when SEND transaction is valid`() {
        // given
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))

        Thread.sleep(1)
        val sendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        checkValid(sendBlockA, listOf(existingOpenBlockA))
    }

    @Test
    fun `should publish TransactionValidate when OPEN transaction is valid`() {
        // given
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        Thread.sleep(1)
        val openBlockB = AttoBlockOld.open(publicKeyB, publicKeyB, existingSendBlockA)

        checkValid(openBlockB, listOf(existingSendBlockA))
    }

    @Test
    fun `should publish TransactionValidate when RECEIVE transaction is valid`() {
        // given
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32))

        Thread.sleep(1)
        val receiveBlockB = existingOpenBlockB.receive(existingSendBlockA)

        checkValid(receiveBlockB, listOf(existingSendBlockA, existingOpenBlockB))
    }

    @Test
    fun `should publish TransactionValidate when CHANGE transaction is valid`() {
        // given
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))

        Thread.sleep(1)
        val changeBlockA = existingOpenBlockA.change(publicKeyB)

        checkValid(changeBlockA, listOf(existingOpenBlockA))
    }

    @Test
    fun `should ignore transactions when duplicates`() {
        // given
        val blockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))

        val transaction = createTransaction(TransactionStatus.RECEIVED, blockA)

        // when
        transactionValidator.add(
            InboundNetworkMessage(
                thisNode.socketAddress,
                this,
                TransactionPush(transaction)
            )
        )

        transactionValidator.add(
            InboundNetworkMessage(
                thisNode.socketAddress,
                this,
                TransactionPush(transaction)
            )
        ) // duplicated

        // then
        verify(exactly = 1, timeout = defaultTimeout) {
            eventPublisher.publish(any())
        }
    }

    @Test
    fun `should buffer transaction when previous transaction is being observed`() = runBlocking {
        // given
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        transactionRepository.save(createTransaction(TransactionStatus.CONFIRMED, existingOpenBlockA))

        val sendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))
        val sendTransactionA = createTransaction(TransactionStatus.RECEIVED, sendBlockA)

        val changeBlockA = sendBlockA.change(publicKeyB)
        val changeTransactionA = createTransaction(TransactionStatus.RECEIVED, changeBlockA)

        transactionValidator.add(
            InboundNetworkMessage(
                thisNode.socketAddress,
                this,
                TransactionPush(sendTransactionA)
            )
        )

        verify(exactly = 1, timeout = defaultTimeout) {
            eventPublisher.publish(TransactionValidated(sendTransactionA.copy(status = TransactionStatus.VALIDATED)))
        }

        // when
        transactionValidator.add(
            InboundNetworkMessage(
                thisNode.socketAddress,
                this,
                TransactionPush(changeTransactionA)
            )
        )

        verify(exactly = 0, timeout = defaultTimeout) {
            eventPublisher.publish(TransactionValidated(changeTransactionA.copy(status = TransactionStatus.VALIDATED)))
        }

        assertEquals(1, transactionValidator.getPreviousBuffer().size)

        runBlocking { transactionRepository.save(sendTransactionA.copy(status = TransactionStatus.CONFIRMED)) }

        transactionValidator.process(TransactionConfirmed(sendTransactionA))

        // then
        verify(exactly = 1, timeout = defaultTimeout) {
            eventPublisher.publish(TransactionValidated(changeTransactionA.copy(status = TransactionStatus.VALIDATED)))
        }

        assertEquals(0, transactionValidator.getLinkBuffer().size)
    }

    @Test
    fun `should buffer transaction when link transaction is being observed`() = runBlocking {
        // given
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        transactionRepository.save(createTransaction(TransactionStatus.CONFIRMED, existingOpenBlockA))

        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32))
        transactionRepository.save(createTransaction(TransactionStatus.CONFIRMED, existingOpenBlockB))

        val sendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))
        val sendTransactionA = createTransaction(TransactionStatus.RECEIVED, sendBlockA)

        val receiveBlockB = existingOpenBlockB.receive(sendBlockA)
        val receiveTransactionB = createTransaction(TransactionStatus.RECEIVED, receiveBlockB)

        transactionValidator.add(
            InboundNetworkMessage(
                thisNode.socketAddress,
                this,
                TransactionPush(sendTransactionA)
            )
        )

        verify(exactly = 1, timeout = defaultTimeout) {
            eventPublisher.publish(TransactionValidated(sendTransactionA.copy(status = TransactionStatus.VALIDATED)))
        }

        // when
        transactionValidator.add(
            InboundNetworkMessage(
                thisNode.socketAddress,
                this,
                TransactionPush(receiveTransactionB)
            )
        )

        verify(exactly = 0, timeout = defaultTimeout) {
            eventPublisher.publish(TransactionValidated(receiveTransactionB.copy(status = TransactionStatus.VALIDATED)))
        }

        assertEquals(1, transactionValidator.getLinkBuffer().size)

        runBlocking { transactionRepository.save(sendTransactionA.copy(status = TransactionStatus.CONFIRMED)) }

        transactionValidator.process(TransactionConfirmed(sendTransactionA))

        // then
        verify(exactly = 1, timeout = defaultTimeout) {
            eventPublisher.publish(TransactionValidated(receiveTransactionB.copy(status = TransactionStatus.VALIDATED)))
        }

        assertEquals(0, transactionValidator.getLinkBuffer().size)
    }

    @Test
    fun `should publish INVALID_TRANSACTION when transaction is invalid`() {
        val openBlockA = createOpenBlock(publicKeyA, publicKeyA, 0UL, ByteArray(32))

        checkInvalid(TransactionRejectionReasons.INVALID_TRANSACTION, openBlockA, listOf())
    }

    @Test
    fun `should publish LINK_NOT_FOUND when OPEN transaction is invalid`() {
        val openBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))

        checkInvalid(TransactionRejectionReasons.LINK_NOT_FOUND, openBlockA, listOf())
    }

    @Test
    fun `should publish LINK_NOT_FOUND when RECEIVE transaction is invalid`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32))

        Thread.sleep(1)
        val receiveBlockB = existingOpenBlockB.receive(existingSendBlockA)

        checkInvalid(TransactionRejectionReasons.LINK_NOT_FOUND, receiveBlockB, listOf(existingOpenBlockB))
    }

    @Test
    fun `should publish INVALID_LINK when OPEN transaction link is not a SEND transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        val openBlockB = AttoBlockOld.open(publicKeyB, publicKeyB, existingSendBlockA)
            .copy(link = AttoLink.from(existingOpenBlockA.getHash()))

        checkInvalid(TransactionRejectionReasons.INVALID_LINK, openBlockB, listOf(existingOpenBlockA))
    }

    @Test
    fun `should publish INVALID_LINK when RECEIVE transaction is not a SEND transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))
        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32))

        Thread.sleep(1)
        val receiveBlockB = existingOpenBlockB.receive(existingSendBlockA)
            .copy(link = AttoLink.from(existingOpenBlockA.getHash()))

        checkInvalid(
            TransactionRejectionReasons.INVALID_LINK,
            receiveBlockB,
            listOf(existingOpenBlockA, existingOpenBlockB)
        )
    }

    @Test
    fun `should publish INVALID_AMOUNT when OPEN transaction is invalid`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        Thread.sleep(1)
        val openBlockB = AttoBlockOld.open(publicKeyB, publicKeyB, existingSendBlockA)
            .copy(balance = AttoAmount.max, amount = AttoAmount.max)

        checkInvalid(TransactionRejectionReasons.INVALID_AMOUNT, openBlockB, listOf(existingSendBlockA))
    }

    @Test
    fun `should publish INVALID_AMOUNT when RECEIVE transaction is invalid`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))
        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32))

        Thread.sleep(1)
        val receiveBlockB = existingOpenBlockB.receive(existingSendBlockA)
            .copy(balance = AttoAmount(300UL), amount = AttoAmount(200UL))

        checkInvalid(
            TransactionRejectionReasons.INVALID_AMOUNT,
            receiveBlockB,
            listOf(existingSendBlockA, existingOpenBlockB)
        )
    }

    @Test
    fun `should publish INVALID_TIMESTAMP when OPEN transaction is invalid`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        val openBlockB = AttoBlockOld.open(publicKeyB, publicKeyB, existingSendBlockA)
            .copy(timestamp = existingSendBlockA.timestamp)

        checkInvalid(TransactionRejectionReasons.INVALID_TIMESTAMP, openBlockB, listOf(existingSendBlockA))
    }

    @Test
    fun `should publish INVALID_TIMESTAMP when RECEIVE transaction is invalid`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))
        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32))

        val receiveBlockB =
            existingOpenBlockB.receive(existingSendBlockA).copy(timestamp = existingSendBlockA.timestamp)

        checkInvalid(
            TransactionRejectionReasons.INVALID_TIMESTAMP,
            receiveBlockB,
            listOf(existingSendBlockA, existingOpenBlockB)
        )
    }

    @Test
    fun `should publish INVALID_LINK when OPEN transaction is invalid`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))

        Thread.sleep(1)
        val openBlockB = AttoBlockOld(
            type = AttoBlockType.OPEN,
            version = existingOpenBlockA.version,
            publicKey = publicKeyB,
            height = 0U,
            previous = AttoHash(AttoBlockOld.zeros32),
            representative = publicKeyB,
            link = AttoLink.from(existingOpenBlockA.getHash()),
            balance = existingOpenBlockA.amount,
            amount = existingOpenBlockA.amount,
            timestamp = Instant.now()
        )

        checkInvalid(TransactionRejectionReasons.INVALID_LINK, openBlockB, listOf(existingOpenBlockA))
    }

    @Test
    fun `should publish INVALID_LINK when RECEIVE transaction is invalid`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyC, AttoAmount(100UL))
        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32))

        Thread.sleep(1)
        val receiveBlockB = AttoBlockOld(
            type = AttoBlockType.RECEIVE,
            version = existingOpenBlockB.version,
            publicKey = existingOpenBlockB.publicKey,
            height = existingOpenBlockB.height + 1U,
            previous = existingOpenBlockB.getHash(),
            representative = existingOpenBlockB.representative,
            link = AttoLink.from(existingSendBlockA.getHash()),
            balance = existingOpenBlockB.balance.plus(existingSendBlockA.amount),
            amount = existingSendBlockA.amount,
            timestamp = Instant.now()
        )

        checkInvalid(
            TransactionRejectionReasons.INVALID_LINK,
            receiveBlockB,
            listOf(existingSendBlockA, existingOpenBlockB)
        )
    }

    @Test
    fun `should publish INVALID_VERSION when OPEN transaction is invalid`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL)).copy(version = 1U)

        Thread.sleep(1)
        val openBlockB = AttoBlockOld.open(publicKeyB, publicKeyB, existingSendBlockA).copy(version = 0u)

        checkInvalid(TransactionRejectionReasons.INVALID_VERSION, openBlockB, listOf(existingSendBlockA))
    }

    @Test
    fun `should publish INVALID_VERSION when RECEIVE transaction is invalid`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL)).copy(version = 1U)
        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32))

        Thread.sleep(1)
        val receiveBlock = existingOpenBlockB.receive(existingSendBlockA).copy(version = 0u)

        checkInvalid(
            TransactionRejectionReasons.INVALID_VERSION,
            receiveBlock,
            listOf(existingSendBlockA, existingOpenBlockB)
        )
    }

    @Test
    fun `should publish LINK_NOT_CONFIRMED when OPEN transaction link is not confirmed`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        Thread.sleep(1)
        val receiveBlock = AttoBlockOld.open(publicKeyB, publicKeyB, existingSendBlockA)

        checkInvalid(
            TransactionRejectionReasons.LINK_NOT_CONFIRMED,
            receiveBlock,
            emptyList(),
            listOf(existingSendBlockA)
        )
    }

    @Test
    fun `should publish LINK_NOT_CONFIRMED when RECEIVE transaction link is not confirmed`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))
        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32))

        Thread.sleep(1)
        val receiveBlock = existingOpenBlockB.receive(existingSendBlockA)

        checkInvalid(
            TransactionRejectionReasons.LINK_NOT_CONFIRMED,
            receiveBlock,
            listOf(existingOpenBlockB),
            listOf(existingSendBlockA)
        )
    }

    @Test
    fun `should publish ACCOUNT_NOT_FOUND when SEND transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))

        val sendBlock = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        checkInvalid(
            TransactionRejectionReasons.ACCOUNT_NOT_FOUND,
            sendBlock,
            listOf()
        )
    }

    @Test
    fun `should publish ACCOUNT_NOT_FOUND when RECEIVE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32))

        Thread.sleep(1)
        val receiveBlock = existingOpenBlockB.receive(existingSendBlockA)

        checkInvalid(
            TransactionRejectionReasons.ACCOUNT_NOT_FOUND,
            receiveBlock,
            listOf(existingSendBlockA)
        )
    }

    @Test
    fun `should publish ACCOUNT_NOT_FOUND when CHANGE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))

        val changeBlock = existingOpenBlockA.change(publicKeyB)

        checkInvalid(
            TransactionRejectionReasons.ACCOUNT_NOT_FOUND,
            changeBlock,
            listOf()
        )
    }


    @Test
    fun `should publish OLD_TRANSACTION when SEND transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        val sendBlock = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        checkInvalid(
            TransactionRejectionReasons.OLD_TRANSACTION,
            sendBlock,
            listOf(existingSendBlockA)
        )
    }

    @Test
    fun `should publish OLD_TRANSACTION when RECEIVE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32))
        val existingReceiveBlockB = existingOpenBlockB.receive(existingSendBlockA)

        Thread.sleep(1)
        val receiveBlock = existingOpenBlockB.receive(existingSendBlockA)

        checkInvalid(
            TransactionRejectionReasons.OLD_TRANSACTION,
            receiveBlock,
            listOf(existingSendBlockA, existingReceiveBlockB)
        )
    }

    @Test
    fun `should publish OLD_TRANSACTION when CHANGE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingChangeBlockA = existingOpenBlockA.change(publicKeyB)

        val changeBlock = existingOpenBlockA.change(publicKeyB)

        checkInvalid(
            TransactionRejectionReasons.OLD_TRANSACTION,
            changeBlock,
            listOf(existingChangeBlockA)
        )
    }

    @Test
    fun `should publish PREVIOUS_NOT_FOUND when SEND transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        val sendBlock = existingSendBlockA.send(publicKeyB, AttoAmount(100UL))

        checkInvalid(
            TransactionRejectionReasons.PREVIOUS_NOT_FOUND,
            sendBlock,
            listOf(existingOpenBlockA)
        )
    }

    @Test
    fun `should publish PREVIOUS_NOT_FOUND when RECEIVE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32))
        val existingChangeBlockB = existingOpenBlockB.change(publicKeyA)

        Thread.sleep(1)
        val receiveBlock = existingChangeBlockB.receive(existingSendBlockA)

        checkInvalid(
            TransactionRejectionReasons.PREVIOUS_NOT_FOUND,
            receiveBlock,
            listOf(existingSendBlockA, existingOpenBlockB)
        )
    }

    @Test
    fun `should publish PREVIOUS_NOT_FOUND when CHANGE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingChangeBlockA = existingOpenBlockA.change(publicKeyB)

        val changeBlock = existingChangeBlockA.change(publicKeyA)

        checkInvalid(
            TransactionRejectionReasons.PREVIOUS_NOT_FOUND,
            changeBlock,
            listOf(existingOpenBlockA)
        )
    }


    @Test
    fun `should publish INVALID_PREVIOUS when SEND transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))

        Thread.sleep(1)
        val sendBlock = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL)).copy(previous = AttoHash(ByteArray(32)))

        checkInvalid(
            TransactionRejectionReasons.INVALID_PREVIOUS,
            sendBlock,
            listOf(existingOpenBlockA)
        )
    }

    @Test
    fun `should publish INVALID_PREVIOUS when RECEIVE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32))

        Thread.sleep(1)
        val receiveBlock = existingOpenBlockB.receive(existingSendBlockA).copy(previous = AttoHash(ByteArray(32)))

        checkInvalid(
            TransactionRejectionReasons.INVALID_PREVIOUS,
            receiveBlock,
            listOf(existingSendBlockA, existingOpenBlockB)
        )
    }

    @Test
    fun `should publish INVALID_PREVIOUS when CHANGE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))

        Thread.sleep(1)
        val changeBlock = existingOpenBlockA.change(publicKeyB).copy(previous = AttoHash(ByteArray(32)))

        checkInvalid(
            TransactionRejectionReasons.INVALID_PREVIOUS,
            changeBlock,
            listOf(existingOpenBlockA)
        )
    }

    @Test
    fun `should publish PREVIOUS_NOT_CONFIRMED when SEND transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))

        Thread.sleep(1)
        val sendBlock = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        checkInvalid(
            TransactionRejectionReasons.PREVIOUS_NOT_CONFIRMED,
            sendBlock,
            listOf(),
            listOf(existingOpenBlockA)
        )
    }

    @Test
    fun `should publish PREVIOUS_NOT_CONFIRMED when RECEIVE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32))

        Thread.sleep(1)
        val receiveBlock = existingOpenBlockB.receive(existingSendBlockA)

        checkInvalid(
            TransactionRejectionReasons.PREVIOUS_NOT_CONFIRMED,
            receiveBlock,
            listOf(existingSendBlockA),
            listOf(existingOpenBlockB)
        )
    }

    @Test
    fun `should publish PREVIOUS_NOT_CONFIRMED when CHANGE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))

        Thread.sleep(1)
        val changeBlock = existingOpenBlockA.change(publicKeyB)

        checkInvalid(
            TransactionRejectionReasons.PREVIOUS_NOT_CONFIRMED,
            changeBlock,
            listOf(),
            listOf(existingOpenBlockA)
        )
    }

    @Test
    fun `should publish INVALID_TIMESTAMP when SEND transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))

        val sendBlock =
            existingOpenBlockA.send(publicKeyB, AttoAmount(100UL)).copy(timestamp = existingOpenBlockA.timestamp)

        checkInvalid(
            TransactionRejectionReasons.INVALID_TIMESTAMP,
            sendBlock,
            listOf(existingOpenBlockA)
        )
    }

    @Test
    fun `should publish INVALID_TIMESTAMP when RECEIVE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        Thread.sleep(1)
        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32))

        val receiveBlock = existingOpenBlockB.receive(existingSendBlockA).copy(timestamp = existingOpenBlockB.timestamp)

        checkInvalid(
            TransactionRejectionReasons.INVALID_TIMESTAMP,
            receiveBlock,
            listOf(existingSendBlockA, existingOpenBlockB)
        )
    }

    @Test
    fun `should publish INVALID_TIMESTAMP when CHANGE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))

        val changeBlock = existingOpenBlockA.change(publicKeyB).copy(timestamp = existingOpenBlockA.timestamp)

        checkInvalid(
            TransactionRejectionReasons.INVALID_TIMESTAMP,
            changeBlock,
            listOf(existingOpenBlockA)
        )
    }

    @Test
    fun `should publish INVALID_VERSION when SEND transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32)).copy(version = 1U)

        Thread.sleep(1)
        val sendBlock =
            existingOpenBlockA.send(publicKeyB, AttoAmount(100UL)).copy(version = 0U)

        checkInvalid(
            TransactionRejectionReasons.INVALID_VERSION,
            sendBlock,
            listOf(existingOpenBlockA)
        )
    }

    @Test
    fun `should publish INVALID_VERSION when RECEIVE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32)).copy(version = 1U)

        Thread.sleep(1)
        val receiveBlock = existingOpenBlockB.receive(existingSendBlockA).copy(version = 0U)

        checkInvalid(
            TransactionRejectionReasons.INVALID_VERSION,
            receiveBlock,
            listOf(existingSendBlockA, existingOpenBlockB)
        )
    }

    @Test
    fun `should publish INVALID_VERSION when CHANGE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32)).copy(version = 1U)

        Thread.sleep(1)
        val changeBlock = existingOpenBlockA.change(publicKeyB).copy(version = 0U)

        checkInvalid(
            TransactionRejectionReasons.INVALID_VERSION,
            changeBlock,
            listOf(existingOpenBlockA)
        )
    }

    @Test
    fun `should publish INVALID_CHANGE when CHANGE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))

        Thread.sleep(1)
        val changeBlock = existingOpenBlockA.change(publicKeyA)

        checkInvalid(
            TransactionRejectionReasons.INVALID_CHANGE,
            changeBlock,
            listOf(existingOpenBlockA)
        )
    }

    @Test
    fun `should publish INVALID_BALANCE when SEND transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))

        Thread.sleep(1)
        val sendBlock = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL)).copy(balance = AttoAmount(100UL))

        checkInvalid(
            TransactionRejectionReasons.INVALID_BALANCE,
            sendBlock,
            listOf(existingOpenBlockA)
        )
    }

    @Test
    fun `should publish INVALID_BALANCE when SEND transaction overflows`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 1UL, ByteArray(32))

        Thread.sleep(1)
        val sendBlock = existingOpenBlockA.send(publicKeyB, AttoAmount(2UL)).copy(balance = AttoAmount(ULong.MAX_VALUE))

        checkInvalid(
            TransactionRejectionReasons.INVALID_BALANCE,
            sendBlock,
            listOf(existingOpenBlockA)
        )
    }

    @Test
    fun `should publish INVALID_BALANCE when RECEIVE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        val existingOpenBlockB = createOpenBlock(publicKeyB, publicKeyB, 100UL, ByteArray(32))

        Thread.sleep(1)
        val receiveBlock = existingOpenBlockB.receive(existingSendBlockA).copy(balance = AttoAmount.max)

        checkInvalid(
            TransactionRejectionReasons.INVALID_BALANCE,
            receiveBlock,
            listOf(existingSendBlockA, existingOpenBlockB)
        )
    }

    @Test
    fun `should publish INVALID_BALANCE when CHANGE transaction`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))

        Thread.sleep(1)
        val changeBlock = existingOpenBlockA.change(publicKeyB).copy(balance = AttoAmount.max)

        checkInvalid(
            TransactionRejectionReasons.INVALID_BALANCE,
            changeBlock,
            listOf(existingOpenBlockA)
        )
    }

    @Test
    fun `should publish LINK_ALREADY_USED when RECEIVE linked transaction is already used`() {
        val existingOpenBlockA = createOpenBlock(publicKeyA, publicKeyA, 100UL, ByteArray(32))
        val existingSendBlockA = existingOpenBlockA.send(publicKeyB, AttoAmount(100UL))

        val existingOpenBlockB = AttoBlockOld.open(publicKeyB, publicKeyB, existingSendBlockA)

        Thread.sleep(1)
        val receiveBlockB = existingOpenBlockB.receive(existingSendBlockA)

        checkInvalid(
            TransactionRejectionReasons.LINK_ALREADY_USED,
            receiveBlockB,
            listOf(existingOpenBlockA, existingSendBlockA, existingOpenBlockB)
        )
    }


    private fun checkValid(
        receivedBlock: AttoBlockOld,
        existingBlocks: List<AttoBlockOld>
    ) {
        // given
        existingBlocks.asSequence()
            .map { createTransaction(TransactionStatus.CONFIRMED, it) }
            .forEach { runBlocking { transactionRepository.save(it) } }

        val receivedTransaction = createTransaction(TransactionStatus.RECEIVED, receivedBlock)

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

    private fun checkInvalid(
        reason: TransactionRejectionReasons,
        receivedBlock: AttoBlockOld,
        existingConfirmedBlocks: List<AttoBlockOld>
    ) {
        checkInvalid(reason, receivedBlock, existingConfirmedBlocks, emptyList())
    }

    private fun checkInvalid(
        reason: TransactionRejectionReasons,
        receivedBlock: AttoBlockOld,
        existingConfirmedBlocks: List<AttoBlockOld>,
        existingValidateBlocks: List<AttoBlockOld>
    ) {
        // given
        existingConfirmedBlocks.asSequence()
            .map { createTransaction(TransactionStatus.CONFIRMED, it) }
            .forEach { runBlocking { transactionRepository.save(it) } }

        existingValidateBlocks.asSequence()
            .map { createTransaction(TransactionStatus.VALIDATED, it) }
            .forEach { runBlocking { transactionRepository.save(it) } }

        val receivedTransaction = createTransaction(
            TransactionStatus.RECEIVED,
            receivedBlock
        )

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
            eventPublisher.publish(
                TransactionRejected(
                    thisNode.socketAddress,
                    reason,
                    receivedTransaction.copy(status = TransactionStatus.REJECTED)
                )
            )
        }
    }

    companion object {
        val seed = AttoSeed("1234567890123456789012345678901234567890123456789012345678901234".fromHexToByteArray())

        val privatekeyA = seed.toPrivateKey(0u)
        val privatekeyB = seed.toPrivateKey(1u)
        val privatekeyC = seed.toPrivateKey(2u)

        val publicKeyA = privatekeyA.toPublicKey()
        val publicKeyB = privatekeyB.toPublicKey()
        val publicKeyC = privatekeyC.toPublicKey()


        val privateKeyMap = mapOf(
            privatekeyA.toPublicKey() to privatekeyA,
            privatekeyB.toPublicKey() to privatekeyB,
            privatekeyC.toPublicKey() to privatekeyC,
        )

        val thisNode = Node(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0u,
            minimalProtocolVersion = 0u,
            publicKey = privatekeyA.toPublicKey(),
            socketAddress = InetSocketAddress(InetAddress.getLocalHost(), 8330),
            features = emptySet()
        )

        val openBlock0 = createOpenBlock(privatekeyA.toPublicKey(), privatekeyA.toPublicKey(), 100UL, ByteArray(32))


        fun createOpenBlock(
            publicKey: AttoPublicKey,
            representative: AttoPublicKey,
            amount: ULong,
            hash: ByteArray
        ): AttoBlockOld {
            return AttoBlockOld(
                type = AttoBlockType.OPEN,
                version = AttoBlockOld.maxVersion,
                publicKey = publicKey,
                height = 0U,
                previous = AttoHash(ByteArray(32)),
                representative = representative,
                link = AttoLink.from(AttoHash(hash)),
                balance = AttoAmount(amount),
                amount = AttoAmount(amount),
                timestamp = Instant.now()
            )
        }
    }

    fun createTransaction(
        status: TransactionStatus,
        block: AttoBlockOld
    ): Transaction {
        val work = if (block.type == AttoBlockType.OPEN) {
            AttoWork.work(block.publicKey, thisNode.network)
        } else {
            AttoWork.work(block.previous, thisNode.network)
        }

        val privateKey = privateKeyMap[block.publicKey]!!

        return Transaction(
            status = status,
            block = block,
            signature = privateKey.sign(block.getHash().value),
            work = work,
            receivedTimestamp = Instant.now()
        )
    }
}