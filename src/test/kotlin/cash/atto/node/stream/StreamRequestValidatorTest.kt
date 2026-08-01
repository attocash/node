package cash.atto.node.stream

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.node.AccountHeightSearch
import cash.atto.commons.node.AccountSearch
import cash.atto.commons.node.HeightSearch
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

internal class StreamRequestValidatorTest {
    @Test
    fun `properties expose bounded defaults`() {
        // given
        val properties = StreamProperties()

        // when
        val configuredLimits =
            listOf(
                properties.maxActiveStreams,
                properties.maxRangesPerRequest,
                properties.maxAddressesPerRequest,
                properties.maxRequestedEntries.toInt(),
            )

        // then
        assertEquals(listOf(32, 32, 1_000, 100_000), configuredLimits)
    }

    @Test
    fun `address searches reject empty duplicate and excessive collections`() {
        // given
        val properties = StreamProperties().apply { maxAddressesPerRequest = 2 }
        val validator = StreamRequestValidator(properties)
        val first = address(1)
        val second = address(2)
        val third = address(3)

        // when
        val empty = assertBadRequest { validator.validate(AccountSearch(emptyList())) }
        val duplicate = assertBadRequest { validator.validate(AccountSearch(listOf(first, first))) }
        val excessive = assertBadRequest { validator.validate(AccountSearch(listOf(first, second, third))) }

        // then
        assertEquals(HttpStatus.BAD_REQUEST, empty.statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, duplicate.statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, excessive.statusCode)
    }

    @Test
    fun `height searches reject empty and excessive collections`() {
        // given
        val properties = StreamProperties().apply { maxRangesPerRequest = 2 }
        val validator = StreamRequestValidator(properties)
        val excessive = HeightSearch(listOf(range(1, 1UL, 1UL), range(2, 1UL, 1UL), range(3, 1UL, 1UL)))

        // when
        val emptyFailure = assertBadRequest { validator.validate(HeightSearch(emptyList())) }
        val excessiveFailure = assertBadRequest { validator.validate(excessive) }

        // then
        assertEquals(HttpStatus.BAD_REQUEST, emptyFailure.statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, excessiveFailure.statusCode)
    }

    @Test
    fun `height searches reject zero and reversed ranges`() {
        // given
        val validator = validator()

        // when
        val zeroFailure = assertBadRequest { validator.validate(HeightSearch(listOf(range(1, 0UL, 1UL)))) }
        val reversedFailure = assertBadRequest { validator.validate(HeightSearch(listOf(range(1, 2UL, 1UL)))) }

        // then
        assertEquals(HttpStatus.BAD_REQUEST, zeroFailure.statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, reversedFailure.statusCode)
    }

    @Test
    fun `height searches reject duplicate ranges`() {
        // given
        val validator = validator()
        val duplicate = range(1, 1UL, 5UL)

        // when
        val failure = assertBadRequest { validator.validate(HeightSearch(listOf(duplicate, duplicate))) }

        // then
        assertEquals(HttpStatus.BAD_REQUEST, failure.statusCode)
    }

    @Test
    fun `height searches reject overlapping ranges for the same public key`() {
        // given
        val validator = validator()
        val search = HeightSearch(listOf(range(1, 1UL, 5UL), range(1, 5UL, 8UL)))

        // when
        val failure = assertBadRequest { validator.validate(search) }

        // then
        assertEquals(HttpStatus.BAD_REQUEST, failure.statusCode)
    }

    @Test
    fun `height searches allow equivalent ranges for different public keys`() {
        // given
        val validator = validator()
        val search = HeightSearch(listOf(range(1, 1UL, 5UL), range(2, 1UL, 5UL)))

        // when
        val result = assertDoesNotThrow { validator.validate(search) }

        // then
        assertEquals(Unit, result)
    }

    @Test
    fun `height searches allow the exact aggregate entry limit`() {
        // given
        val validator = validator(maxRequestedEntries = 10)
        val search = HeightSearch(listOf(range(1, 1UL, 5UL), range(1, 6UL, 10UL)))

        // when
        val result = assertDoesNotThrow { validator.validate(search) }

        // then
        assertEquals(Unit, result)
    }

    @Test
    fun `height searches reject one entry over the aggregate limit`() {
        // given
        val validator = validator(maxRequestedEntries = 10)
        val search = HeightSearch(listOf(range(1, 1UL, 5UL), range(1, 6UL, 11UL)))

        // when
        val failure = assertBadRequest { validator.validate(search) }

        // then
        assertEquals(HttpStatus.BAD_REQUEST, failure.statusCode)
    }

    @Test
    fun `height span validation cannot overflow unsigned arithmetic`() {
        // given
        val validator = validator(maxRequestedEntries = 100_000)
        val search = HeightSearch(listOf(range(1, 1UL, ULong.MAX_VALUE)))

        // when
        val failure = assertBadRequest { validator.validate(search) }

        // then
        assertEquals(HttpStatus.BAD_REQUEST, failure.statusCode)
    }

    private fun validator(maxRequestedEntries: Long = 100_000): StreamRequestValidator =
        StreamRequestValidator(
            StreamProperties().apply {
                this.maxRequestedEntries = maxRequestedEntries
            },
        )

    private fun range(
        publicKeySeed: Int,
        from: ULong,
        to: ULong,
    ): AccountHeightSearch = AccountHeightSearch(address(publicKeySeed), AttoHeight(from), AttoHeight(to))

    private fun address(seed: Int): AttoAddress =
        AttoAddress(
            AttoAlgorithm.V1,
            AttoPublicKey(ByteArray(32) { seed.toByte() }),
        )

    private fun assertBadRequest(block: () -> Unit): ResponseStatusException = assertThrows(ResponseStatusException::class.java, block)
}
