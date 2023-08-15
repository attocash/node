package atto.node.vote.convertion

import atto.node.EventPublisher
import atto.node.network.InboundNetworkMessage
import atto.node.vote.Vote
import atto.node.vote.VoteReceived
import atto.node.vote.weight.VoteWeighter
import atto.protocol.vote.AttoVote
import atto.protocol.vote.AttoVotePush
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
class VoteConverter(
    private val weightService: VoteWeighter,
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