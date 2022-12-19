package org.atto.node.election

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.atto.commons.*
import org.atto.node.EventPublisher
import org.atto.node.account.Account
import org.atto.node.network.BroadcastNetworkMessage
import org.atto.node.network.BroadcastStrategy
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.transaction.PublicKeyHeight
import org.atto.node.transaction.Transaction
import org.atto.node.vote.Vote
import org.atto.node.vote.VoteValidated
import org.atto.node.vote.weight.VoteWeightService
import org.atto.protocol.AttoNode
import org.atto.protocol.vote.AttoVote
import org.atto.protocol.vote.AttoVotePush
import org.atto.protocol.vote.AttoVoteSignature
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Service
class ElectionVoter(
    private val thisNode: AttoNode,
    private val privateKey: AttoPrivateKey,
    private val voteWeightService: VoteWeightService,
    private val eventPublisher: EventPublisher,
    private val messagePublisher: NetworkMessagePublisher
) {
    private val logger = KotlinLogging.logger {}

    val defaultScope = CoroutineScope(Dispatchers.Default + CoroutineName("ElectionVoter"))

    private val minWeight = AttoAmount(1_000_000_000_000_000u)

    private val transactions = ConcurrentHashMap<PublicKeyHeight, Transaction>()
    private val agreements = ConcurrentHashMap.newKeySet<PublicKeyHeight>()

    @EventListener
    fun process(event: ElectionStarted) {
        val account = event.account
        val transaction = event.transaction
        if (transactions[transaction.toPublicKeyHeight()] == null) {
            consensed(account, transaction)
        }
    }

    @EventListener
    fun process(event: ElectionConsensusChanged) {
        val account = event.account
        val transaction = event.transaction
        consensed(account, transaction)
    }

    fun consensed(account: Account, transaction: Transaction) {
        val publicKeyHeight = transaction.toPublicKeyHeight()

        val oldTransaction = transactions[publicKeyHeight]
        if (oldTransaction != transaction) {
            transactions[publicKeyHeight] = transaction
            vote(transaction, Instant.now())
        }
    }


    @EventListener
    fun process(event: ElectionConsensusReached) {
        val account = event.account
        val transaction = event.transaction

        val publicKeyHeight = transaction.toPublicKeyHeight()
        if (!agreements.contains(publicKeyHeight)) {
            agreements.add(publicKeyHeight)
            vote(transaction, AttoVoteSignature.finalTimestamp)
        }
    }

    @EventListener
    fun process(event: ElectionFinished) {
        remove(event.transaction)
    }

    @EventListener
    fun process(event: ElectionExpiring) {
        vote(event.transaction, Instant.now())
    }

    @EventListener
    fun process(event: ElectionExpired) {
        remove(event.transaction)
    }

    private fun vote(transaction: Transaction, timestamp: Instant) {
        val weight = voteWeightService.get()
        if (!canVote(weight)) {
            return
        }

        val voteHash = AttoHashes.hash(32, transaction.hash.value, timestamp.toByteArray())
        val voteSignature = AttoVoteSignature(
            timestamp = timestamp,
            publicKey = thisNode.publicKey,
            signature = privateKey.sign(voteHash)
        )
        val attoVote = AttoVote(
            hash = transaction.hash,
            signature = voteSignature,
        )
        val votePush = AttoVotePush(
            vote = attoVote
        )

        val strategy = if (attoVote.signature.isFinal()) {
            BroadcastStrategy.EVERYONE
        } else {
            BroadcastStrategy.VOTERS
        }

        logger.debug { "Sending to $strategy $attoVote" }

        /**
         * singleDispatcher doesn't allow recursion causing the whole processing to deadlock once the Voter
         * cast a internal vote.
         *
         * To avoid the deadlock a new coroutines is used to publish the internal vote
         */
        defaultScope.launch {
            eventPublisher.publish(VoteValidated(transaction, Vote.from(weight, attoVote)))
        }

        messagePublisher.publish(BroadcastNetworkMessage(strategy, emptySet(), votePush))
    }

    private fun canVote(weight: AttoAmount): Boolean {
        return thisNode.isVoter() && weight >= minWeight
    }

    private fun remove(transaction: Transaction) {
        val publicKeyHeight = transaction.toPublicKeyHeight()
        transactions.remove(publicKeyHeight)
        agreements.remove(publicKeyHeight)
    }

}