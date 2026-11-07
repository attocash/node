package cash.atto.node.network

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.node.EventPublisher
import cash.atto.node.network.guardian.PeerBans
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.info.Info
import java.net.InetAddress
import java.net.URI
import java.time.Clock

class NetworkInfoContributorTest {
    @Test
    fun `diagnostics query current connections and authoritative bans`() {
        // Given
        val peers = MutableConnectedPeers()
        val bans = PeerBans(mockk<EventPublisher>(relaxed = true), Clock.systemUTC())
        val contributor = NetworkInfoContributor(peers, bans)
        val old =
            AttoNode(
                network = AttoNetwork.LOCAL,
                protocolVersion = AttoNode.CURRENT_PROTOCOL_VERSION,
                algorithm = AttoAlgorithm.V1,
                publicKey = AttoPublicKey(ByteArray(32) { 1 }),
                publicUri = URI("ws://127.0.0.1:8080"),
                features = setOf(NodeFeature.HISTORICAL),
            )
        peers.connect(old)
        val banned = InetAddress.getByName("8.8.8.8")
        bans.ban(banned)

        // When
        val current = old.copy(publicKey = AttoPublicKey(ByteArray(32) { 2 }))
        peers.connect(current)
        val builder = Info.Builder()
        contributor.contribute(builder)

        // Then
        assertEquals(
            mapOf("paired" to listOf(current), "banned" to listOf(banned.hostAddress)),
            builder.build().details["network"],
        )

        // When
        peers.disconnect(current.publicUri)
        val disconnected = Info.Builder()
        contributor.contribute(disconnected)

        // Then
        assertEquals(
            mapOf("paired" to emptyList<AttoNode>(), "banned" to listOf(banned.hostAddress)),
            disconnected.build().details["network"],
        )

        // When
        bans.clear()
        val cleared = Info.Builder()
        contributor.contribute(cleared)

        // Then
        assertEquals(
            mapOf("paired" to emptyList<AttoNode>(), "banned" to emptyList<String>()),
            cleared.build().details["network"],
        )
    }
}
