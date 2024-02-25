package atto.node.vote.weight

import atto.node.CacheSupport
import atto.node.account.AccountRepository
import atto.node.toULong
import atto.node.transaction.Transaction
import atto.node.transaction.TransactionSaved
import atto.node.vote.Vote
import atto.node.vote.VoteRepository
import atto.node.vote.VoteValidated
import atto.protocol.AttoNode
import cash.atto.commons.*
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.context.annotation.DependsOn
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.lang.Integer.min
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max

@Component
@DependsOn("genesisTransaction")
class VoteWeighter(
    val thisNode: AttoNode,
    val properties: VoteWeightProperties,
    val accountRepository: AccountRepository,
    val voteRepository: VoteRepository,
    val genesisTransaction: Transaction,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val weightMap = ConcurrentHashMap<AttoPublicKey, AttoAmount>()
    private val latestVoteMap = ConcurrentHashMap<AttoPublicKey, Vote>()
    private lateinit var minimalRebroadcastWeight: AttoAmount
    private lateinit var minimalConfirmationWeight: AttoAmount

    @PostConstruct
    override fun init() = runBlocking {
        val weights = accountRepository.findAllWeights()
            .associateBy({ it.publicKey }, { AttoAmount(it.weight.toULong()) })
        weightMap.putAll(weights)

        val minTimestamp = getMinTimestamp()
        val voteMap = voteRepository.findLatestAfter(minTimestamp).asSequence()
            .map { it.publicKey to it }
            .toMap()
        latestVoteMap.putAll(voteMap)
        calculateMinimalWeights()
    }

    @EventListener
    fun listen(event: VoteValidated) {
        val vote = event.vote
        latestVoteMap.compute(vote.publicKey) { _, previousHashVote ->
            if (previousHashVote == null || vote.timestamp > previousHashVote.timestamp) {
                vote
            } else {
                previousHashVote
            }
        }
    }

    @EventListener
    fun listen(event: TransactionSaved) {
        val previousAccount = event.previousAccount
        val updatedAccount = event.updatedAccount
        val transaction = event.transaction
        val block = transaction.block

        if (transaction == genesisTransaction) {
            return
        }

        when (block) {
            is AttoOpenBlock -> {
                add(block.representative, block.balance, block.balance)
            }

            is AttoReceiveBlock -> {
                val amount = block.balance - previousAccount.balance
                add(updatedAccount.representative, amount, block.balance)
            }

            is AttoSendBlock -> {
                subtract(updatedAccount.representative, block.amount, block.balance)
            }

            is AttoChangeBlock -> {
                subtract(previousAccount.representative, updatedAccount.balance, AttoAmount.MIN)
                add(block.representative, updatedAccount.balance, updatedAccount.balance)
            }
        }

        logger.trace { "Weight updated $weightMap" }
    }

    private fun add(publicKey: AttoPublicKey, amount: AttoAmount, defaultAmount: AttoAmount) {
        weightMap.compute(publicKey) { _, weight ->
            if (weight == null) {
                defaultAmount
            } else {
                weight + amount
            }
        }
    }

    private fun subtract(publicKey: AttoPublicKey, amount: AttoAmount, defaultAmount: AttoAmount) {
        weightMap.compute(publicKey) { _, weight ->
            if (weight == null) {
                defaultAmount
            } else {
                weight - amount
            }
        }
    }

    fun getAll(): Map<AttoPublicKey, AttoAmount> {
        return weightMap.toMap()
    }

    fun get(publicKey: AttoPublicKey): AttoAmount {
        return weightMap[publicKey] ?: AttoAmount.MIN
    }

    fun get(): AttoAmount {
        return get(thisNode.publicKey)
    }

    fun getMinimalConfirmationWeight(): AttoAmount {
        return minimalConfirmationWeight
    }

    fun isAboveMinimalRebroadcastWeight(publicKey: AttoPublicKey): Boolean {
        return minimalRebroadcastWeight <= get(publicKey)
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    fun calculateMinimalWeights() {
        val minTimestamp = getMinTimestamp()

        val onlineWeights = weightMap.asSequence()
            .filter { minTimestamp < (latestVoteMap[it.key]?.timestamp ?: Instant.MIN) }
            .sortedByDescending { it.value.raw }
            .toList()

        val onlineWeight = onlineWeights.sumOf { it.value.raw }

        val minimalConfirmationWeight = onlineWeight * properties.confirmationThreshold!!.toUByte() / 100U
        val defaultMinimalConfirmationWeight = properties.minimalConfirmationWeight!!.toString().toULong()
        this.minimalConfirmationWeight = max(minimalConfirmationWeight, defaultMinimalConfirmationWeight).toAttoAmount()

        logger.info { "Minimal confirmation weight updated to ${this.minimalConfirmationWeight}" }

        val minimalConfirmationVoteCount = onlineWeights.asSequence()
            .takeWhile { it.value > this.minimalConfirmationWeight }
            .count()

        val i = min(minimalConfirmationVoteCount * 2, onlineWeights.size - 1)
        if (onlineWeights.isNotEmpty()) {
            this.minimalRebroadcastWeight = onlineWeights[i].value
        } else {
            this.minimalRebroadcastWeight = properties.minimalRebroadcastWeight!!.toAttoAmount()
        }

        logger.info { "Minimal rebroadcast weight updated to ${this.minimalRebroadcastWeight}" }
    }

    fun getMinTimestamp(): Instant {
        return ZonedDateTime.now(ZoneOffset.UTC).minusDays(properties.samplePeriodInDays!!).toInstant()
    }

    override fun clear() {
        weightMap.clear()
        latestVoteMap.clear()
    }

}