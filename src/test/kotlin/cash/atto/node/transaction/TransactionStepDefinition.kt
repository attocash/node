package cash.atto.node.transaction

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAccountEntry
import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceivable
import cash.atto.commons.AttoTransaction
import cash.atto.commons.InMemorySigner
import cash.atto.commons.worker.AttoWorker
import cash.atto.node.Neighbour
import cash.atto.node.PropertyHolder
import cash.atto.node.Waiter
import cash.atto.node.Waiter.waitUntilNonNull
import cash.atto.node.account.LegacyAccountResponse
import cash.atto.node.account.entry.LegacyAccountEntryResponse
import cash.atto.node.receivable.LegacyReceivableResponse
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlin.jvm.optionals.getOrNull

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

        val send = account.send(AttoAddress(receiverPublicKeyAlgorithm, receiverPublicKey), defaultSendAmount)
        val sendBlock = send.first
        val sendTransaction =
            Transaction(
                block = sendBlock,
                signature = runBlocking { signer.sign(sendBlock.hash) },
                work = runBlocking { AttoWorker.cpu().work(sendBlock) },
            )

        logger.info { "Publishing $sendTransaction" }

        publish(senderShortId, sendTransaction)

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
                    AttoAccount.open(receivable.receiverAddress, receivable)
                val openBlock = open.first
                Transaction(
                    block = openBlock,
                    signature = runBlocking { signer.sign(openBlock.hash) },
                    work = runBlocking { AttoWorker.cpu().work(openBlock) },
                )
            }

        logger.info { "Publishing $transaction" }

        publish(receiverShortId, transaction)

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

        val change = account.change(AttoAddress(AttoAlgorithm.V1, representativePublicKey))
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

    internal fun getAccount(
        neighbour: Neighbour,
        publicKey: AttoPublicKey,
    ): AttoAccount? {
        val response =
            webClient
                .get()
                .uri(
                    "http://localhost:${neighbour.httpPort}" + if (neighbour.released) "/accounts/{account}" else "/v2/accounts/{account}",
                    accountIdentifier(neighbour, publicKey),
                ).retrieve()
                .onStatus({ it.value() == 404 }, { Mono.empty() })
        return if (neighbour.released) {
            response.bodyToMono<LegacyAccountResponse>().map {
                AttoAccount(
                    address = AttoAddress(it.algorithm, it.publicKey),
                    network = it.network,
                    version = it.version,
                    height = it.height,
                    balance = it.balance,
                    lastTransactionHash = it.lastTransactionHash,
                    lastTransactionTimestamp = it.lastTransactionTimestamp,
                    representativeAddress = AttoAddress(it.representativeAlgorithm, it.representativePublicKey),
                )
            }
        } else {
            response.bodyToMono<AttoAccount>()
        }.blockOptional().orElse(null)
    }

    internal fun streamTransaction(
        neighbour: Neighbour,
        hash: AttoHash,
    ): AttoTransaction {
        try {
            return waitUntilNonNull {
                val response =
                    webClient
                        .get()
                        .uri(
                            "http://localhost:${neighbour.httpPort}${if (neighbour.released) "" else "/v2"}/transactions/{hash}/stream",
                            hash.toString(),
                        ).retrieve()
                        .onStatus({ it.value() == 404 }, { Mono.empty() })
                val transaction =
                    if (neighbour.released) {
                        response.bodyToMono<LegacyTransactionRequest>().map { it.transaction }
                    } else {
                        response
                            .bodyToFlux<String>()
                            .filter { it.isNotBlank() }
                            .map { Json.decodeFromString<AttoTransaction>(it) }
                            .single()
                    }
                transaction
                    .timeout(Duration.ofSeconds(1))
                    .onErrorResume(TimeoutException::class.java) { Mono.empty() }
                    .doOnSubscribe { logger.info { "Started streaming transaction $hash" } }
                    .doOnTerminate { logger.info { "Stopped streaming transaction $hash" } }
                    .blockOptional()
                    .getOrNull()
            }!!
        } catch (e: Exception) {
            throw IllegalStateException("Exception while streaming transaction $hash (neighbour=$neighbour)", e)
        }
    }

    internal fun streamAccountEntries(
        neighbour: Neighbour,
        publicKey: AttoPublicKey,
        hash: AttoHash,
        height: AttoHeight,
    ): AttoAccountEntry {
        val prefix = if (neighbour.released) "" else "/v2"
        val url = "http://localhost:${neighbour.httpPort}$prefix/accounts/{account}/entries/stream?fromHeight=$height&toHeight=$height"
        try {
            val response =
                webClient
                    .get()
                    .uri(url, accountIdentifier(neighbour, publicKey))
                    .retrieve()
                    .onStatus({ it.value() == 404 }, { Mono.empty() })
            val entries =
                if (neighbour.released) {
                    response.bodyToFlux<LegacyAccountEntryResponse>().map {
                        AttoAccountEntry(
                            hash = it.hash,
                            address = it.address,
                            height = it.height,
                            blockType = it.blockType,
                            subjectAddress = it.subjectAddress,
                            previousBalance = it.previousBalance,
                            balance = it.balance,
                            timestamp = it.timestamp,
                        )
                    }
                } else {
                    response.bodyToFlux<String>().filter { it.isNotBlank() }.map { Json.decodeFromString<AttoAccountEntry>(it) }
                }
            return entries
                .filter { it.hash == hash }
                .blockFirst(Duration.ofSeconds(Waiter.timeoutInSeconds))!!
        } catch (e: Exception) {
            throw IllegalStateException("Exception while streaming account entry $hash (neighbour=$neighbour)", e)
        }
    }

    internal fun streamReceivable(
        neighbour: Neighbour,
        publicKey: AttoPublicKey,
        sendHash: AttoHash,
    ): AttoReceivable {
        val prefix = if (neighbour.released) "" else "/v2"
        val response =
            webClient
                .get()
                .uri(
                    "http://localhost:${neighbour.httpPort}$prefix/accounts/{account}/receivables/stream",
                    accountIdentifier(neighbour, publicKey),
                ).retrieve()
                .onStatus({ it.value() == 404 }, { Mono.empty() })
        val receivables =
            if (neighbour.released) {
                response.bodyToFlux<LegacyReceivableResponse>().map {
                    AttoReceivable(
                        network = it.network,
                        hash = it.hash,
                        version = it.version,
                        address = AttoAddress(it.algorithm, it.publicKey),
                        timestamp = it.timestamp,
                        receiverAddress = AttoAddress(it.receiverAlgorithm, it.receiverPublicKey),
                        amount = it.amount,
                    )
                }
            } else {
                response.bodyToFlux<String>().filter { it.isNotBlank() }.map { Json.decodeFromString<AttoReceivable>(it) }
            }
        return receivables
            .filter { it.hash == sendHash }
            .blockFirst(Duration.ofSeconds(Waiter.timeoutInSeconds))!!
    }

    private fun accountIdentifier(
        neighbour: Neighbour,
        publicKey: AttoPublicKey,
    ): String = if (neighbour.released) publicKey.toString() else AttoAddress(AttoAlgorithm.V1, publicKey).toString()

    private fun publish(
        neighbourShortId: String,
        transaction: Transaction,
    ) = publish(PropertyHolder[Neighbour::class.java, neighbourShortId], transaction)

    internal fun publish(
        neighbour: Neighbour,
        transaction: Transaction,
    ) {
        val canonical = transaction.toAttoTransaction()
        val response =
            webClient
                .post()
                .uri("http://localhost:${neighbour.httpPort}${if (neighbour.released) "" else "/v2"}/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(if (neighbour.released) canonical.toLegacyResponse() else canonical)
                .retrieve()
        val confirmed =
            if (neighbour.released) {
                response.bodyToMono<LegacyTransactionRequest>().map { it.transaction }
            } else {
                response
                    .bodyToFlux<String>()
                    .filter { it.isNotBlank() }
                    .map { Json.decodeFromString<AttoTransaction>(it) }
                    .single()
            }.block()
        check(confirmed?.hash == transaction.hash) { "Publication did not confirm ${transaction.hash} (neighbour=$neighbour)" }
    }
}
