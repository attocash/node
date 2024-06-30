package cash.atto.node.transaction

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceivable
import cash.atto.commons.AttoTransaction
import cash.atto.commons.AttoWork
import cash.atto.commons.sign
import cash.atto.node.Neighbour
import cash.atto.node.PropertyHolder
import cash.atto.node.Waiter
import cash.atto.protocol.AttoNode
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.time.Duration

class TransactionStepDefinition(
    private val thisNode: AttoNode,
    private val webClient: WebClient,
) {
    private val logger = KotlinLogging.logger {}

    private val defaultSendAmount = AttoAmount(4_500_000_000_000_000_000u)

    @When("^send transaction (\\w+) from (\\w+) account to (\\w+) account$")
    fun sendTransaction(
        transactionShortId: String,
        senderShortId: String,
        receiverShortId: String,
    ) {
        val privateKey = PropertyHolder[AttoPrivateKey::class.java, senderShortId]
        val publicKey = PropertyHolder[AttoPublicKey::class.java, senderShortId]
        val account = getAccount(PropertyHolder[Neighbour::class.java, senderShortId], publicKey)!!

        val receiverPublicKeyAlgorithm = PropertyHolder[AttoAlgorithm::class.java, receiverShortId]
        val receiverPublicKey = PropertyHolder[AttoPublicKey::class.java, receiverShortId]

        val sendBlock = account.send(receiverPublicKeyAlgorithm, receiverPublicKey, defaultSendAmount)
        val sendTransaction =
            Transaction(
                block = sendBlock,
                signature = privateKey.sign(sendBlock.hash),
                work = AttoWork.work(thisNode.network, sendBlock.timestamp, account.lastTransactionHash),
            )

        logger.info { "Publishing $sendTransaction" }

        publish("THIS", sendTransaction)

        PropertyHolder.add(transactionShortId, sendTransaction)
    }

    @When("^receive transaction (\\w+) from (\\w+) send transaction to (\\w+) account$")
    fun receiveTransaction(
        transactionShortId: String,
        sendTransactionShortId: String,
        receiverShortId: String,
    ) {
        val privateKey = PropertyHolder.get(AttoPrivateKey::class.java, receiverShortId)
        val publicKey = PropertyHolder.get(AttoPublicKey::class.java, receiverShortId)

        val sendTransaction = PropertyHolder.get(Transaction::class.java, sendTransactionShortId)
        val receivable = streamReceivable(PropertyHolder[Neighbour::class.java, receiverShortId], publicKey, sendTransaction.hash)

        val account = getAccount(PropertyHolder[Neighbour::class.java, receiverShortId], publicKey)
        val transaction =
            if (account != null) {
                val receiveBlock = account.receive(receivable)
                Transaction(
                    block = receiveBlock,
                    signature = privateKey.sign(receiveBlock.hash),
                    work = AttoWork.work(thisNode.network, receiveBlock.timestamp, account.lastTransactionHash),
                )
            } else {
                val openBlock = AttoAccount.open(receivable.receiverPublicKey, receivable)
                Transaction(
                    block = openBlock,
                    signature = privateKey.sign(openBlock.hash),
                    work = AttoWork.work(thisNode.network, openBlock.timestamp, openBlock.publicKey),
                )
            }

        logger.info { "Publishing $transaction" }

        publish("THIS", transaction)

        PropertyHolder.add(transactionShortId, transaction)
    }

    @When("^change transaction (\\w+) from (\\w+) account to (\\w+) representative$")
    fun changeTransaction(
        transactionShortId: String,
        shortId: String,
        representativeShortId: String,
    ) {
        val privateKey = PropertyHolder.get(AttoPrivateKey::class.java, shortId)
        val publicKey = PropertyHolder.get(AttoPublicKey::class.java, shortId)
        val account = getAccount(PropertyHolder[Neighbour::class.java, shortId], publicKey)!!

        val representative = PropertyHolder.get(AttoPublicKey::class.java, representativeShortId)

        val changeBlock = account.change(representative)
        val changeTransaction =
            Transaction(
                block = changeBlock,
                signature = privateKey.sign(changeBlock.hash),
                work = AttoWork.work(thisNode.network, changeBlock.timestamp, account.lastTransactionHash),
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
    fun checkConfirmed(
        transactionShortId: String,
        shortId: String,
    ) {
        val expectedTransaction = PropertyHolder[Transaction::class.java, transactionShortId]
        streamTransaction(PropertyHolder[Neighbour::class.java, shortId], expectedTransaction.hash)
    }

    private fun getAccount(
        neighbour: Neighbour,
        publicKey: AttoPublicKey,
    ): AttoAccount? =
        webClient
            .get()
            .uri("http://localhost:${neighbour.httpPort}/accounts/{publicKey}", publicKey.toString())
            .retrieve()
            .onStatus({ it.value() == 404 }, { Mono.empty() })
            .bodyToMono<AttoAccount>()
            .blockOptional()
            .orElse(null)

    private fun streamTransaction(
        neighbour: Neighbour,
        hash: AttoHash,
    ): AttoTransaction =
        webClient
            .get()
            .uri("http://localhost:${neighbour.httpPort}/transactions/{hash}/stream", hash.toString())
            .retrieve()
            .onStatus({ it.value() == 404 }, { Mono.empty() })
            .bodyToMono<AttoTransaction>()
            .block(Duration.ofSeconds(Waiter.timeoutInSeconds))!!

    private fun streamReceivable(
        neighbour: Neighbour,
        publicKey: AttoPublicKey,
        sendHash: AttoHash,
    ): AttoReceivable =
        webClient
            .get()
            .uri("http://localhost:${neighbour.httpPort}/accounts/{publicKey}/receivables/stream", publicKey.toString())
            .retrieve()
            .onStatus({ it.value() == 404 }, { Mono.empty() })
            .bodyToFlux<AttoReceivable>()
            .filter { it.hash == sendHash }
            .blockFirst(Duration.ofSeconds(Waiter.timeoutInSeconds))!!

    private fun publish(
        neighbourShortId: String,
        transaction: Transaction,
    ) {
        val neighbour = PropertyHolder[Neighbour::class.java, neighbourShortId]
        webClient
            .post()
            .uri("http://localhost:${neighbour.httpPort}/transactions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(transaction.toAttoTransaction())
            .retrieve()
            .bodyToMono<Void>()
            .block()
    }
}
