package org.atto.node.vote.convertion

import kotlinx.coroutines.runBlocking
import org.atto.node.EventPublisher
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.vote.Vote
import org.atto.node.vote.VoteReceived
import org.atto.node.vote.weight.VoteWeightService
import org.atto.protocol.vote.AttoVotePush
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class VoteConverter(
    private val weightService: VoteWeightService,
    private val eventPublisher: EventPublisher
) {

    @EventListener
    fun add(message: InboundNetworkMessage<AttoVotePush>) = runBlocking {
        val attoVote = message.payload.vote

        val weight = weightService.get(attoVote.signature.publicKey)
        val vote = Vote.from(weight, attoVote)

        eventPublisher.publish(VoteReceived(message.socketAddress, vote))
    }

}