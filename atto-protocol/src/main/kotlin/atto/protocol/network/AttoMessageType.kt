package atto.protocol.network

enum class AttoMessageType(val code: UByte, val public: Boolean) {
    HANDSHAKE_CHALLENGE(0u, true),
    HANDSHAKE_ANSWER(1u, true),
    KEEP_ALIVE(2u, false),
    TRANSACTION_PUSH(3u, false),
    TRANSACTION_REQUEST(4u, false),
    TRANSACTION_RESPONSE(5u, false),
    TRANSACTION_STREAM_REQUEST(6u, false),
    TRANSACTION_STREAM_RESPONSE(7u, false),
    VOTE_PUSH(8u, false),
    VOTE_REQUEST(9u, false),
    VOTE_RESPONSE(10u, false),
    BOOTSTRAP_TRANSACTION_PUSH(11u, false),

    UNKNOWN(UByte.MAX_VALUE, false);

    val private = !public

    companion object {
        private val map = values().associateBy(AttoMessageType::code)
        fun fromCode(code: UByte): AttoMessageType {
            return map.getOrDefault(code, UNKNOWN)
        }
    }
}