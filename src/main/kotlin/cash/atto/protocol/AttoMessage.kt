package cash.atto.protocol

import cash.atto.commons.AttoNetwork
import cash.atto.protocol.bootstrap.AttoBootstrapTransactionPush
import cash.atto.protocol.network.handshake.AttoHandshakeAcceptance
import cash.atto.protocol.network.handshake.AttoHandshakeAnswer
import cash.atto.protocol.network.handshake.AttoHandshakeChallenge
import cash.atto.protocol.network.peer.AttoKeepAlive
import cash.atto.protocol.transaction.*
import cash.atto.protocol.vote.AttoVotePush
import cash.atto.protocol.vote.AttoVoteStreamCancel
import cash.atto.protocol.vote.AttoVoteStreamRequest
import cash.atto.protocol.vote.AttoVoteStreamResponse
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

val messageSerializerMap =
    mapOf(
        AttoMessageType.HANDSHAKE_CHALLENGE to AttoHandshakeChallenge.serializer(),
        AttoMessageType.HANDSHAKE_ANSWER to AttoHandshakeAnswer.serializer(),
        AttoMessageType.HANDSHAKE_ACCEPTANCE to AttoHandshakeAcceptance.serializer(),
        AttoMessageType.KEEP_ALIVE to AttoKeepAlive.serializer(),
        AttoMessageType.TRANSACTION_PUSH to AttoTransactionPush.serializer(),
        AttoMessageType.TRANSACTION_REQUEST to AttoTransactionRequest.serializer(),
        AttoMessageType.TRANSACTION_RESPONSE to AttoTransactionResponse.serializer(),
        AttoMessageType.TRANSACTION_STREAM_REQUEST to AttoTransactionStreamRequest.serializer(),
        AttoMessageType.TRANSACTION_STREAM_RESPONSE to AttoTransactionStreamResponse.serializer(),
        AttoMessageType.VOTE_PUSH to AttoVotePush.serializer(),
        AttoMessageType.VOTE_STREAM_REQUEST to AttoVoteStreamRequest.serializer(),
        AttoMessageType.VOTE_STREAM_RESPONSE to AttoVoteStreamResponse.serializer(),
        AttoMessageType.VOTE_STREAM_CANCEL to AttoVoteStreamCancel.serializer(),
        AttoMessageType.BOOTSTRAP_TRANSACTION_PUSH to AttoBootstrapTransactionPush.serializer(),
        AttoMessageType.UNKNOWN to AttoUnknown.serializer(),
    )

@Suppress("UNCHECKED_CAST")
inline fun <reified T : AttoMessage> AttoMessageType.messageSerializer(): KSerializer<T> = messageSerializerMap[this] as KSerializer<T>
