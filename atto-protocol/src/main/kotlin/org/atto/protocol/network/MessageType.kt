package org.atto.protocol.network

enum class MessageType(val code: UByte, val public: Boolean) {
    HANDSHAKE_CHALLENGE(0u, true),
    HANDSHAKE_ANSWER(1u, true),
    KEEP_ALIVE(2u, false),
    TRANSACTION_PUSH(3u, false),
    TRANSACTION_REQUEST(4u, false),
    TRANSACTION_RESPONSE(5u, false),
    VOTE_REQUEST(7u, false),
    VOTE_PUSH(8u, false),

    UNKNOWN(UByte.MAX_VALUE, false);

    companion object {
        private val map = values().associateBy(MessageType::code)
        fun fromCode(code: UByte): MessageType {
            return map.getOrDefault(code, UNKNOWN)
        }
    }
}