package atto.node.node

import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.fromHexToByteArray
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration


@Configuration
@ConfigurationProperties(prefix = "atto.node")
class NodeProperties {
    var voterStrategy: NodeVoterStrategy = NodeVoterStrategy.DEFAULT
    var network: AttoNetwork? = null
    var publicUri: String? = null
    var privateKey: String? = null

    fun getPrivateKey(): AttoPrivateKey? {
        if (privateKey == null) {
            return null
        }
        return AttoPrivateKey(privateKey!!.fromHexToByteArray())
    }
}

enum class NodeVoterStrategy {
    /**
     * Enables voting when the private key is defined
     */
    DEFAULT,

    /**
     * Always enables voting
     */
    FORCE_ENABLED,

    /**
     * Always disable voting
     */
    DISABLED
}