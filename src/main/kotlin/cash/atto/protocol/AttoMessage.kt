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
    HANDSHAKE_CHALLENGE(0u, true),
    HANDSHAKE_ANSWER(1u, true),
    HANDSHAKE_ACCEPTANCE(2u, true),
    KEEP_ALIVE(3u, false),
    TRANSACTION_PUSH(4u, false),
    TRANSACTION_REQUEST(5u, false),
    TRANSACTION_RESPONSE(6u, false),
    TRANSACTION_STREAM_REQUEST(7u, false),
    TRANSACTION_STREAM_RESPONSE(8u, false),
    VOTE_PUSH(9u, false),
    VOTE_STREAM_REQUEST(10u, false),
    VOTE_STREAM_RESPONSE(11u, false),
    VOTE_STREAM_CANCEL(12u, false),
    BOOTSTRAP_TRANSACTION_PUSH(13u, false),

    UNKNOWN(UByte.MAX_VALUE, false),
    ;

    val private = !public

    companion object {
        private val codeMap = entries.associateBy(AttoMessageType::code)

        fun fromCode(code: UByte): AttoMessageType = codeMap.getOrDefault(code, UNKNOWN)
    }
}
