package org.atto.node.transaction

import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.atto.commons.*
import org.atto.node.PropertyHolder
import org.atto.node.Waiter.waitUntilNonNull
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.network.NetworkMessagePublisher
import org.atto.protocol.Node
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.transaction.TransactionPush
import org.atto.protocol.transaction.TransactionStatus
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TransactionStepDefinition(
    private val thisNode: Node,
    private val messagePublisher: NetworkMessagePublisher,
    private val transactionRepository: TransactionRepository,
) {
    private val defaultSendAmount = AttoAmount(4_500_000_000_000_000_000u)

    @When("^send transaction (\\w+) from (\\w+) account to (\\w+) account$")
    fun sendTransaction(transactionShortId: String, shortId: String, receiverShortId: String) = runTest {
        val privateKey = PropertyHolder.get(AttoPrivateKey::class.java, shortId)
        val publicKey = PropertyHolder.get(AttoPublicKey::class.java, shortId)
        val latestTransaction = transactionRepository.findLastByPublicKeyId(publicKey)!!

        val receiverPublicKey = PropertyHolder.get(AttoPublicKey::class.java, receiverShortId)

        val sendBlock = latestTransaction.block.send(receiverPublicKey, defaultSendAmount)
        val sendTransaction = Transaction(
            block = sendBlock,
            signature = privateKey.sign(sendBlock.getHash().value),
            work = AttoWork.Companion.work(latestTransaction.hash, thisNode.network)
        )
        messagePublisher.publish(
            InboundNetworkMessage(
                thisNode.socketAddress,
                this,
                TransactionPush(sendTransaction)
            )
        )

        PropertyHolder.add(transactionShortId, sendTransaction)
    }

    @When("^change transaction (\\w+) from (\\w+) account to (\\w+) representative$")
    fun changeTransaction(transactionShortId: String, shortId: String, representativeShortId: String) = runTest {
        val privateKey = PropertyHolder.get(AttoPrivateKey::class.java, shortId)
        val publicKey = PropertyHolder.get(AttoPublicKey::class.java, shortId)
        val latestTransaction = transactionRepository.findLastByPublicKeyId(publicKey)!!

        val receiverPublicKey = PropertyHolder.get(AttoPublicKey::class.java, representativeShortId)

        val changeBlock = latestTransaction.block.change(receiverPublicKey)
        val changeTransaction = Transaction(
            block = changeBlock,
            signature = privateKey.sign(changeBlock.getHash().value),
            work = AttoWork.Companion.work(latestTransaction.hash, thisNode.network)
        )
        messagePublisher.publish(
            InboundNetworkMessage(
                thisNode.socketAddress,
                this,
                TransactionPush(changeTransaction)
            )
        )

        PropertyHolder.add(transactionShortId, changeTransaction)
    }

    @Then("^transaction (\\w+) is confirmed$")
    fun checkConfirmed(transactionShortId: String) = runTest {
        val expectedTransaction = PropertyHolder.get(Transaction::class.java, transactionShortId)
        val transaction = waitUntilNonNull {
            runBlocking {
                transactionRepository.findById(expectedTransaction.hash)
            }
        }
        assertEquals(expectedTransaction.copy(status = TransactionStatus.CONFIRMED), transaction)
    }

    @Then("^matching open or receive transaction for transaction (\\w+) is confirmed$")
    fun checkMatchingConfirmed(transactionShortId: String) = runTest {
        val sendTransaction = PropertyHolder.get(Transaction::class.java, transactionShortId)

        assertEquals(AttoBlockType.SEND, sendTransaction.block.type)

        val linkedPublicKey = sendTransaction.block.link.publicKey!!

        val transaction = waitUntilNonNull {
            runBlocking {
                val matchingTransaction = transactionRepository.findLastByPublicKeyId(linkedPublicKey)
                if (matchingTransaction != null && matchingTransaction.block.link.hash == sendTransaction.hash) {
                    matchingTransaction
                } else {
                    null
                }
            }
        }
        assertNotNull(transaction)
    }
}