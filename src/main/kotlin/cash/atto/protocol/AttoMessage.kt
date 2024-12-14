package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import kotlinx.serialization.Serializable

@Serializable
sealed interface AttoMessage {
    fun messageType(): AttoMessageType

    fun isValid(network: AttoNetwork): Boolean
}

enum class AttoMessageType {
    KEEP_ALIVE,
    TRANSACTION_PUSH,
    TRANSACTION_REQUEST,
    TRANSACTION_RESPONSE,
    TRANSACTION_STREAM_REQUEST,
    TRANSACTION_STREAM_RESPONSE,
    VOTE_PUSH,
    VOTE_STREAM_REQUEST,
    VOTE_STREAM_RESPONSE,
    VOTE_STREAM_CANCEL,
    BOOTSTRAP_TRANSACTION_PUSH,
    VOTE_REQUEST,
    VOTE_RESPONSE,
}
