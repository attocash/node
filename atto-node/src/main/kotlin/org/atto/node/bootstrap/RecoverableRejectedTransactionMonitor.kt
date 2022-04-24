//package org.atto.node.bootstrap
//
//import com.github.benmanes.caffeine.cache.Cache
//import com.github.benmanes.caffeine.cache.Caffeine
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.launch
//import org.atto.commons.AttoHash
//import org.atto.node.EventPublisher
//import org.atto.node.network.InboundNetworkMessage
//import org.atto.node.transaction.TransactionRejected
//import org.atto.node.transaction.TransactionRejectionReason
//import org.atto.node.transaction.TransactionService
//import org.atto.node.vote.VoteService
//import org.atto.node.vote.validator.VoteValidator
//import org.atto.node.vote.weight.VoteWeightService
//import org.atto.protocol.transaction.Transaction
//import org.atto.protocol.transaction.TransactionStatus
//import org.atto.protocol.vote.AttoVotePush
//import org.springframework.context.event.EventListener
//import org.springframework.stereotype.Service
//import java.util.concurrent.TimeUnit
//
//@Service
//class RecoverableRejectedTransactionMonitor(
//    properties: FinderProperties,
//    private val scope: CoroutineScope,
//    private val eventPublisher: EventPublisher,
//    private val transactionService: TransactionService,
//    private val voteValidator: VoteValidator,
//    private val voteWeightService: VoteWeightService,
//    private val voteService: VoteService
//) {
//    private val weighters: Cache<AttoHash, TransactionVoteWeighter> = Caffeine.newBuilder()
//        .expireAfterAccess(properties.cacheExpirationTimeInSeconds, TimeUnit.SECONDS)
//        .maximumSize(properties.cacheMaxSize)
//        .build()
//
//    @EventListener
//    fun process(event: TransactionRejected) {
//        if (!event.reasons.recoverable) {
//            return
//        }
//
//        val transaction = event.transaction
//
//        weighters.asMap().putIfAbsent(transaction.hash, TransactionVoteWeighter(event.reasons, transaction))
//    }
//
//    @EventListener
//    fun process(message: InboundNetworkMessage<AttoVotePush>) {
//        val hashVote = message.payload.hashVote
//
//        if (!hashVote.signature.isFinal() || voteValidator.validate(hashVote) != null) {
//            return
//        }
//
//        val weighter = weighters.asMap().computeIfPresent(hashVote.hash) { _, v ->
//            val weightedHashVote = WeightedHashVote(hashVote, voteWeightService.get(hashVote.signature.publicKey))
//            v.add(weightedHashVote)
//            v
//        } ?: return
//
//        if (voteWeightService.getMinimalConfirmationWeight() > weighter.totalWeight) {
//            return
//        }
//
//        weighters.invalidate(hashVote.hash)
//
//        scope.launch {
//            val transaction = weighter.transaction.copy(status = TransactionStatus.VOTED)
//            val hashVotes = weighter.votes.asSequence().map { it.hashVote }.toHashSet()
//
//            transactionService.save(transaction)
//            hashVotes.forEach { voteService.save(it) }
//
//            val event = AttoTransactionVoted(transaction, weighter.rejectionReason)
//            eventPublisher.publish(event)
//        }
//
//    }
//
//    private class TransactionVoteWeighter(
//        val rejectionReason: TransactionRejectionReason,
//        val transaction: Transaction
//    ) {
//        var totalWeight = 0UL
//        val votes = HashSet<WeightedHashVote>()
//
//        fun add(vote: WeightedHashVote) {
//            val new = votes.add(vote)
//            if (new) {
//                totalWeight += vote.weight
//            }
//        }
//    }
//}