package atto.protocol.transaction

import cash.atto.commons.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.util.stream.Stream

internal class AttoBlockTest {

    @ParameterizedTest
    @MethodSource("validBlockProvider")
    fun `should not fail validation when AttoBlock is valid`(block: AttoBlock) {
        assertTrue(block.isValid())
    }

    @ParameterizedTest
    @MethodSource("invalidBlockProvider")
    fun `should fail validation when AttoBlock is invalid`(block: AttoBlock) {
        assertFalse(block.isValid())
    }

    companion object {
        val privateKey = AttoPrivateKey("00".repeat(32).fromHexToByteArray())

        @JvmStatic
        fun validBlockProvider(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    AttoSendBlock(
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 2u,
                        balance = AttoAmount(100u),
                        timestamp = Instant.now(),
                        previous = AttoHash(ByteArray(32)),
                        receiverPublicKey = AttoPublicKey(ByteArray(32)),
                        amount = AttoAmount(100u),
                    ),
                ),
                Arguments.of(
                    AttoReceiveBlock(
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 2u,
                        balance = AttoAmount(100u),
                        timestamp = Instant.now(),
                        previous = AttoHash(ByteArray(32)),
                        sendHash = AttoHash(ByteArray(32)),
                    ),
                ),
                Arguments.of(
                    AttoOpenBlock(
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        balance = AttoAmount(100u),
                        timestamp = Instant.now(),
                        sendHash = AttoHash(ByteArray(32)),
                        representative = privateKey.toPublicKey(),
                    ),
                ),
                Arguments.of(
                    AttoChangeBlock(
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 2u,
                        balance = AttoAmount(100u),
                        timestamp = Instant.now(),
                        previous = AttoHash(ByteArray(32)),
                        representative = privateKey.toPublicKey(),
                    ),
                ),
            )
        }

        @JvmStatic
        fun invalidBlockProvider(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    AttoSendBlock(
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 1u, // invalid
                        balance = AttoAmount(100u),
                        timestamp = Instant.now(),
                        previous = AttoHash(ByteArray(32)),
                        receiverPublicKey = privateKey.toPublicKey(),
                        amount = AttoAmount(100u),
                    ),
                ),
                Arguments.of(
                    AttoReceiveBlock(
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 1u, // invalid
                        balance = AttoAmount(100u),
                        timestamp = Instant.now(),
                        previous = AttoHash(ByteArray(32)),
                        sendHash = AttoHash(ByteArray(32))
                    ),
                ),
                Arguments.of(
                    AttoChangeBlock(
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 1u, // invalid
                        balance = AttoAmount(100u),
                        timestamp = Instant.now(),
                        previous = AttoHash(ByteArray(32)),
                        representative = privateKey.toPublicKey(),
                    ),
                ),
                Arguments.of(
                    AttoSendBlock(
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 2u,
                        balance = AttoAmount(100u),
                        timestamp = Instant.now(),
                        previous = AttoHash(ByteArray(32)),
                        receiverPublicKey = AttoPublicKey(ByteArray(32)),
                        amount = AttoAmount(0u),  // invalid
                    ),
                ),
                Arguments.of(
                    AttoSendBlock(
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 2u,
                        balance = AttoAmount(100u),
                        timestamp = Instant.now(),
                        previous = AttoHash(ByteArray(32)),
                        receiverPublicKey = privateKey.toPublicKey(), // invalid
                        amount = AttoAmount(100u),
                    ),
                ),
                Arguments.of(
                    AttoReceiveBlock(
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 2u,
                        balance = AttoAmount.MIN,  // invalid
                        timestamp = Instant.now(),
                        previous = AttoHash(ByteArray(32)),
                        sendHash = AttoHash(ByteArray(32))
                    ),
                ),
            )
        }
    }
}