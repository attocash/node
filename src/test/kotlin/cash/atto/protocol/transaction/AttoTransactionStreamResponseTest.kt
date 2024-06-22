package cash.atto.protocol.transaction

import cash.atto.commons.*
import cash.atto.commons.serialiazers.protobuf.AttoProtobuf
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.random.Random

@OptIn(ExperimentalSerializationApi::class)
class AttoTransactionStreamResponseTest {
    @Test
    fun `should serialize and deserialize`() {
        // given
        val block =
            AttoReceiveBlock(
                version = 0u,
                algorithm = AttoAlgorithm.V1,
                publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
                height = 2u,
                balance = AttoAmount.MAX,
                timestamp = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds()),
                previous = AttoHash(Random.nextBytes(ByteArray(32))),
                sendHashAlgorithm = AttoAlgorithm.V1,
                sendHash = AttoHash(ByteArray(32)),
            )
        val transaction =
            AttoTransaction(
                block = block,
                signature = AttoSignature(Random.nextBytes(ByteArray(64))),
                work = AttoWork(Random.nextBytes(ByteArray(8))),
            )
        val expectedResponse = AttoTransactionStreamResponse(transaction)

        // when
        val byteArray = AttoProtobuf.encodeToByteArray(AttoTransactionStreamResponse.serializer(), expectedResponse)
        val response = AttoProtobuf.decodeFromByteArray(AttoTransactionStreamResponse.serializer(), byteArray)

        // then
        assertEquals(expectedResponse, response)
    }
}
