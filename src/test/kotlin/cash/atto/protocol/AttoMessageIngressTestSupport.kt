package cash.atto.protocol

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoSignedVote
import cash.atto.commons.AttoTransaction
import cash.atto.commons.AttoVote
import cash.atto.commons.sign
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toPublicKey
import cash.atto.commons.worker.AttoWorker
import cash.atto.commons.worker.cpu
import cash.atto.node.network.NetworkSerializer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import kotlin.random.Random

internal fun assertAcceptedAtP2PIngress(
    message: AttoMessage,
    network: AttoNetwork,
) {
    val serialized = NetworkSerializer.serialize(message)

    assertEquals(message, NetworkSerializer.deserialize(serialized, network))
}

internal fun assertRejectedAtP2PIngress(
    message: AttoMessage,
    network: AttoNetwork,
) {
    val serialized = NetworkSerializer.serialize(message)

    assertNull(NetworkSerializer.deserialize(serialized, network))
}

internal fun localTransaction(): AttoTransaction {
    val privateKey = AttoPrivateKey.generate()
    val block =
        AttoReceiveBlock(
            network = AttoNetwork.LOCAL,
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = privateKey.toPublicKey(),
            height = 2U.toAttoHeight(),
            balance = AttoAmount.MAX,
            timestamp = AttoInstant.now(),
            previous = AttoHash(Random.nextBytes(ByteArray(32))),
            sendHashAlgorithm = AttoAlgorithm.V1,
            sendHash = AttoHash(ByteArray(32)),
        )
    return AttoTransaction(
        block = block,
        signature = runBlocking { privateKey.sign(block.hash) },
        work = runBlocking { AttoWorker.cpu().work(block) },
    )
}

internal fun signedVote(timestamp: AttoInstant = AttoInstant.now()): AttoSignedVote {
    val privateKey = AttoPrivateKey.generate()
    val vote = vote(privateKey, timestamp)
    return AttoSignedVote(
        vote = vote,
        signature = runBlocking { privateKey.sign(vote.hash) },
    )
}

internal fun forgedVote(): AttoSignedVote {
    val privateKey = AttoPrivateKey.generate()
    return AttoSignedVote(
        vote = vote(privateKey, AttoInstant.now()),
        signature = AttoSignature(ByteArray(AttoSignature.SIZE)),
    )
}

internal fun validHash(): AttoHash = AttoHash(ByteArray(32))

internal fun invalidHash(): AttoHash = AttoHash(ByteArray(1))

private fun vote(
    privateKey: AttoPrivateKey,
    timestamp: AttoInstant,
): AttoVote =
    AttoVote(
        version = 0U.toAttoVersion(),
        algorithm = AttoAlgorithm.V1,
        publicKey = privateKey.toPublicKey(),
        blockAlgorithm = AttoAlgorithm.V1,
        blockHash = AttoHash(Random.nextBytes(ByteArray(32))),
        timestamp = timestamp,
    )
