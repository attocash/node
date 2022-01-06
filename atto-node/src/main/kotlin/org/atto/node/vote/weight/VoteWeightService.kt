package org.atto.node.vote.weight

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.atto.commons.AttoBlockType
import org.atto.commons.AttoPublicKey
import org.atto.node.CacheSupport
import org.atto.node.transaction.TransactionConfirmed
import org.atto.node.transaction.TransactionRepository
import org.atto.node.vote.HashVoteRepository
import org.atto.protocol.Node
import org.atto.protocol.vote.HashVote
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.lang.Integer.min
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import javax.annotation.PostConstruct

@Component
class VoteWeightService(
    val thisNode: Node,
    val scope: CoroutineScope,
    val properties: VoteWeightProperties,
    val transactionRepository: TransactionRepository,
    val hashVoteRepository: HashVoteRepository
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val weightMap = ConcurrentHashMap<AttoPublicKey, ULong>()
    private val latestVoteMap = ConcurrentHashMap<AttoPublicKey, HashVote>()
    private var minimalRebroadcastWeight = 0UL
    private var minimalConfirmationWeight = 0UL

    @PostConstruct
    override fun init() = runBlocking {
        weightMap.putAll(transactionRepository.findAllWeights())
        latestVoteMap.putAll(hashVoteRepository.findLatestVotes().asSequence().map { it.vote.publicKey to it }.toMap())
        calculateMinimalWeights()
    }

    @EventListener
    fun listen(hashVote: HashVote) {
        latestVoteMap.compute(hashVote.vote.publicKey) { _, previousHashVote ->
            if (previousHashVote == null || hashVote.vote.timestamp > previousHashVote.vote.timestamp) {
                hashVote
            } else {
                previousHashVote
            }
        }
    }

    @EventListener
    fun listen(transactionConfirmed: TransactionConfirmed) {
        val transaction = transactionConfirmed.transaction
        val block = transaction.block
        when (block.type) {
            AttoBlockType.OPEN, AttoBlockType.RECEIVE -> {
                weightMap.compute(block.representative) { _, weight ->
                    if (weight == null) {
                        block.balance.raw
                    } else {
                        addExact(weight, block.amount.raw)
                    }
                }
            }
            AttoBlockType.SEND -> {
                weightMap.compute(block.representative) { _, weight ->
                    if (weight == null) {
                        block.balance.raw
                    } else {
                        subtractExact(weight, block.amount.raw)
                    }
                }
            }
            AttoBlockType.CHANGE -> {
                scope.launch {
                    val previousBlock = transactionRepository.findById(block.previous)!!.block

                    weightMap.compute(previousBlock.representative) { _, weight ->
                        if (weight == null) {
                            0UL
                        } else {
                            subtractExact(weight, previousBlock.amount.raw)
                        }
                    }

                    weightMap.compute(block.representative) { _, weight ->
                        if (weight == null) {
                            block.balance.raw
                        } else {
                            addExact(weight, block.amount.raw)
                        }
                    }
                }
            }
            else -> {
                throw IllegalArgumentException("Block type ${block.type} is not supported")
            }
        }
    }

    fun getAll(): Map<AttoPublicKey, ULong> {
        return weightMap.toMap()
    }

    fun get(publicKey: AttoPublicKey): ULong {
        return weightMap[publicKey] ?: 0UL
    }

    fun get(): ULong {
        return get(thisNode.publicKey)
    }

    fun getMinimalConfirmationWeight(): ULong {
        return minimalConfirmationWeight
    }

    fun isAboveMinimalRebroadcastWeight(publicKey: AttoPublicKey): Boolean {
        return minimalRebroadcastWeight <= get(publicKey)
    }

    @Scheduled(cron = "0 0 0/1 * * *")
    fun calculateMinimalWeights() {
        val minTimestamp = LocalDateTime.now().minusDays(properties.samplePeriodInDays!!).toInstant(ZoneOffset.UTC)

        val onlineWeights = weightMap.asSequence()
            .filter { minTimestamp < (latestVoteMap[it.key]?.vote?.timestamp ?: Instant.MIN) }
            .sortedByDescending { it.value }
            .toList()

        val onlineWeight = onlineWeights.sumOf { it.value }

        val minimalConfirmationWeight = onlineWeight * properties.confirmationThreshold!!.toUByte() / 100U
        val defaultMinimalConfirmationWeight = properties.minimalConfirmationWeight!!.toString().toULong()
        this.minimalConfirmationWeight = max(minimalConfirmationWeight, defaultMinimalConfirmationWeight)

        logger.info { "Minimal confirmation weight updated to ${this.minimalConfirmationWeight}" }

        val minimalConfirmationVoteCount = onlineWeights.asSequence()
            .takeWhile { it.value > this.minimalConfirmationWeight }
            .count()

        val i = min(minimalConfirmationVoteCount * 2, onlineWeights.size - 1)
        if (onlineWeights.size > 0) {
            this.minimalRebroadcastWeight = onlineWeights[i].value
        } else {
            this.minimalRebroadcastWeight = properties.minimalRebroadcastWeight!!.toString().toULong()
        }

        logger.info { "Minimal rebroadcast weight updated to ${this.minimalRebroadcastWeight}" }
    }

    override fun clear() {
        weightMap.clear()
        latestVoteMap.clear()
    }


    private fun max(x: ULong, y: ULong): ULong {
        if (x > y) {
            return x
        }
        return y
    }

    private fun addExact(x: ULong, y: ULong): ULong {
        val total = x + y
        if (total < x || total < y) {
            throw IllegalStateException("ULong overflow")
        }
        return total
    }

    private fun subtractExact(x: ULong, y: ULong): ULong {
        val total = x - y
        if (total > x) {
            throw IllegalStateException("ULong underflow")
        }
        return total
    }

}