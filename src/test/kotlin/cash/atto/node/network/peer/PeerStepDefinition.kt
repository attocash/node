package cash.atto.node.network.peer

import cash.atto.commons.AttoPublicKey
import cash.atto.node.Neighbour
import cash.atto.node.NodeStepDefinition
import cash.atto.node.PropertyHolder
import cash.atto.node.Waiter.waitUntilNonNull
import cash.atto.node.Waiter.waitUntilTrue
import cash.atto.node.network.NetworkProcessor
import cash.atto.node.network.NetworkProperties
import cash.atto.node.network.NodeConnectionManager
import cash.atto.node.network.NodeDisconnected
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.PayloadApplicationEvent
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue

class PeerStepDefinition(
    private val nodeStepDefinition: NodeStepDefinition,
    private val networkProcessor: NetworkProcessor,
    private val webClient: WebClient,
    private val connectionManager: NodeConnectionManager,
    private val networkProperties: NetworkProperties,
    private val context: ConfigurableApplicationContext,
) {
    private val logger = KotlinLogging.logger {}
    private val bootstrapPeerTimeoutInSeconds = 180L
    private val disconnections = ConcurrentLinkedQueue<NodeDisconnected>()
    private val disconnectListener =
        object : ApplicationListener<PayloadApplicationEvent<NodeDisconnected>> {
            override fun supportsAsyncExecution(): Boolean = false

            override fun onApplicationEvent(event: PayloadApplicationEvent<NodeDisconnected>) {
                disconnections.add(event.payload)
            }
        }

    @After
    fun stopObservingDisconnections() {
        context.removeApplicationListener(disconnectListener)
    }

    @Given("the peer {word} connected by {word}")
    fun startPeer(
        shortId: String,
        initiator: String,
    ) {
        context.addApplicationListener(disconnectListener)
        when (initiator) {
            "THIS" -> {
                startPeer(shortId)
            }

            shortId -> {
                val thisNeighbour = PropertyHolder[Neighbour::class.java, "THIS"]
                nodeStepDefinition.startNeighbour(shortId, listOf("ws://localhost:${thisNeighbour.websocketPort}"))
                checkPeer("THIS", shortId)
                checkPeer(shortId, "THIS")
            }

            else -> {
                error("Unknown connection initiator: $initiator")
            }
        }
    }

    @Given("^the peer (\\w+)$")
    fun startPeer(shortId: String) {
        nodeStepDefinition.startNeighbour(shortId)
        nodeStepDefinition.setAsDefaultNode()
        runBlocking {
            networkProcessor.bootstrap()
        }

        checkPeer("THIS", shortId)
        checkPeer(shortId, "THIS")
    }

    @When("default handshake starts")
    fun startDefaultHandshake() {
        runBlocking {
            networkProcessor.bootstrap()
        }
    }

    @Then("^(\\w+) node is (\\w+) node peer$")
    fun checkPeer(
        sourceNodeShortId: String,
        peerNodeShortId: String,
    ) {
        val peerPublicKey = PropertyHolder[AttoPublicKey::class.java, peerNodeShortId]

        logger.info { "Checking if $sourceNodeShortId is connected to $peerPublicKey..." }

        waitUntilNonNull(bootstrapPeerTimeoutInSeconds) {
            findPeer(sourceNodeShortId, peerPublicKey)
        }
    }

    @Then("peer {word} stays connected beyond the handshake deadline")
    fun checkStableConnection(shortId: String) {
        val publicKey = PropertyHolder[AttoPublicKey::class.java, shortId]
        val disconnectsBefore = disconnectCount(publicKey)
        val deadline = System.nanoTime() + Duration.ofMillis(5_200).toNanos()

        // Both nodes automatically reconnect; an eventual peer check alone can conceal a five-second session expiry.
        while (System.nanoTime() < deadline) {
            check(publicKey in connectionManager.connectedPublicKeys) { "Peer $shortId disconnected before the deadline" }
            check(disconnectCount(publicKey) == disconnectsBefore) { "Peer $shortId reconnected during the stability interval" }
            Thread.sleep(100)
        }
        check(publicKey in connectionManager.connectedPublicKeys) { "Peer $shortId disconnected before the deadline" }
        check(disconnectCount(publicKey) == disconnectsBefore) { "Peer $shortId reconnected during the stability interval" }
        checkPeer(shortId, "THIS")
    }

    @When("peer {word} reconnects after its connection is closed")
    fun reconnect(shortId: String) {
        val neighbour = PropertyHolder[Neighbour::class.java, shortId]
        val publicKey = PropertyHolder[AttoPublicKey::class.java, shortId]
        val publicUri = "ws://localhost:${neighbour.websocketPort}"
        val disconnectsBefore = disconnectCount(publicKey)
        val wasDefault = networkProperties.defaultNodes.remove(publicUri)

        try {
            connectionManager.clear()
            waitUntilTrue(bootstrapPeerTimeoutInSeconds) { disconnectCount(publicKey) > disconnectsBefore }
        } finally {
            if (wasDefault) networkProperties.defaultNodes.add(publicUri)
        }

        if (wasDefault) startDefaultHandshake()
        checkPeer("THIS", shortId)
        checkPeer(shortId, "THIS")
        check(connectionManager.connectionCount == 1) { "Expected exactly one re-established peer" }
    }

    private fun disconnectCount(publicKey: AttoPublicKey): Int = disconnections.count { it.node.publicKey == publicKey }

    private fun findPeer(
        sourceShortId: String,
        peerPublicKey: AttoPublicKey,
    ): AttoPublicKey? {
        if (sourceShortId == "THIS") return peerPublicKey.takeIf { it in connectionManager.connectedPublicKeys }

        val sourceNeighbour = PropertyHolder[Neighbour::class.java, sourceShortId]
        if (sourceNeighbour.released) {
            require(peerPublicKey == PropertyHolder[AttoPublicKey::class.java, "THIS"]) {
                "Released peer observation supports connections to THIS only"
            }
            val sourcePublicKey = PropertyHolder[AttoPublicKey::class.java, sourceShortId]
            if (sourcePublicKey !in connectionManager.connectedPublicKeys) return null

            // Released native images can lack AttoNode reflection metadata for /info.
            // The local manager identifies the authenticated peer; the remote gauge confirms active peer registration.
            return runCatching {
                val metric =
                    webClient
                        .get()
                        .uri("http://localhost:${sourceNeighbour.managementPort}/metrics/network.peers.active")
                        .retrieve()
                        .bodyToMono<Map<String, Any>>()
                        .block(Duration.ofSeconds(1))
                val measurements = metric?.get("measurements") as? List<*>
                peerPublicKey.takeIf {
                    measurements?.any { measurement ->
                        val value = measurement as? Map<*, *>
                        value?.get("statistic") == "VALUE" && (value["value"] as? Number)?.toDouble()?.let { it >= 1.0 } == true
                    } == true
                }
            }.getOrNull()
        }
        return runCatching {
            webClient
                .get()
                .uri("http://localhost:${sourceNeighbour.httpPort}/nodes/peers")
                .retrieve()
                .bodyToMono<List<AttoPublicKey>>()
                .block(Duration.ofSeconds(1))
                ?.firstOrNull { it == peerPublicKey }
        }.getOrNull()
    }
}
