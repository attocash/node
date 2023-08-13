package atto.node.vote.weight

import atto.node.CacheSupport
import atto.node.account.AccountRepository
import atto.node.election.ElectionFinished
import atto.node.vote.Vote
import atto.node.vote.VoteRepository
import atto.node.vote.VoteValidated
import cash.atto.commons.*
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.context.annotation.DependsOn
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.lang.Integer.min
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

@Component
@DependsOn("transactionGenesisInitializer")
class VoteWeighter(
    val thisNode: atto.protocol.AttoNode,
    val properties: VoteWeightProperties,
    val accountRepository: AccountRepository,
    val voteRepository: VoteRepository,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val weightMap = ConcurrentHashMap<AttoPublicKey, AttoAmount>()
    private val latestVoteMap = ConcurrentHashMap<AttoPublicKey, Vote>()
    private var minimalRebroadcastWeight = properties.minimalRebroadcastWeight!!.toAttoAmount()
    private var minimalConfirmationWeight = properties.minimalConfirmationWeight!!.toAttoAmount()

    @PostConstruct
    override fun init() = runBlocking {
        weightMap.putAll(accountRepository.findAllWeights().associateBy({ it.publicKey }, { it.weight }))

        val minTimestamp = getMinTimestamp()
        val voteMap = voteRepository.findLatestAfter(minTimestamp).asSequence()
            .map { it.publicKey to it }
            .toMap()
        latestVoteMap.putAll(voteMap)
        calculateMinimalWeights()
    }

    @EventListener
    @Async
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
    @Async
    fun listen(event: ElectionFinished) {
        val account = event.account
        val transaction = event.transaction
        val block = transaction.block

        if (block is AttoOpenBlock) {
            add(block.representative, block.balance, block.balance)
        } else if (block is AttoReceiveBlock) {
            val amount = block.balance - account.balance
            add(account.representative, amount, block.balance)
        } else if (block is AttoSendBlock) {
            subtract(account.representative, block.amount, block.balance)
        } else if (block is AttoChangeBlock) {
            subtract(account.representative, block.balance, AttoAmount.MIN)
            add(block.representative, block.balance, block.balance)
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

    fun getMinimalRebroadcastWeight() : AttoAmount {
        return minimalRebroadcastWeight
    }

    fun isAboveMinimalRebroadcastWeight(publicKey: AttoPublicKey): Boolean {
        return minimalRebroadcastWeight <= get(publicKey)
    }

    @Scheduled(cron = "0 0 0/1 * * *")
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
        return LocalDateTime.now().minusDays(properties.samplePeriodInDays!!).toInstant(ZoneOffset.UTC)
    }

    override fun clear() {
        weightMap.clear()
        latestVoteMap.clear()
    }

}