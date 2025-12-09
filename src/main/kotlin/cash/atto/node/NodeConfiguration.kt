package cash.atto.node

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoSigner
import cash.atto.node.signature.SignerProperties
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.net.URI

@Configuration
class NodeConfiguration(
    val nodeProperties: NodeProperties,
    val signerProperties: SignerProperties,
) {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun start() {
        require(nodeProperties.publicUri != null) { "`atto.node.public-uri` can't be null" }
        require(URI(nodeProperties.publicUri).path != null) { "`atto.node.public-uri` invalid" }
    }

    @Bean
    fun node(signer: AttoSigner): AttoNode {
        val features = HashSet<NodeFeature>()

        if (signerProperties.backend == SignerProperties.Backend.REMOTE ||
            !signerProperties.key.isNullOrEmpty() ||
            nodeProperties.forceVoter
        ) {
            features.add(NodeFeature.VOTING)
        }

        if (!features.contains(NodeFeature.VOTING) || nodeProperties.forceHistorical) {
            features.add(NodeFeature.HISTORICAL)
        }

        return AttoNode(
            network = nodeProperties.network!!,
            protocolVersion = 0u,
            algorithm = AttoAlgorithm.V1,
            publicKey = signer.publicKey,
            publicUri = URI(nodeProperties.publicUri!!),
            features = features.toSet(),
        ).apply {
            logger.info { this }
        }
    }

    @Bean
    @Profile("!default")
    fun webServerFactoryCustomizer(): WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            if (!signerProperties.key.isNullOrEmpty()) {
                factory.setPort(-1)
            }
        }
}
