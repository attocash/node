package org.atto.node.transaction

import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.atto.commons.*
import org.atto.node.PropertyHolder
import org.atto.node.Waiter
import org.atto.node.account.AccountDTO
import org.atto.node.account.AccountRepository
import org.atto.node.node.Neighbour
import org.atto.protocol.AttoNode
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.time.Duration

class TransactionStepDefinition(
    private val thisNode: AttoNode,
    private val accountRepository: AccountRepository,
    private val webClient: WebClient
) {
    private val logger = KotlinLogging.logger {}

    private val defaultSendAmount = AttoAmount(4_500_000_000_000_000_000u)

    @When("^send transaction (\\w+) from (\\w+) account to (\\w+) account$")
    fun sendTransaction(transactionShortId: String, senderShortId: String, receiverShortId: String) = runBlocking {
        val privateKey = PropertyHolder.get(AttoPrivateKey::class.java, senderShortId)
        val publicKey = PropertyHolder.get(AttoPublicKey::class.java, senderShortId)
        val account = accountRepository.findById(publicKey)!!

        val receiverPublicKey = PropertyHolder.get(AttoPublicKey::class.java, receiverShortId)

        val sendBlock = account.toAttoAccount().send(receiverPublicKey, defaultSendAmount)
        val sendTransaction = Transaction(
            block = sendBlock,
            signature = privateKey.sign(sendBlock.hash),
            work = AttoWork.work(thisNode.network, sendBlock.timestamp, account.lastTransactionHash)
        )

        logger.info { "Publishing $sendTransaction" }

        publish("THIS", sendTransaction)

        PropertyHolder.add(transactionShortId, sendTransaction)
    }

    @When("^receive transaction (\\w+) from (\\w+) send transaction to (\\w+) account$")
    fun receiveTransaction(transactionShortId: String, sendTransactionShortId: String, receiverShortId: String) =
        runBlocking {
            val privateKey = PropertyHolder.get(AttoPrivateKey::class.java, receiverShortId)
            val publicKey = PropertyHolder.get(AttoPublicKey::class.java, receiverShortId)

            val sendTransaction = PropertyHolder.get(Transaction::class.java, sendTransactionShortId)
            val sendBlock = sendTransaction.block as AttoSendBlock

            val account = accountRepository.findById(publicKey)
            val transaction = if (account != null) {
                val receiveBlock = account.toAttoAccount().receive(sendBlock)
                Transaction(
                    block = receiveBlock,
                    signature = privateKey.sign(receiveBlock.hash),
                    work = AttoWork.work(thisNode.network, receiveBlock.timestamp, account.lastTransactionHash)
                )
            } else {
                val openBlock = AttoAccount.open(sendBlock.receiverPublicKey, sendBlock)
                Transaction(
                    block = openBlock,
                    signature = privateKey.sign(openBlock.hash),
                    work = AttoWork.work(thisNode.network, openBlock.timestamp, openBlock.publicKey)
                )
            }

            logger.info { "Publishing $transaction" }

            publish("THIS", transaction)

            PropertyHolder.add(transactionShortId, transaction)
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
            signature = privateKey.sign(changeBlock.hash),
            work = AttoWork.Companion.work(thisNode.network, changeBlock.timestamp, account.lastTransactionHash),
        )

        publish(shortId, changeTransaction)

        PropertyHolder.add(transactionShortId, changeTransaction)
    }

    @Then("^transaction (\\w+) is confirmed$")
    fun checkConfirmed(transactionShortId: String) {
        val expectedTransaction = PropertyHolder[Transaction::class.java, transactionShortId]
        for (neighbour in PropertyHolder.getAll(Neighbour::class.java)) {
            streamTransaction(neighbour, expectedTransaction.hash)
        }
    }

    @Then("^transaction (\\w+) is confirmed for (\\w+) peer$")
    fun checkConfirmed(transactionShortId: String, shortId: String) {
        val expectedTransaction = PropertyHolder[Transaction::class.java, transactionShortId]
        streamTransaction(PropertyHolder[Neighbour::class.java, shortId], expectedTransaction.hash)
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

    private fun streamTransaction(neighbour: Neighbour, hash: AttoHash): TransactionDTO? {
        return webClient.get()
            .uri("http://localhost:${neighbour.httpPort}/transactions/{hash}/stream", hash.toString())
            .retrieve()
            .onStatus({ it.value() == 404 }, { Mono.empty() })
            .bodyToMono<TransactionDTO>()
            .block(Duration.ofSeconds(Waiter.timeoutInSeconds))!!
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