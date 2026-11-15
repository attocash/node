package cash.atto.node.transaction

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoTransaction
import io.swagger.v3.oas.annotations.media.Schema
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

@Serializable(with = LegacyTransactionRequestSerializer::class)
@Schema(implementation = AttoTransaction::class)
data class LegacyTransactionRequest(
    val transaction: AttoTransaction,
)

internal object LegacyTransactionRequestSerializer : KSerializer<LegacyTransactionRequest> {
    override val descriptor = AttoTransaction.serializer().descriptor

    override fun deserialize(decoder: Decoder): LegacyTransactionRequest {
        val jsonDecoder = decoder as? JsonDecoder ?: throw SerializationException("Legacy transaction requests require JSON")
        val transaction = jsonDecoder.decodeJsonElement() as? JsonObject ?: throw SerializationException("Expected a transaction object")
        val block = transaction["block"] as? JsonObject ?: throw SerializationException("Expected a transaction block")
        val fields = block.toMutableMap()
        fields.restoreAddress(jsonDecoder.json, "address", "algorithm", "publicKey")
        when ((block["type"] as? JsonPrimitive)?.content) {
            "OPEN", "CHANGE" -> {
                fields.restoreAddress(
                    jsonDecoder.json,
                    "representativeAddress",
                    "representativeAlgorithm",
                    "representativePublicKey",
                )
            }

            "SEND" -> {
                fields.restoreAddress(jsonDecoder.json, "receiverAddress", "receiverAlgorithm", "receiverPublicKey")
            }
        }
        val canonical = JsonObject(transaction + ("block" to JsonObject(fields)))
        return LegacyTransactionRequest(jsonDecoder.json.decodeFromJsonElement<AttoTransaction>(canonical))
    }

    override fun serialize(
        encoder: Encoder,
        value: LegacyTransactionRequest,
    ) = encoder.encodeSerializableValue(AttoTransaction.serializer(), value.transaction)

    private fun MutableMap<String, JsonElement>.restoreAddress(
        json: Json,
        addressField: String,
        algorithmField: String,
        publicKeyField: String,
    ) {
        if (!containsKey(addressField)) {
            val algorithm = get(algorithmField) ?: throw SerializationException("Missing $addressField or $algorithmField")
            val publicKey = get(publicKeyField) ?: throw SerializationException("Missing $addressField or $publicKeyField")
            val address =
                try {
                    AttoAddress(json.decodeFromJsonElement<AttoAlgorithm>(algorithm), json.decodeFromJsonElement<AttoPublicKey>(publicKey))
                } catch (exception: IllegalArgumentException) {
                    throw SerializationException("Invalid legacy $addressField", exception)
                }
            put(addressField, json.encodeToJsonElement(address))
        }
        remove(algorithmField)
        remove(publicKeyField)
    }
}
