package org.atto.protocol.transaction

import org.atto.commons.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.util.stream.Stream
import kotlin.random.Random

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
        val seed = AttoSeed("1234567890123456789012345678901234567890123456789012345678901234".fromHexToByteArray())
        val privateKey = seed.toPrivateKey(0u)

        @JvmStatic
        fun validBlockProvider(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    AttoBlock(
                        type = AttoBlockType.OPEN,
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 0u,
                        previous = AttoHash(ByteArray(32)),
                        representative = privateKey.toPublicKey(),
                        link = AttoLink.from(AttoHash(Random.nextBytes(ByteArray(32)))),
                        balance = AttoAmount(100u),
                        amount = AttoAmount(100u),
                        timestamp = Instant.now()
                    ),
                ),
                Arguments.of(
                    AttoBlock(
                        type = AttoBlockType.RECEIVE,
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 1u,
                        previous = AttoHash(ByteArray(32)),
                        representative = privateKey.toPublicKey(),
                        link = AttoLink.from(AttoHash(Random.nextBytes(ByteArray(32)))),
                        balance = AttoAmount(100u),
                        amount = AttoAmount(50u),
                        timestamp = Instant.now()
                    ),
                ),
                Arguments.of(
                    AttoBlock(
                        type = AttoBlockType.SEND,
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 1u,
                        previous = AttoHash(ByteArray(32)),
                        representative = privateKey.toPublicKey(),
                        link = AttoLink.from(AttoPublicKey(Random.nextBytes(ByteArray(32)))),
                        balance = AttoAmount(100u),
                        amount = AttoAmount(50u),
                        timestamp = Instant.now()
                    ),
                ),
                Arguments.of(
                    AttoBlock(
                        type = AttoBlockType.CHANGE,
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 1u,
                        previous = AttoHash(ByteArray(32)),
                        representative = privateKey.toPublicKey(),
                        link = AttoLink.from(AttoPublicKey(ByteArray(32))),
                        balance = AttoAmount(100u),
                        amount = AttoAmount(0u),
                        timestamp = Instant.now()
                    ),
                ),
            )
        }

        @JvmStatic
        fun invalidBlockProvider(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    AttoBlock( // UNKNOWN
                        type = AttoBlockType.UNKNOWN,
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 0u,
                        previous = AttoHash(ByteArray(32)),
                        representative = privateKey.toPublicKey(),
                        link = AttoLink.from(AttoHash(Random.nextBytes(ByteArray(32)))),
                        balance = AttoAmount(100u),
                        amount = AttoAmount(100u),
                        timestamp = Instant.now()
                    ),
                ),
                Arguments.of(
                    AttoBlock( // wrong version
                        type = AttoBlockType.OPEN,
                        version = 1u,
                        publicKey = privateKey.toPublicKey(),
                        height = 0u,
                        previous = AttoHash(ByteArray(32)),
                        representative = privateKey.toPublicKey(),
                        link = AttoLink.from(AttoHash(Random.nextBytes(ByteArray(32)))),
                        balance = AttoAmount(100u),
                        amount = AttoAmount(100u),
                        timestamp = Instant.now()
                    ),
                ),
                Arguments.of(
                    AttoBlock( // OPEN AttoBlock amount and balance not equals
                        type = AttoBlockType.OPEN,
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 0u,
                        previous = AttoHash(ByteArray(32)),
                        representative = privateKey.toPublicKey(),
                        link = AttoLink.from(AttoHash(Random.nextBytes(ByteArray(32)))),
                        balance = AttoAmount(100u),
                        amount = AttoAmount(90u),
                        timestamp = Instant.now()
                    )
                ),
                Arguments.of(
                    AttoBlock( // OPEN AttoBlock invalid height
                        type = AttoBlockType.OPEN,
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 1u,
                        previous = AttoHash(ByteArray(32)),
                        representative = privateKey.toPublicKey(),
                        link = AttoLink.from(AttoHash(Random.nextBytes(ByteArray(32)))),
                        balance = AttoAmount(100u),
                        amount = AttoAmount(100u),
                        timestamp = Instant.now()
                    )
                ),
                Arguments.of(
                    AttoBlock( // CHANGE AttoBlock invalid height
                        type = AttoBlockType.CHANGE,
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 0u,
                        previous = AttoHash(ByteArray(32)),
                        representative = privateKey.toPublicKey(),
                        link = AttoLink.from(AttoHash(ByteArray(32))),
                        balance = AttoAmount(100u),
                        amount = AttoAmount(0u),
                        timestamp = Instant.now()
                    )
                ),
                Arguments.of(
                    AttoBlock( // CHANGE AttoBlock nonnull amount
                        type = AttoBlockType.CHANGE,
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 1u,
                        previous = AttoHash(ByteArray(32)),
                        representative = privateKey.toPublicKey(),
                        link = AttoLink.from(AttoHash(ByteArray(32))),
                        balance = AttoAmount(100u),
                        amount = AttoAmount(5u),
                        timestamp = Instant.now()
                    )
                ),
                Arguments.of(
                    AttoBlock( // CHANGE AttoBlock nonzero link
                        type = AttoBlockType.CHANGE,
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 1u,
                        previous = AttoHash(ByteArray(32)),
                        representative = privateKey.toPublicKey(),
                        link = AttoLink.from(AttoHash(Random.nextBytes(ByteArray(32)))),
                        balance = AttoAmount(100u),
                        amount = AttoAmount(0u),
                        timestamp = Instant.now()
                    )
                ),
                Arguments.of(
                    AttoBlock( // CHANGE AttoBlock nonzero link
                        type = AttoBlockType.SEND,
                        version = 0u,
                        publicKey = privateKey.toPublicKey(),
                        height = 1u,
                        previous = AttoHash(ByteArray(32)),
                        representative = privateKey.toPublicKey(),
                        link = AttoLink.from(privateKey.toPublicKey()),
                        balance = AttoAmount(100u),
                        amount = AttoAmount(5u),
                        timestamp = Instant.now()
                    )
                )
            )
        }
    }
}