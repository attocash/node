package cash.atto.node.transaction

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAccountEntry
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceivable
import cash.atto.commons.AttoTransaction
import cash.atto.commons.InMemorySigner
import cash.atto.commons.worker.AttoWorker
import cash.atto.commons.worker.cpu
import cash.atto.node.Neighbour
import cash.atto.node.PropertyHolder
import cash.atto.node.Waiter
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.time.Duration

class TransactionStepDefinition(
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
        val signer = PropertyHolder[InMemorySigner::class.java, senderShortId]
        val publicKey = PropertyHolder[AttoPublicKey::class.java, senderShortId]
        val account = getAccount(PropertyHolder[Neighbour::class.java, senderShortId], publicKey)!!

        val receiverPublicKeyAlgorithm = PropertyHolder[AttoAlgorithm::class.java, receiverShortId]
        val receiverPublicKey = PropertyHolder[AttoPublicKey::class.java, receiverShortId]

        val send = account.send(receiverPublicKeyAlgorithm, receiverPublicKey, defaultSendAmount)
        val sendBlock = send.first
        val sendTransaction =
            Transaction(
                block = sendBlock,
                signature = runBlocking { signer.sign(sendBlock.hash) },
                work = runBlocking { AttoWorker.cpu().work(sendBlock) },
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
        val signer = PropertyHolder.get(InMemorySigner::class.java, receiverShortId)
        val publicKey = PropertyHolder.get(AttoPublicKey::class.java, receiverShortId)

        val sendTransaction = PropertyHolder.get(Transaction::class.java, sendTransactionShortId)
        val receivable = streamReceivable(PropertyHolder[Neighbour::class.java, receiverShortId], publicKey, sendTransaction.hash)

        val account = getAccount(PropertyHolder[Neighbour::class.java, receiverShortId], publicKey)
        val transaction =
            if (account != null) {
                val receive = account.receive(receivable)
                val receiveBlock = receive.first
                Transaction(
                    block = receiveBlock,
                    signature = runBlocking { signer.sign(receiveBlock.hash) },
                    work = runBlocking { AttoWorker.cpu().work(receiveBlock) },
                )
            } else {
                val open =
                    AttoAccount.open(receivable.receiverAlgorithm, receivable.receiverPublicKey, receivable, sendTransaction.block.network)
                val openBlock = open.first
                Transaction(
                    block = openBlock,
                    signature = runBlocking { signer.sign(openBlock.hash) },
                    work = runBlocking { AttoWorker.cpu().work(openBlock) },
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
        val signer = PropertyHolder.get(InMemorySigner::class.java, shortId)
        val publicKey = PropertyHolder.get(AttoPublicKey::class.java, shortId)
        val account = getAccount(PropertyHolder[Neighbour::class.java, shortId], publicKey)!!

        val representativePublicKey = PropertyHolder.get(AttoPublicKey::class.java, representativeShortId)

        val change = account.change(AttoAlgorithm.V1, representativePublicKey)
        val changeBlock = change.first
        val changeTransaction =
            Transaction(
                block = changeBlock,
                signature = runBlocking { signer.sign(changeBlock.hash) },
                work = runBlocking { AttoWorker.cpu().work(changeBlock) },
            )

        publish(shortId, changeTransaction)

        PropertyHolder.add(transactionShortId, changeTransaction)
    }

    @Then("^transaction (\\w+) is confirmed$")
    fun checkConfirmed(transactionShortId: String) {
        val expectedTransaction = PropertyHolder[Transaction::class.java, transactionShortId]
        for (neighbour in PropertyHolder.getAll(Neighbour::class.java)) {
            streamTransaction(neighbour, expectedTransaction.hash)
            streamAccountEntries(neighbour, expectedTransaction.publicKey, expectedTransaction.hash, expectedTransaction.height)
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
            .doOnSubscribe { logger.info { "Started streaming transaction $hash" } }
            .doOnTerminate { logger.info { "Stopped streaming transaction $hash" } }
            .block(Duration.ofSeconds(Waiter.timeoutInSeconds))!!

    private fun streamAccountEntries(
        neighbour: Neighbour,
        publicKey: AttoPublicKey,
        hash: AttoHash,
        height: AttoHeight,
    ): AttoAccountEntry {
        val url = "http://localhost:${neighbour.httpPort}/accounts/$publicKey/entries/stream?fromHeight=$height&toHeight=$height"
        return webClient
            .get()
            .uri(url)
            .retrieve()
            .onStatus({ it.value() == 404 }, { Mono.empty() })
            .bodyToFlux<AttoAccountEntry>()
            .filter { it.hash == hash }
            .blockFirst(Duration.ofSeconds(Waiter.timeoutInSeconds))!!
    }

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
