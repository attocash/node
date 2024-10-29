package cash.atto.node.vote.convertion

import cash.atto.commons.AttoSignedVote
import cash.atto.node.EventPublisher
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteReceived
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoVotePush
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class VoteConverter(
    private val weightService: VoteWeighter,
    private val eventPublisher: EventPublisher,
) {
    @EventListener
    fun add(message: InboundNetworkMessage<AttoVotePush>) {
        val attoVote = message.payload.vote
        eventPublisher.publish(VoteReceived(message.publicUri, convert(attoVote)))
    }

    fun convert(attoVote: AttoSignedVote): Vote {
        val weight = weightService.get(attoVote.vote.publicKey)
        return Vote.from(weight, attoVote)
    }
}
