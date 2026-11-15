package cash.atto.node.network

import cash.atto.commons.AttoChallenge
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoSigner
import cash.atto.commons.fromHexToByteArray
import cash.atto.node.transaction.Transaction
import cash.atto.protocol.AttoNode
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI

/** Owns the peer challenge exchange; transport deadlines and connection attempts stay with the processor. */
internal class HandshakeProtocol(
    private val genesisTransaction: Transaction,
    private val thisNode: AttoNode,
    private val signer: AttoSigner,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun prepareResponse(
        remoteHost: String,
        counterResponse: CounterChallengeResponse,
    ): PreparedHandshake? {
        val challenge = counterResponse.challenge
        val publicUri = ChallengeStore.remove(challenge)
        if (publicUri == null) {
            logger.trace { "Received invalid challenge request from $remoteHost $counterResponse" }
            return null
        }

        val node = counterResponse.node
        if (node.publicUri != publicUri) {
            logger.trace { "Node publicUri ${node.publicUri} doesn't match expected $publicUri from $remoteHost" }
            return null
        }

        if (counterResponse.genesis != genesisTransaction.hash) {
            logger.trace { "Received mismatched genesis hash from $publicUri $remoteHost $counterResponse" }
            return null
        }

        if (!counterResponse.signature.isValid(node.publicKey, challengeHash(node, challenge, counterResponse.timestamp))) {
            logger.trace { "Received invalid signature from server $remoteHost $counterResponse" }
            return null
        }

        val counterChallenge = counterResponse.counterChallenge
        if (!isChallengePrefixValid(counterChallenge)) {
            logger.trace {
                "Received invalid challenge prefix request from $publicUri $remoteHost " +
                    "$counterResponse"
            }
            return null
        }

        logger.trace { "Challenge $challenge validated successfully" }

        val timestamp = AttoInstant.now()
        val response =
            ChallengeResponse(
                thisNode,
                timestamp,
                signer.sign(AttoChallenge(counterChallenge.fromHexToByteArray()), timestamp),
            )
        return PreparedHandshake(publicUri, node, response)
    }

    suspend fun createCounterResponse(
        challenge: String,
        counterChallenge: String,
        timestamp: AttoInstant,
    ): CounterChallengeResponse =
        CounterChallengeResponse(
            challenge,
            genesisTransaction.hash,
            thisNode,
            timestamp,
            signer.sign(AttoChallenge(challenge.fromHexToByteArray()), timestamp),
            counterChallenge,
        )

    suspend fun authenticateResponse(
        remoteHost: String,
        publicUri: URI,
        counterChallenge: String?,
        response: ChallengeResponse,
    ): AttoNode? {
        if (counterChallenge == null || ChallengeStore.remove(counterChallenge) == null) {
            logger.trace { "Received invalid challenge response from $publicUri $remoteHost $response" }
            return null
        }

        val node = response.node
        if (node.publicUri != publicUri) {
            logger.trace { "Node publicUri ${node.publicUri} doesn't match header $publicUri from $remoteHost" }
            return null
        }
        if (!response.signature.isValid(node.publicKey, challengeHash(node, counterChallenge, response.timestamp))) {
            logger.trace { "Received invalid signature from client $remoteHost $response" }
            return null
        }
        return node
    }

    fun isChallengePrefixValid(value: String): Boolean {
        val challenge = value.fromHexToByteArray()
        if (challenge.size <= ChallengeStore.CHALLENGE_SIZE) {
            return false
        }

        val prefixEnd = challenge.size - ChallengeStore.CHALLENGE_SIZE
        val url = challenge.sliceArray(0 until prefixEnd).toString(Charsets.UTF_8)
        return url == thisNode.publicUri.toString()
    }

    private fun challengeHash(
        node: AttoNode,
        challenge: String,
        timestamp: AttoInstant,
    ): AttoHash =
        AttoHash.hash(
            64,
            node.publicKey.value,
            challenge.fromHexToByteArray(),
            timestamp.toByteArray(),
        )
}

internal data class PreparedHandshake(
    val publicUri: URI,
    val node: AttoNode,
    val response: ChallengeResponse,
)
