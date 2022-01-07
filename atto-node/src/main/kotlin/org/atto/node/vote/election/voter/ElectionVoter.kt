package org.atto.node.vote.election.voter

import mu.KotlinLogging
import org.atto.commons.AttoHashes
import org.atto.commons.AttoPrivateKey
import org.atto.commons.sign
import org.atto.commons.toByteArray
import org.atto.node.EventPublisher
import org.atto.node.network.BroadcastNetworkMessage
import org.atto.node.network.BroadcastStrategy
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.vote.HashVoteValidated
import org.atto.node.vote.WeightedHashVote
import org.atto.node.vote.election.ElectionObserver
import org.atto.node.vote.weight.VoteWeightService
import org.atto.protocol.Node
import org.atto.protocol.transaction.PublicKeyHeight
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.vote.HashVote
import org.atto.protocol.vote.Vote
import org.atto.protocol.vote.VotePush
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ElectionVoter(
    private val thisNode: Node,
    private val privateKey: AttoPrivateKey,
    private val voteWeightService: VoteWeightService,
    private val eventPublisher: EventPublisher,
    private val messagePublisher: NetworkMessagePublisher
) : ElectionObserver {
    private val logger = KotlinLogging.logger {}

    private val transactions = HashMap<PublicKeyHeight, Transaction>()
    private val agreements = HashSet<PublicKeyHeight>()

    override suspend fun observed(transaction: Transaction) {
        if (transactions[transaction.toPublicKeyHeight()] == null) {
            consensed(transaction)
        }
    }

    override suspend fun consensed(transaction: Transaction) {
        val publicKeyHeight = transaction.toPublicKeyHeight()

        val oldTransaction = transactions[publicKeyHeight]
        if (oldTransaction != transaction) {
            transactions[publicKeyHeight] = transaction
            vote(transaction, Instant.now())
        }
    }

    override suspend fun agreed(transaction: Transaction) {
        val publicKeyHeight = transaction.toPublicKeyHeight()
        if (!agreements.contains(publicKeyHeight)) {
            agreements.add(publicKeyHeight)
            vote(transaction, Vote.finalTimestamp)
        }
    }

    override suspend fun confirmed(transaction: Transaction, hashVotes: Collection<HashVote>) {
        remove(transaction)
    }

    override suspend fun staling(transaction: Transaction) {
        vote(transaction, Instant.now())
    }

    override suspend fun staled(transaction: Transaction) {
        remove(transaction)
    }

    private fun vote(transaction: Transaction, timestamp: Instant) {
        val weight = voteWeightService.get()
        if (!canVoter(weight)) {
            return
        }

        val voteHash = AttoHashes.hash(32, transaction.hash.value + timestamp.toByteArray())
        val vote = Vote(
            timestamp = timestamp,
            publicKey = thisNode.publicKey,
            signature = privateKey.sign(voteHash)
        )
        val hashVote = HashVote(
            hash = transaction.hash,
            vote = vote,
            receivedTimestamp = Instant.now()
        )
        val votePush = VotePush(
            hashVote = hashVote
        )

        val strategy = if (vote.isFinal()) {
            BroadcastStrategy.EVERYONE
        } else {
            BroadcastStrategy.VOTERS
        }

        logger.debug { "Sending to $strategy $hashVote" }

        eventPublisher.publish(HashVoteValidated(WeightedHashVote(hashVote, weight)))
        messagePublisher.publish(BroadcastNetworkMessage(strategy, emptySet(), this, votePush))
    }

    private fun canVoter(weight: ULong): Boolean {
        return thisNode.isVoter() && weight > 0UL
    }

    private fun remove(transaction: Transaction) {
        val publicKeyHeight = transaction.toPublicKeyHeight()
        transactions.remove(publicKeyHeight)
        agreements.remove(publicKeyHeight)
    }

}