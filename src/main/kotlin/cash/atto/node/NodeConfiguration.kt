package cash.atto.node

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoSigner
import cash.atto.commons.fromHexToByteArray
import cash.atto.commons.signer.remote
import cash.atto.commons.toHex
import cash.atto.commons.toSigner
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
    fun signer(): AttoSigner {
        if (signerProperties.backend == SignerProperties.Backend.REMOTE) {
            return AttoSigner.remote(signerProperties.remoteUrl!!) {
                mapOf("Authorization" to signerProperties.token!!)
            }
        }

        val privateKey =
            if (!signerProperties.key.isNullOrEmpty()) {
                AttoPrivateKey(signerProperties.key!!.fromHexToByteArray())
            } else {
                val temporaryPrivateKey = AttoPrivateKey.generate()
                logger.info { "No private key configured. Created TEMPORARY private key ${temporaryPrivateKey.value.toHex()}" }
                temporaryPrivateKey
            }

        return privateKey.toSigner()
    }

    @Bean
    fun node(signer: AttoSigner): AttoNode {
        val features = HashSet<NodeFeature>()

        if (!signerProperties.key.isNullOrEmpty() || nodeProperties.forceVoter) {
            features.add(NodeFeature.VOTING)
        }

        if (signerProperties.key.isNullOrEmpty() || nodeProperties.forceHistorical) {
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
    fun webServerFactoryCustomizer(nodeProperties: NodeProperties): WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> =
        WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> { factory ->
            if (!signerProperties.key.isNullOrEmpty()) {
                factory.setPort(-1)
            }
        }
}
