package atto.node.bootstrap.unchecked

import atto.node.PropertyHolder
import atto.node.Waiter
import atto.node.node.Neighbour
import atto.node.transaction.TransactionDTO
import cash.atto.commons.AttoTransaction
import io.cucumber.java.en.When
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux


class UncheckedTransactionDefinition(
    private val webClient: WebClient
) {
    private val logger = KotlinLogging.logger {}

    @When("^peer (\\w+) finds (\\w+) unchecked transactions$")
    fun assertUncheckedCount(shortId: String, count: Int) {
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
            webClient.post()
                .uri("http://localhost:${neighbour.httpPort}/transactions/uncheckeds")
                .retrieve()
                .bodyToFlux<Void>()
                .blockFirst()
            val uncheckedTransactions = findUncheckedTransactions(neighbour)
            uncheckedTransactions.isEmpty()
        }
    }

    @When("^peer (\\w+) look for missing transactions$")
    fun lookMissingTransactions(shortId: String) {
        val neighbour = PropertyHolder[Neighbour::class.java, shortId]
        webClient.post()
            .uri("http://localhost:${neighbour.httpPort}/transactions/uncheckeds/discoveries/gap")
            .retrieve()
            .bodyToFlux<Void>()
            .blockFirst()
    }


    @When("^peer (\\w+) broadcast last sample$")
    fun broadcastLastTransactions(shortId: String) {
        val neighbour = PropertyHolder[Neighbour::class.java, shortId]
        webClient.post()
            .uri("http://localhost:${neighbour.httpPort}/transactions/uncheckeds/discoveries/last")
            .retrieve()
            .bodyToFlux<Void>()
            .blockFirst()
    }

    private fun findUncheckedTransactions(neighbour: Neighbour): List<AttoTransaction> {
        return webClient.get()
            .uri("http://localhost:${neighbour.httpPort}/transactions/uncheckeds")
            .retrieve()
            .bodyToFlux<TransactionDTO>()
            .map { it.toAttoTransaction() }
            .collectList()
            .block()!!
    }


}