package org.atto.node.transaction

import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.atto.commons.*
import org.atto.node.PropertyHolder
import org.atto.node.Waiter.waitUntilNonNull
import org.atto.node.account.AccountDTO
import org.atto.node.account.AccountRepository
import org.atto.node.node.Neighbour
import org.atto.protocol.AttoNode
import org.junit.jupiter.api.Assertions.assertNotNull
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono

class TransactionStepDefinition(
    private val thisNode: AttoNode,
    private val accountRepository: AccountRepository,
    private val webClient: WebClient
) {
    private val logger = KotlinLogging.logger {}

    private val defaultSendAmount = AttoAmount(4_500_000_000_000_000_000u)

    @When("^send transaction (\\w+) from (\\w+) account to (\\w+) account$")
    fun sendTransaction(transactionShortId: String, shortId: String, receiverShortId: String) = runBlocking {
        val privateKey = PropertyHolder.get(AttoPrivateKey::class.java, shortId)
        val publicKey = PropertyHolder.get(AttoPublicKey::class.java, shortId)
        val account = accountRepository.findById(publicKey)!!

        val receiverPublicKey = PropertyHolder.get(AttoPublicKey::class.java, receiverShortId)

        val sendBlock = account.toAttoAccount().send(receiverPublicKey, defaultSendAmount)
        val sendTransaction = Transaction(
            block = sendBlock,
            signature = privateKey.sign(sendBlock.hash.value),
            work = AttoWork.Companion.work(thisNode.network, sendBlock.timestamp, account.lastTransactionHash)
        )

        logger.info { "Publishing $sendTransaction" }

        publish("THIS", sendTransaction)

        PropertyHolder.add(transactionShortId, sendTransaction)
    }

    @When("^change transaction (\\w+) from (\\w+) account to (\\w+) representative$")
    fun changeTransaction(transactionShortId: String, shortId: String, representativeShortId: String) = runBlocking {
        val privateKey = PropertyHolder.get(AttoPrivateKey::class.java, shortId)
        val publicKey = PropertyHolder.get(AttoPublicKey::class.java, shortId)
        val account = accountRepository.findById(publicKey)!!

        val representative = PropertyHolder.get(AttoPublicKey::class.java, representativeShortId)

        val changeBlock = account.toAttoAccount().change(representative)
        val changeTransaction = Transaction(
            block = changeBlock,
            signature = privateKey.sign(changeBlock.hash.value),
            work = AttoWork.Companion.work(thisNode.network, changeBlock.timestamp, account.lastTransactionHash),
        )

        val neighbour = PropertyHolder[Neighbour::class.java, shortId]

        publish(shortId, changeTransaction)

        PropertyHolder.add(transactionShortId, changeTransaction)
    }

    @Then("^transaction (\\w+) is confirmed$")
    fun checkConfirmed(transactionShortId: String) {
        checkConfirmed(transactionShortId, "THIS")
    }

    @Then("^transaction (\\w+) is confirmed for (\\w+) peer$")
    fun checkConfirmed(transactionShortId: String, shortId: String) {
        val expectedTransaction = PropertyHolder[Transaction::class.java, transactionShortId]

        waitUntilNonNull {
            getTransaction(shortId, expectedTransaction.hash)
        }
    }

    @Then("^matching open or receive transaction for transaction (\\w+) is confirmed$")
    fun checkMatchingConfirmed(transactionShortId: String) = runBlocking {
        val sendTransaction = PropertyHolder.get(Transaction::class.java, transactionShortId)
        val sendBlock = sendTransaction.block as AttoSendBlock

        val receiverPublicKey = sendBlock.receiverPublicKey

        val transaction = waitUntilNonNull {
            val account = getAccount("THIS", receiverPublicKey)?.toAttoAccount()
                ?: return@waitUntilNonNull null

            val transaction = getTransaction("THIS", account.lastTransactionHash)?.toAttoTransaction()
                ?: return@waitUntilNonNull null

            val block = transaction.block
            if (block !is ReceiveSupportBlock) {
                return@waitUntilNonNull null
            }

            if (block.sendHash != sendBlock.hash) {
                return@waitUntilNonNull null
            }

            return@waitUntilNonNull transaction
        }
        assertNotNull(transaction)
    }

    private fun getAccount(neighbourShortId: String, publicKey: AttoPublicKey): AccountDTO? {
        val neighbour = PropertyHolder[Neighbour::class.java, neighbourShortId]
        return webClient.get()
            .uri("http://localhost:${neighbour.httpPort}/accounts/{publicKey}", publicKey.toString())
            .retrieve()
            .onStatus({ it.value() == 404 }, { Mono.empty() })
            .bodyToMono<AccountDTO>()
            .block()
    }

    private fun getTransaction(neighbourShortId: String, hash: AttoHash): TransactionDTO? {
        val neighbour = PropertyHolder[Neighbour::class.java, neighbourShortId]
        return webClient.get()
            .uri("http://localhost:${neighbour.httpPort}/transactions/{hash}", hash.toString())
            .retrieve()
            .onStatus({ it.value() == 404 }, { Mono.empty() })
            .bodyToMono<TransactionDTO>()
            .block()
    }

    private fun publish(neighbourShortId: String, transaction: Transaction) {
        val neighbour = PropertyHolder[Neighbour::class.java, neighbourShortId]
        webClient
            .post()
            .uri("http://localhost:${neighbour.httpPort}/transactions")
            .bodyValue(transaction)
            .retrieve()
            .bodyToMono<Void>()
            .block()
    }


}