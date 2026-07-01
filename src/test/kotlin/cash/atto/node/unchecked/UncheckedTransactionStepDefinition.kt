package cash.atto.node.unchecked

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoTransaction
import cash.atto.node.Neighbour
import cash.atto.node.PropertyHolder
import cash.atto.node.Waiter
import cash.atto.node.transaction.Transaction
import io.cucumber.java.en.When
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.seconds

class UncheckedTransactionStepDefinition(
    private val webClient: WebClient,
) {
    private val bootstrapDiscoveryTimeoutInSeconds = 180L

    @When("^peer (\\w+) finds (\\w+) unchecked transactions$")
    fun assertUncheckedCount(
        shortId: String,
        count: Int,
    ) {
        val neighbour = PropertyHolder[Neighbour::class.java, shortId]

        Waiter.waitUntilTrue {
            val uncheckedTransactions = findUncheckedTransactions(neighbour)
            uncheckedTransactions.size == count
        }
    }

    @When("^peer (\\w+) unchecked transactions are processed$")
    fun assertProcessingFinished(shortId: String) {
        val neighbour = PropertyHolder[Neighbour::class.java, shortId]

        Waiter.waitUntilTrue {
            processUnchecked(neighbour)
            val uncheckedTransactions = findUncheckedTransactions(neighbour)
            uncheckedTransactions.isEmpty()
        }
    }

    @When("^peer (\\w+) look for missing transactions$")
    fun lookMissingTransactions(shortId: String) {
        val neighbour = PropertyHolder[Neighbour::class.java, shortId]
        discoverGaps(neighbour)
    }

    @When("^peer (\\w+) broadcast last sample$")
    fun broadcastLastTransactions(shortId: String) {
        val neighbour = PropertyHolder[Neighbour::class.java, shortId]
        broadcastLastTransactions(neighbour)
    }

    @When("^peer (\\w+) broadcasts last sample until peer (\\w+) receives transaction (\\w+)$")
    fun broadcastLastTransactionsUntilReceived(
        sourceShortId: String,
        targetShortId: String,
        transactionShortId: String,
    ) {
        val source = PropertyHolder[Neighbour::class.java, sourceShortId]
        val target = PropertyHolder[Neighbour::class.java, targetShortId]
        val transaction = PropertyHolder[Transaction::class.java, transactionShortId]

        val deadline = System.nanoTime() + bootstrapDiscoveryTimeoutInSeconds.seconds.inWholeNanoseconds
        var lastFailure: Throwable? = null

        while (System.nanoTime() < deadline) {
            val received =
                runCatching {
                    if (hasTransaction(target, transaction.hash)) {
                        return
                    }

                    processUnchecked(source)
                    refreshNetwork(source)
                    broadcastLastTransactions(source)

                    val settleDeadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
                    while (System.nanoTime() < settleDeadline) {
                        settleDiscoveries(target)

                        if (hasTransaction(target, transaction.hash)) {
                            return
                        }

                        Thread.sleep(250)
                    }

                    false
                }.onFailure {
                    lastFailure = it
                }.getOrDefault(false)

            if (received) {
                return
            }

            Thread.sleep(100)
        }

        error(
            "Peer $targetShortId did not receive transaction $transactionShortId (${transaction.hash}) " +
                "from $sourceShortId last sample within ${bootstrapDiscoveryTimeoutInSeconds}s. " +
                "Source state: ${describeDiscoveryState(source, transaction.hash)}. " +
                "Target state: ${describeDiscoveryState(target, transaction.hash)}. " +
                "Last failure: ${lastFailure?.javaClass?.simpleName ?: "none"} ${lastFailure?.message ?: ""}",
        )
    }

    @When("^peer (\\w+) settles discoveries until transaction (\\w+) is received$")
    fun settleDiscoveriesUntilReceived(
        targetShortId: String,
        transactionShortId: String,
    ) {
        val target = PropertyHolder[Neighbour::class.java, targetShortId]
        val transaction = PropertyHolder[Transaction::class.java, transactionShortId]

        val deadline = System.nanoTime() + bootstrapDiscoveryTimeoutInSeconds.seconds.inWholeNanoseconds
        var lastFailure: Throwable? = null

        while (System.nanoTime() < deadline) {
            runCatching {
                refreshNetwork(target)
                settleDiscoveries(target)

                if (hasTransaction(target, transaction.hash)) {
                    return
                }
            }.onFailure {
                lastFailure = it
            }

            Thread.sleep(250)
        }

        error(
            "Peer $targetShortId did not receive transaction $transactionShortId (${transaction.hash}) " +
                "after settling discoveries within ${bootstrapDiscoveryTimeoutInSeconds}s. " +
                "Target state: ${describeDiscoveryState(target, transaction.hash)}. " +
                "Last failure: ${lastFailure?.javaClass?.simpleName ?: "none"} ${lastFailure?.message ?: ""}",
        )
    }

    private fun refreshNetwork(neighbour: Neighbour) {
        post(neighbour, "/nodes/bootstrap")
    }

    private fun broadcastLastTransactions(neighbour: Neighbour) {
        post(neighbour, "/unchecked-transactions/discoveries/last")
    }

    private fun discoverGaps(neighbour: Neighbour) {
        post(neighbour, "/unchecked-transactions/discoveries/gap")
    }

    private fun settleDiscoveries(neighbour: Neighbour) {
        post(neighbour, "/unchecked-transactions/discoveries/settle")
    }

    private fun hasTransaction(
        neighbour: Neighbour,
        hash: AttoHash,
    ): Boolean =
        webClient
            .get()
            .uri("http://localhost:${neighbour.httpPort}/transactions/{hash}", hash.toString())
            .retrieve()
            .onStatus({ it.value() == 404 }, { Mono.empty() })
            .bodyToMono<AttoTransaction>()
            .timeout(Duration.ofSeconds(1))
            .onErrorResume(TimeoutException::class.java) { Mono.empty() }
            .blockOptional(Duration.ofSeconds(2))
            .isPresent

    private fun describeDiscoveryState(
        neighbour: Neighbour,
        expectedHash: AttoHash,
    ): String =
        runCatching {
            val uncheckedTransactions = findUncheckedTransactions(neighbour)
            val uncheckedHashes = uncheckedTransactions.map { it.hash }
            "hasTransaction=${hasTransaction(neighbour, expectedHash)}, " +
                "uncheckedCount=${uncheckedTransactions.size}, " +
                "uncheckedHasExpected=${expectedHash in uncheckedHashes}, " +
                "uncheckedHashes=$uncheckedHashes"
        }.getOrElse {
            "unavailable (${it.javaClass.simpleName}: ${it.message})"
        }

    private fun processUnchecked(neighbour: Neighbour) {
        post(neighbour, "/unchecked-transactions")
    }

    private fun post(
        neighbour: Neighbour,
        path: String,
    ) {
        webClient
            .post()
            .uri("http://localhost:${neighbour.httpPort}$path")
            .retrieve()
            .toBodilessEntity()
            .timeout(Duration.ofSeconds(5))
            .block(Duration.ofSeconds(6))
    }

    private fun findUncheckedTransactions(neighbour: Neighbour): List<AttoTransaction> =
        webClient
            .get()
            .uri("http://localhost:${neighbour.httpPort}/unchecked-transactions")
            .retrieve()
            .bodyToMono<List<AttoTransaction>>()
            .block()!!
}
