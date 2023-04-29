package atto.node.network.peer

import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import atto.commons.AttoPublicKey
import atto.node.PropertyHolder
import atto.node.Waiter.waitUntilNonNull
import atto.node.node.NodeStepDefinition
import org.junit.jupiter.api.Assertions.assertNotNull

class PeerStepDefinition(
    private val nodeStepDefinition: NodeStepDefinition,
    private val handshakeService: HandshakeService,
    private val peerManager: PeerManager
) {


    @Given("^the peer (\\w+)$")
    fun startPeer(shortId: String) {
        nodeStepDefinition.startNeighbour(shortId)
        nodeStepDefinition.setAsDefaultNode()
        handshakeService.startDefaultHandshake()

        checkPeer(shortId)

        peerManager.sendKeepAlive()

    }

    @When("default handshake starts")
    fun startDefaultHandshake() {
        handshakeService.startDefaultHandshake()
    }

    @Then("^THIS node is (\\w+) node peer$")
    fun checkPeer(nodeShortId: String) {
        val publicKey = PropertyHolder.get(AttoPublicKey::class.java, nodeShortId)
        val peer = waitUntilNonNull {
            peerManager.getPeers().find { it.node.publicKey == publicKey }
        }
        assertNotNull(peer)
    }
}