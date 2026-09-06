package cash.atto.protocol

import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoPublicKeyAsByteArraySerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.time.Duration

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("AttoTransactionStreamRequest")
data class AttoTransactionStreamRequest(
    @ProtoNumber(1)
    @Serializable(with = AttoPublicKeyAsByteArraySerializer::class)
    val publicKey: AttoPublicKey,
    @ProtoNumber(2) val startHeight: AttoHeight,
    @ProtoNumber(3) val endHeight: AttoHeight,
) : AttoMessage {
    companion object {
        const val MAX_TRANSACTIONS = 1000UL
        val TIMEOUT: Duration = Duration.ofMinutes(1)
    }

    init {
        require(startHeight >= AttoHeight.MIN) { "Start height must be greater than or equal to ${AttoHeight.MIN}" }
        require(startHeight <= endHeight) { "End height must be greater than or equal to start height" }

        val distance = endHeight.value - startHeight.value
        require(distance < MAX_TRANSACTIONS) {
            "The number of transactions must not exceed the maximum limit of $MAX_TRANSACTIONS. Requested ${distance + 1UL}"
        }
    }

    override suspend fun isValid(network: AttoNetwork): Boolean {
        if (startHeight < AttoHeight.MIN || startHeight > endHeight) {
            return false
        }

        return endHeight.value - startHeight.value < MAX_TRANSACTIONS
    }
}
