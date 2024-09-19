package cash.atto.node.network.peer

import cash.atto.commons.AttoPublicKey
import cash.atto.node.Neighbour
import cash.atto.node.NodeStepDefinition
import cash.atto.node.PropertyHolder
import cash.atto.node.Waiter.waitUntilNonNull
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

class PeerStepDefinition(
    private val nodeStepDefinition: NodeStepDefinition,
    private val handshakeService: HandshakeService,
    private val peerManager: PeerManager,
    private val webClient: WebClient,
) {
    @Given("^the peer (\\w+)$")
    fun startPeer(shortId: String) {
        nodeStepDefinition.startNeighbour(shortId)
        nodeStepDefinition.setAsDefaultNode()
        runBlocking {
            handshakeService.startDefaultHandshake()
        }

        checkPeer("THIS", shortId)
        checkPeer(shortId, "THIS")

        peerManager.sendKeepAlive()
    }

    @When("default handshake starts")
    fun startDefaultHandshake() {
        runBlocking {
            handshakeService.startDefaultHandshake()
        }
    }

    @Then("^(\\w+) node is (\\w+) node peer$")
    fun checkPeer(
        sourceNodeShortId: String,
        peerNodeShortId: String,
    ) {
        val sourceNeighbour = PropertyHolder[Neighbour::class.java, sourceNodeShortId]
        val peerPublicKey = PropertyHolder[AttoPublicKey::class.java, peerNodeShortId]

        waitUntilNonNull {
            webClient
                .get()
                .uri("http://localhost:${sourceNeighbour.httpPort}/nodes/peers")
                .retrieve()
                .bodyToMono<List<AttoPublicKey>>()
                .flatMapIterable { it }
                .filter { it == peerPublicKey }
                .blockFirst()
        }
    }
}
