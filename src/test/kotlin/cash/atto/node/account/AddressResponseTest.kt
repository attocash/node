package cash.atto.node.account

import cash.atto.commons.toHex
import cash.atto.node.account.entry.toLegacyResponse
import cash.atto.node.receivable.toLegacyResponse
import cash.atto.node.transaction.toLegacyResponse
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AddressResponseTest {
    // Fixtures were emitted by Commons 8 using the Node HTTP codec settings.
    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }

    @Test
    fun `legacy responses match published Commons 8 fields and numbers while canonical models serialize addresses`() {
        // Given
        val account = LegacyHttpFixtures.account
        val entry = LegacyHttpFixtures.entry
        val receivable = LegacyHttpFixtures.receivable

        // When
        val legacy =
            listOf(
                "account" to json.encodeToJsonElement(account.toLegacyResponse()),
                "entry" to json.encodeToJsonElement(entry.toLegacyResponse()),
                "receivable" to json.encodeToJsonElement(receivable.toLegacyResponse()),
            )
        val canonical =
            listOf(
                json.encodeToJsonElement(account),
                json.encodeToJsonElement(entry),
                json.encodeToJsonElement(receivable),
            )

        // Then
        legacy.forEach { (name, actual) -> assertEquals(fixture(name), actual.toString(), name) }
        canonical.forEach { assertCanonicalIdentity(it) }
    }

    @Test
    fun `all block variants preserve legacy responses and original binary transaction bytes`() {
        // Given
        for (name in listOf("open", "receive", "send", "change")) {
            val transaction = LegacyHttpFixtures.transaction(name)

            // When
            val legacy = json.encodeToJsonElement(transaction.toLegacyResponse())
            val canonical = json.encodeToJsonElement(transaction)
            val binary = transaction.toBuffer().readByteArray().toHex()

            // Then
            assertEquals(fixture(name), legacy.toString(), name)
            assertCanonicalIdentity(canonical)
            assertEquals(resource("$name.hex").trim(), binary, name)
        }
    }

    private fun assertCanonicalIdentity(element: JsonElement) {
        if (element is JsonObject) {
            assertFalse(element.keys.any { it.endsWith("publicKey", ignoreCase = true) })
            assertFalse(
                element.keys.any {
                    it == "algorithm" ||
                        it in setOf("representativeAlgorithm", "receiverAlgorithm", "subjectAlgorithm")
                },
            )
            element.values.forEach { assertCanonicalIdentity(it) }
        }
    }

    private fun fixture(name: String): String = resource("$name.json")

    private fun resource(name: String): String = checkNotNull(javaClass.getResource("/legacy-http/$name")).readText()
}
