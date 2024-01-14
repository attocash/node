package atto.protocol

import atto.protocol.bootstrap.AttoBootstrapTransactionPush
import atto.protocol.network.handshake.AttoHandshakeAnswer
import atto.protocol.network.handshake.AttoHandshakeChallenge
import atto.protocol.network.peer.AttoKeepAlive
import atto.protocol.transaction.*
import atto.protocol.vote.AttoVotePush
import atto.protocol.vote.AttoVoteRequest
import atto.protocol.vote.AttoVoteResponse
import cash.atto.commons.AttoNetwork
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

interface AttoMessage {
    fun messageType(): AttoMessageType

    fun isValid(network: AttoNetwork): Boolean
}

@Serializable
class AttoUnknown : AttoMessage {
    override fun messageType(): AttoMessageType = AttoMessageType.UNKNOWN
    override fun isValid(network: AttoNetwork): Boolean = false
}


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
        private val codeMap = entries.associateBy(AttoMessageType::code)
        fun fromCode(code: UByte): AttoMessageType {
            return codeMap.getOrDefault(code, UNKNOWN)
        }
    }
}


val messageSerializerMap = mapOf(
    AttoMessageType.HANDSHAKE_CHALLENGE to AttoHandshakeChallenge.serializer(),
    AttoMessageType.HANDSHAKE_ANSWER to AttoHandshakeAnswer.serializer(),
    AttoMessageType.KEEP_ALIVE to AttoKeepAlive.serializer(),
    AttoMessageType.TRANSACTION_PUSH to AttoTransactionPush.serializer(),
    AttoMessageType.TRANSACTION_REQUEST to AttoTransactionRequest.serializer(),
    AttoMessageType.TRANSACTION_RESPONSE to AttoTransactionResponse.serializer(),
    AttoMessageType.TRANSACTION_STREAM_REQUEST to AttoTransactionStreamRequest.serializer(),
    AttoMessageType.TRANSACTION_STREAM_RESPONSE to AttoTransactionStreamResponse.serializer(),
    AttoMessageType.VOTE_PUSH to AttoVotePush.serializer(),
    AttoMessageType.VOTE_REQUEST to AttoVoteRequest.serializer(),
    AttoMessageType.VOTE_RESPONSE to AttoVoteResponse.serializer(),
    AttoMessageType.BOOTSTRAP_TRANSACTION_PUSH to AttoBootstrapTransactionPush.serializer(),
    AttoMessageType.UNKNOWN to AttoUnknown.serializer(),
)

@Suppress("UNCHECKED_CAST")
inline fun <reified T : AttoMessage> AttoMessageType.messageSerializer(): KSerializer<T> {
    return messageSerializerMap[this] as KSerializer<T>
}