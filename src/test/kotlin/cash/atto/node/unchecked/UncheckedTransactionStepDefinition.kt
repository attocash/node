package cash.atto.node.unchecked

import cash.atto.commons.AttoTransaction
import cash.atto.node.Neighbour
import cash.atto.node.PropertyHolder
import cash.atto.node.Waiter
import io.cucumber.java.en.When
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import org.springframework.web.reactive.function.client.bodyToMono

class UncheckedTransactionStepDefinition(
    private val webClient: WebClient,
) {
    private val logger = KotlinLogging.logger {}

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
        webClient
            .post()
            .uri("http://localhost:${neighbour.httpPort}/unchecked-transactions/discoveries/gap")
            .retrieve()
            .bodyToFlux<Void>()
            .blockFirst()
    }

    @When("^peer (\\w+) broadcast last sample$")
    fun broadcastLastTransactions(shortId: String) {
        val neighbour = PropertyHolder[Neighbour::class.java, shortId]
        webClient
            .post()
            .uri("http://localhost:${neighbour.httpPort}/unchecked-transactions/discoveries/last")
            .retrieve()
            .bodyToFlux<Void>()
            .blockFirst()
    }

    private fun processUnchecked(neighbour: Neighbour) {
        webClient
            .post()
            .uri("http://localhost:${neighbour.httpPort}/unchecked-transactions")
            .retrieve()
            .bodyToFlux<Void>()
            .blockFirst()
    }

    private fun findUncheckedTransactions(neighbour: Neighbour): List<AttoTransaction> =
        webClient
            .get()
            .uri("http://localhost:${neighbour.httpPort}/unchecked-transactions")
            .retrieve()
            .bodyToMono<List<AttoTransaction>>()
            .block()!!
}
