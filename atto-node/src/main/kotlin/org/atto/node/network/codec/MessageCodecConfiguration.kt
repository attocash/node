package org.atto.node.network.codec

import org.atto.protocol.Node
import org.atto.protocol.network.codec.NodeCodec
import org.atto.protocol.network.codec.peer.KeepAliveCodec
import org.atto.protocol.network.codec.peer.handshake.HandshakeAnswerCodec
import org.atto.protocol.network.codec.peer.handshake.HandshakeChallengeCodec
import org.atto.protocol.network.codec.transaction.TransactionCodec
import org.atto.protocol.network.codec.transaction.TransactionPushCodec
import org.atto.protocol.network.codec.vote.HashVoteCodec
import org.atto.protocol.network.codec.vote.VoteCodec
import org.atto.protocol.network.codec.vote.VotePushCodec
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MessageCodecConfiguration {

    @Bean
    fun handshakeChallengeCodec(): HandshakeChallengeCodec {
        return HandshakeChallengeCodec()
    }

    @Bean
    fun handshakeAnswerCodec(): HandshakeAnswerCodec {
        return HandshakeAnswerCodec(NodeCodec())
    }

    @Bean
    fun keepAliveCodec(): KeepAliveCodec {
        return KeepAliveCodec()
    }

    @Bean
    fun transactionCodec(thisNode: Node): TransactionCodec {
        return TransactionCodec(thisNode.network)
    }

    @Bean
    fun transactionPushCodec(transactionCodec: TransactionCodec): TransactionPushCodec {
        return TransactionPushCodec(transactionCodec)
    }

    @Bean
    fun votePushCodec(thisNode: Node): VotePushCodec {
        return VotePushCodec(HashVoteCodec(VoteCodec()))
    }

}