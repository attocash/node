package cash.atto.node

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoSigner
import cash.atto.node.network.MAX_PUBLIC_URI_SIZE_BYTES
import cash.atto.node.signature.SignerProperties
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
        require(nodeProperties.publicUri!!.encodeToByteArray().size <= MAX_PUBLIC_URI_SIZE_BYTES) {
            "`atto.node.public-uri` exceeds $MAX_PUBLIC_URI_SIZE_BYTES bytes"
        }
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
            protocolVersion = AttoNode.CURRENT_PROTOCOL_VERSION,
            algorithm = AttoAlgorithm.V1,
            publicKey = signer.publicKey,
            publicUri = URI(nodeProperties.publicUri!!),
            features = features.toSet(),
        ).apply {
            logger.info { this }
        }
    }
}
