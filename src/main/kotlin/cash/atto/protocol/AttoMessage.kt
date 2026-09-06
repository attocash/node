package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import kotlinx.serialization.Serializable

@Serializable
sealed interface AttoMessage {
    suspend fun isValid(network: AttoNetwork): Boolean
}
