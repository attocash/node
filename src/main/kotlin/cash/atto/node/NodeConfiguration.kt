package cash.atto.node

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.toHex
import cash.atto.commons.toPublicKey
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
) {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun start() {
        require(nodeProperties.publicUri != null) { "`atto.node.public-uri` can't be null" }
        require(URI(nodeProperties.publicUri).path != null) { "`atto.node.public-uri` invalid" }
    }

    @Bean
    fun privateKey(): AttoPrivateKey {
        val privateKey = nodeProperties.getPrivateKey()
        if (privateKey != null) {
            return privateKey
        }
        val temporaryPrivateKey = AttoPrivateKey.generate()
        logger.info { "No private key configured. Created TEMPORARY private key ${temporaryPrivateKey.value.toHex()}" }
        return temporaryPrivateKey
    }

    @Bean
    fun node(
        nodeProperties: NodeProperties,
        privateKey: AttoPrivateKey,
    ): AttoNode {
        val features = HashSet<NodeFeature>()

        if (nodeProperties.privateKey != null || nodeProperties.forceVoter) {
            features.add(NodeFeature.VOTING)
        }

        if (nodeProperties.privateKey == null || nodeProperties.forceHistorical) {
            features.add(NodeFeature.HISTORICAL)
        }

        return AttoNode(
            network = nodeProperties.network!!,
            protocolVersion = 0u,
            algorithm = AttoAlgorithm.V1,
            publicKey = privateKey.toPublicKey(),
            publicUri = URI(nodeProperties.publicUri!!),
            features = features.toSet(),
        ).apply {
            logger.info { this }
        }
    }

    @Bean
    @Profile("!default")
    fun webServerFactoryCustomizer(nodeProperties: NodeProperties): WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> =
        WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> { factory ->
            if (nodeProperties.getPrivateKey() != null) {
                factory.setPort(-1)
            }
        }
}
