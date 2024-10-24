package cash.atto.node.network.peer

import cash.atto.commons.AttoPublicKey
import cash.atto.node.Neighbour
import cash.atto.node.NodeStepDefinition
import cash.atto.node.PropertyHolder
import cash.atto.node.Waiter.waitUntilNonNull
import cash.atto.node.network.NetworkProcessor
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

class PeerStepDefinition(
    private val nodeStepDefinition: NodeStepDefinition,
    private val networkProcessor: NetworkProcessor,
    private val webClient: WebClient,
) {
    private val logger = KotlinLogging.logger {}

    @Given("^the peer (\\w+)$")
    fun startPeer(shortId: String) {
        nodeStepDefinition.startNeighbour(shortId)
        nodeStepDefinition.setAsDefaultNode()
        runBlocking {
            networkProcessor.boostrap()
        }

        checkPeer("THIS", shortId)
        checkPeer(shortId, "THIS")
    }

    @When("default handshake starts")
    fun startDefaultHandshake() {
        runBlocking {
            networkProcessor.boostrap()
        }
    }

    @Then("^(\\w+) node is (\\w+) node peer$")
    fun checkPeer(
        sourceNodeShortId: String,
        peerNodeShortId: String,
    ) {
        val sourceNeighbour = PropertyHolder[Neighbour::class.java, sourceNodeShortId]
        val peerPublicKey = PropertyHolder[AttoPublicKey::class.java, peerNodeShortId]

        logger.info { "Checking if $sourceNeighbour is connected to $peerPublicKey..." }

        waitUntilNonNull {
            webClient
                .get()
                .uri("http://localhost:${sourceNeighbour.httpPort}/nodes/peers")
                .retrieve()
                .bodyToMono<List<AttoPublicKey>>()
                .doOnNext { println("felipe $it") }
                .flatMapIterable { it }
                .filter { it == peerPublicKey }
                .blockFirst()
        }
    }
}
