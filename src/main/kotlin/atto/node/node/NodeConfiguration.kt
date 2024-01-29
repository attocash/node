package atto.node.node

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.toHex
import mu.KotlinLogging
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
class NodeConfiguration(val nodeProperties: NodeProperties) {
    private val logger = KotlinLogging.logger {}

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
    fun node(nodeProperties: NodeProperties, privateKey: AttoPrivateKey): atto.protocol.AttoNode {
        val features = HashSet<atto.protocol.NodeFeature>()
        features.add(atto.protocol.NodeFeature.HISTORICAL)

        if (nodeProperties.voterStrategy == NodeVoterStrategy.FORCE_ENABLED ||
            nodeProperties.voterStrategy == NodeVoterStrategy.DEFAULT && nodeProperties.privateKey != null
        ) {
            features.add(atto.protocol.NodeFeature.VOTING)
        }

        return atto.protocol.AttoNode(
            network = nodeProperties.network!!,
            protocolVersion = 0u,
            algorithm = AttoAlgorithm.V1,
            publicKey = privateKey.toPublicKey(),
            socketAddress = nodeProperties.getPublicAddress(),
            features = features
        )
    }

    @Bean
    @Profile("!default")
    fun webServerFactoryCustomizer(nodeProperties: NodeProperties): WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> {
        return WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> { factory ->
            if (nodeProperties.getPrivateKey() != null) {
                factory.setPort(-1)
            }
        }
    }
}