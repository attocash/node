package cash.atto.node.signature

import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoSigner
import cash.atto.commons.fromHexToByteArray
import cash.atto.commons.node.remote
import cash.atto.commons.toHex
import cash.atto.commons.toSigner
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SignatureConfiguration {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun signer(signerProperties: SignerProperties): AttoSigner {
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
                logger.info {
                    "No private key configured. This node will be considered historical. Created TEMPORARY private key ${temporaryPrivateKey.value.toHex()} to talk with other nodes. DO NOT USE IT OR SHARE!"
                }
                temporaryPrivateKey
            }

        return privateKey.toSigner()
    }
}
