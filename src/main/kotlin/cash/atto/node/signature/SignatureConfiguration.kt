package cash.atto.node.signature

import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoSigner
import cash.atto.commons.fromHexToByteArray
import cash.atto.commons.node.remote
import cash.atto.commons.toHex
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SignatureConfiguration {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun signer(
        signerProperties: SignerProperties,
        meterRegistry: MeterRegistry,
    ): AttoSigner {
        val signer =
            if (signerProperties.backend == SignerProperties.Backend.REMOTE) {
                AttoSigner.remote(signerProperties.remoteUrl!!) {
                    mapOf("Authorization" to signerProperties.token!!)
                }
            } else {
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

                runBlocking { privateKey.toSigner() }
            }

        return MeteredAttoSigner(signer, meterRegistry)
    }
}
