package org.atto.node.network.codec

import org.atto.protocol.AttoNode
import org.atto.protocol.network.codec.AttoNodeCodec
import org.atto.protocol.network.codec.bootstrap.AttoBootstrapTransactionPushCodec
import org.atto.protocol.network.codec.peer.AttoKeepAliveCodec
import org.atto.protocol.network.codec.peer.handshake.AttoHandshakeAnswerCodec
import org.atto.protocol.network.codec.peer.handshake.AttoHandshakeChallengeCodec
import org.atto.protocol.network.codec.transaction.*
import org.atto.protocol.network.codec.vote.*
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
    fun transactionRequestCodec(): AttoTransactionRequestCodec {
        return AttoTransactionRequestCodec()
    }

    @Bean
    fun transactionResponseCodec(transactionCodec: AttoTransactionCodec): AttoTransactionResponseCodec {
        return AttoTransactionResponseCodec(transactionCodec)
    }

    @Bean
    fun transactionStreamRequestCodec(): AttoTransactionStreamRequestCodec {
        return AttoTransactionStreamRequestCodec()
    }

    @Bean
    fun transactionStreamResponseCodec(thisNode: AttoNode): AttoTransactionStreamResponseCodec {
        return AttoTransactionStreamResponseCodec(thisNode.network)
    }

    @Bean
    fun voteCodec(): AttoVoteCodec {
        return AttoVoteCodec(AttoSignatureCodec())
    }

    @Bean
    fun votePushCodec(voteCodec: AttoVoteCodec): AttoVotePushCodec {
        return AttoVotePushCodec(voteCodec)
    }

    @Bean
    fun voteRequestCodec(): AttoVoteRequestCodec {
        return AttoVoteRequestCodec()
    }

    @Bean
    fun voteResponseCodec(voteCodec: AttoVoteCodec): AttoVoteResponseCodec {
        return AttoVoteResponseCodec(voteCodec)
    }

    @Bean
    fun bootstrapTransactionPushCodec(transactionCodec: AttoTransactionCodec): AttoBootstrapTransactionPushCodec {
        return AttoBootstrapTransactionPushCodec(transactionCodec)
    }

}