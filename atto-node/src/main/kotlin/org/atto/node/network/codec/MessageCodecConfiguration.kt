package org.atto.node.network.codec

import org.atto.protocol.AttoNode
import org.atto.protocol.network.codec.AttoNodeCodec
import org.atto.protocol.network.codec.peer.AttoKeepAliveCodec
import org.atto.protocol.network.codec.peer.handshake.AttoHandshakeAnswerCodec
import org.atto.protocol.network.codec.peer.handshake.AttoHandshakeChallengeCodec
import org.atto.protocol.network.codec.transaction.AttoTransactionCodec
import org.atto.protocol.network.codec.transaction.AttoTransactionPushCodec
import org.atto.protocol.network.codec.vote.AttoSignatureCodec
import org.atto.protocol.network.codec.vote.AttoVoteCodec
import org.atto.protocol.network.codec.vote.AttoVotePushCodec
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MessageCodecConfiguration {

    @Bean
    fun handshakeChallengeCodec(): AttoHandshakeChallengeCodec {
        return AttoHandshakeChallengeCodec()
    }

    @Bean
    fun handshakeAnswerCodec(): AttoHandshakeAnswerCodec {
        return AttoHandshakeAnswerCodec(AttoNodeCodec())
    }

    @Bean
    fun keepAliveCodec(): AttoKeepAliveCodec {
        return AttoKeepAliveCodec()
    }

    @Bean
    fun attoTransactionCodec(thisNode: AttoNode): AttoTransactionCodec {
        return AttoTransactionCodec(thisNode.network)
    }

    @Bean
    fun transactionCodec(attoTransactionCodec: AttoTransactionCodec): TransactionCodec {
        return TransactionCodec(attoTransactionCodec)
    }

    @Bean
    fun transactionPushCodec(transactionCodec: AttoTransactionCodec): AttoTransactionPushCodec {
        return AttoTransactionPushCodec(transactionCodec)
    }

    @Bean
    fun votePushCodec(thisNode: AttoNode): AttoVotePushCodec {
        return AttoVotePushCodec(AttoVoteCodec(AttoSignatureCodec()))
    }

}