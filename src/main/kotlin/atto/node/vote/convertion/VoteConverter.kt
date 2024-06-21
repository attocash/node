package atto.node.vote.convertion

import atto.node.EventPublisher
import atto.node.network.InboundNetworkMessage
import atto.node.vote.Vote
import atto.node.vote.VoteReceived
import atto.node.vote.weight.VoteWeighter
import atto.protocol.vote.AttoVote
import atto.protocol.vote.AttoVotePush
import cash.atto.commons.AttoHash
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class VoteConverter(
    private val weightService: VoteWeighter,
    private val eventPublisher: EventPublisher,
) {
    @EventListener
    fun add(message: InboundNetworkMessage<AttoVotePush>) {
        val hash = message.payload.blockHash
        val attoVote = message.payload.vote

        eventPublisher.publish(VoteReceived(message.publicUri, convert(hash, attoVote)))
    }

    fun convert(
        hash: AttoHash,
        attoVote: AttoVote,
    ): Vote {
        val weight = weightService.get(attoVote.publicKey)
        return Vote.from(weight, hash, attoVote)
    }
}
