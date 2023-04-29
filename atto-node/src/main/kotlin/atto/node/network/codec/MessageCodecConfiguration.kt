package atto.node.network.codec

import atto.protocol.AttoNode
import atto.protocol.network.codec.AttoNodeCodec
import atto.protocol.network.codec.bootstrap.AttoBootstrapTransactionPushCodec
import atto.protocol.network.codec.peer.AttoKeepAliveCodec
import atto.protocol.network.codec.peer.handshake.AttoHandshakeAnswerCodec
import atto.protocol.network.codec.peer.handshake.AttoHandshakeChallengeCodec
import atto.protocol.network.codec.transaction.*
import atto.protocol.network.codec.vote.*
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
    fun attoTransactionCodec(thisNode: atto.protocol.AttoNode): AttoTransactionCodec {
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
    fun transactionStreamResponseCodec(thisNode: atto.protocol.AttoNode): AttoTransactionStreamResponseCodec {
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