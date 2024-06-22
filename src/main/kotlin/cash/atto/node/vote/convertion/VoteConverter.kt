package cash.atto.node.vote.convertion

import cash.atto.commons.AttoHash
import cash.atto.node.EventPublisher
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteReceived
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.vote.AttoVote
import cash.atto.protocol.vote.AttoVotePush
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
