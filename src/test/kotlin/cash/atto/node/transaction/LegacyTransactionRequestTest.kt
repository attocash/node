package cash.atto.node.transaction

import cash.atto.node.account.LegacyHttpFixtures
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LegacyTransactionRequestTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val blockNames = listOf("open", "receive", "send", "change")

    @Test
    fun `legacy key-only blocks decode through the local request boundary with implicit open height`() {
        // Given
        for (name in blockNames) {
            val expected = LegacyHttpFixtures.transaction(name)
            val payload =
                fixture(name).withBlock { block ->
                    JsonObject(
                        block.filterKeys {
                            it !in setOf("address", "receiverAddress", "representativeAddress") &&
                                (name != "open" || it != "height")
                        },
                    )
                }

            // When
            val request = json.decodeFromString<LegacyTransactionRequest>(payload.toString())
            val encoded = json.encodeToJsonElement(request)

            // Then
            assertEquals(expected, request.transaction, name)
            assertEquals(json.encodeToJsonElement(expected), encoded, name)
            assertEquals(setOf("block", "signature", "work"), encoded.jsonObject.keys)
        }
    }

    @Test
    fun `canonical addresses take priority without parsing malformed duplicate legacy identities`() {
        // Given
        for (name in blockNames) {
            val payload =
                fixture(name).withBlock { block ->
                    JsonObject(
                        block.mapValues { (key, value) ->
                            when {
                                key == "algorithm" || key.endsWith("Algorithm") && key != "sendHashAlgorithm" -> JsonObject(emptyMap())
                                key.endsWith("PublicKey") || key == "publicKey" -> JsonArray(listOf(JsonNull))
                                else -> value
                            }
                        },
                    )
                }

            // When
            val request = json.decodeFromString<LegacyTransactionRequest>(payload.toString())

            // Then
            assertEquals(LegacyHttpFixtures.transaction(name), request.transaction, name)
        }
    }

    @Test
    fun `present null canonical address is rejected instead of falling back to a legacy key`() {
        // Given
        val payload = fixture("send").withBlock { block -> JsonObject(block + ("address" to JsonNull)) }

        // When / Then
        assertThrows(SerializationException::class.java) { json.decodeFromString<LegacyTransactionRequest>(payload.toString()) }
    }

    @Test
    fun `missing legacy identity pairs are rejected at the request boundary`() {
        // Given
        val payload = fixture("send").withBlock { block -> JsonObject(block - setOf("address", "publicKey")) }

        // When / Then
        assertThrows(SerializationException::class.java) { json.decodeFromString<LegacyTransactionRequest>(payload.toString()) }
    }

    @Test
    fun `request normalization retains the caller unknown field policy`() {
        // Given
        val strictJson = Json { ignoreUnknownKeys = false }
        val payload = fixture("send").withBlock { block -> JsonObject(block + ("future" to JsonPrimitive(true))) }

        // When / Then
        assertThrows(SerializationException::class.java) { strictJson.decodeFromString<LegacyTransactionRequest>(payload.toString()) }
    }

    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(checkNotNull(javaClass.getResource("/legacy-http/$name.json")).readText()).jsonObject

    private fun JsonObject.withBlock(transform: (JsonObject) -> JsonObject): JsonObject =
        JsonObject(this + ("block" to transform(getValue("block").jsonObject)))
}
