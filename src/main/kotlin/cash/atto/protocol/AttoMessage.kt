package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import kotlinx.serialization.Serializable

@Serializable
sealed interface AttoMessage {
    fun messageType(): AttoMessageType

    fun isValid(network: AttoNetwork): Boolean
}

@Serializable
class AttoUnknown : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.UNKNOWN

    override fun isValid(network: AttoNetwork): Boolean = false
}

enum class AttoMessageType(
    val code: UByte,
    val public: Boolean,
) {
    READY(0u, true),
    HANDSHAKE_CHALLENGE(1u, true),
    HANDSHAKE_ANSWER(2u, true),
    HANDSHAKE_ACCEPTANCE(3u, true),
    KEEP_ALIVE(4u, false),
    TRANSACTION_PUSH(5u, false),
    TRANSACTION_REQUEST(6u, false),
    TRANSACTION_RESPONSE(7u, false),
    TRANSACTION_STREAM_REQUEST(8u, false),
    TRANSACTION_STREAM_RESPONSE(9u, false),
    VOTE_PUSH(10u, false),
    VOTE_STREAM_REQUEST(11u, false),
    VOTE_STREAM_RESPONSE(12u, false),
    VOTE_STREAM_CANCEL(13u, false),
    BOOTSTRAP_TRANSACTION_PUSH(14u, false),

    UNKNOWN(UByte.MAX_VALUE, false),
    ;

    val private = !public

    companion object {
        private val codeMap = entries.associateBy(AttoMessageType::code)

        fun fromCode(code: UByte): AttoMessageType = codeMap.getOrDefault(code, UNKNOWN)
    }
}
