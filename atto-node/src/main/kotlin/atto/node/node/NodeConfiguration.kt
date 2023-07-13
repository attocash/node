package atto.node.node

import mu.KotlinLogging
import atto.commons.AttoPrivateKey
import atto.commons.toHex
import atto.protocol.AttoNode
import atto.protocol.NodeFeature
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

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
            publicKey = privateKey.toPublicKey(),
            socketAddress = nodeProperties.getPublicAddress(),
            features = features
        )
    }
}