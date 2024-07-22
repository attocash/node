package cash.atto.protocol

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoTransaction
import cash.atto.commons.AttoWorker
import cash.atto.commons.cpu
import cash.atto.commons.sign
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

@OptIn(ExperimentalSerializationApi::class)
class AttoTransactionStreamResponseTest {
    private val privateKey = AttoPrivateKey.generate()

    @Test
    fun `should serialize and deserialize`() {
        // given
        val block =
            AttoReceiveBlock(
                network = AttoNetwork.LOCAL,
                version = 0U.toAttoVersion(),
                algorithm = AttoAlgorithm.V1,
                publicKey = privateKey.toPublicKey(),
                height = 2U.toAttoHeight(),
                balance = AttoAmount.MAX,
                timestamp = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()),
                previous = AttoHash(Random.nextBytes(ByteArray(32))),
                sendHashAlgorithm = AttoAlgorithm.V1,
                sendHash = AttoHash(ByteArray(32)),
            )
        val transaction =
            AttoTransaction(
                block = block,
                signature = privateKey.sign(block.hash),
                AttoWorker.cpu().work(block),
            )
        val expectedResponse = AttoTransactionStreamResponse(transaction)

        // when
        val byteArray = ProtoBuf.encodeToByteArray(AttoTransactionStreamResponse.serializer(), expectedResponse)
        val response = ProtoBuf.decodeFromByteArray(AttoTransactionStreamResponse.serializer(), byteArray)

        // then
        assertEquals(expectedResponse, response)
    }
}
