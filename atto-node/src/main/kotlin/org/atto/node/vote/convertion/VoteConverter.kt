package org.atto.node.vote.convertion

import org.atto.node.EventPublisher
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.vote.Vote
import org.atto.node.vote.VoteReceived
import org.atto.node.vote.weight.VoteWeightService
import org.atto.protocol.vote.AttoVote
import org.atto.protocol.vote.AttoVotePush
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
class VoteConverter(
    private val weightService: VoteWeightService,
    private val eventPublisher: EventPublisher
) {

    @EventListener
    @Async
    fun add(message: InboundNetworkMessage<AttoVotePush>) {
        val attoVote = message.payload.vote

        eventPublisher.publish(VoteReceived(message.socketAddress, convert(attoVote)))
    }

    fun convert(attoVote: AttoVote): Vote {
        val weight = weightService.get(attoVote.signature.publicKey)
        return Vote.from(weight, attoVote)
    }

}