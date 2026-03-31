package cash.atto.node.vote.weight

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoChangeBlock
import cash.atto.commons.AttoOpenBlock
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSendBlock
import cash.atto.commons.toAttoAmount
import cash.atto.node.CacheSupport
import cash.atto.node.account.AccountUpdated
import cash.atto.node.transaction.Transaction
import cash.atto.node.vote.VoteValidated
import cash.atto.protocol.AttoNode
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.springframework.context.annotation.DependsOn
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

@Component
@DependsOn("genesisTransaction")
class VoteWeighter(
    val thisNode: AttoNode,
    val properties: VoteWeightProperties,
    val weightService: WeightService,
    val genesisTransaction: Transaction,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val weightMap = ConcurrentHashMap<AttoPublicKey, Weight>()
    private lateinit var onlineWeight: AttoAmount
    private lateinit var minimalRebroadcastWeight: AttoAmount
    private lateinit var minimalConfirmationWeight: AttoAmount

    @PostConstruct
    override fun init() =
        runBlocking {
            val allWeights =
                weightService
                    .refresh()
                    .toList()

            weightMap.putAll(allWeights.associateBy { it.representativePublicKey })

            calculateMinimalWeights()
        }

    override fun clear() {
        weightMap.clear()
    }

    @EventListener
    fun listen(event: VoteValidated) {
        val vote = event.vote
        weightMap.computeIfPresent(vote.publicKey) { _, existing ->
            if (existing.lastVoteTimestamp == null || vote.receivedAt > existing.lastVoteTimestamp) {
                existing.copy(lastVoteTimestamp = vote.receivedAt)
            } else {
                existing
            }
        }
    }

    @EventListener
    suspend fun listen(event: AccountUpdated) {
        val previousAccount = event.previousAccount
        val updatedAccount = event.updatedAccount
        val transaction = event.transaction
        val block = transaction.block

        if (transaction == genesisTransaction) {
            return
        }

        when (block) {
            is AttoOpenBlock -> {
                add(block.representativePublicKey, block.balance, block.balance)
            }

            is AttoReceiveBlock -> {
                val amount = block.balance - previousAccount.balance
                add(updatedAccount.representativePublicKey, amount, block.balance)
            }

            is AttoSendBlock -> {
                subtract(updatedAccount.representativePublicKey, block.amount, block.balance)
            }

            is AttoChangeBlock -> {
                subtract(previousAccount.representativePublicKey, block.balance, AttoAmount.MIN)
                add(block.representativePublicKey, block.balance, block.balance)
            }
        }

        logger.trace { "Weight updated $weightMap" }
    }

    fun getLatestVoteTimestamp(publicKey: AttoPublicKey): Instant? = weightMap[publicKey]?.lastVoteTimestamp

    private suspend fun add(
        publicKey: AttoPublicKey,
        amount: AttoAmount,
        defaultAmount: AttoAmount,
    ) {
        retryUntilSuccess {
            weightMap.compute(publicKey) { _, existing ->
                if (existing == null) {
                    Weight(representativePublicKey = publicKey, weight = defaultAmount)
                } else {
                    existing.copy(weight = existing.weight + amount)
                }
            }
        }
    }

    private suspend fun subtract(
        publicKey: AttoPublicKey,
        amount: AttoAmount,
        defaultAmount: AttoAmount,
    ) {
        retryUntilSuccess {
            weightMap.compute(publicKey) { _, existing ->
                val newWeight =
                    if (existing == null) {
                        defaultAmount
                    } else {
                        existing.weight - amount
                    }
                if (newWeight > AttoAmount.MIN) {
                    return@compute (existing ?: Weight(representativePublicKey = publicKey, weight = newWeight)).copy(weight = newWeight)
                } else {
                    return@compute null
                }
            }
        }
    }

    fun getAll(): Map<AttoPublicKey, AttoAmount> = weightMap.mapValues { it.value.weight }

    fun get(publicKey: AttoPublicKey): AttoAmount = weightMap[publicKey]?.weight ?: AttoAmount.MIN

    fun get(): AttoAmount = get(thisNode.publicKey)

    fun getMinimalConfirmationWeight(): AttoAmount = minimalConfirmationWeight

    fun getMinimalToStaleWeight(): AttoAmount {
        val onlineWeight = onlineWeight
        val minimalConfirmationWeight = minimalConfirmationWeight
        /*
         * Prevent underflow during bootstrap
         */
        if (onlineWeight <= minimalConfirmationWeight) {
            return minimalConfirmationWeight
        }
        return onlineWeight - minimalConfirmationWeight
    }

    fun isAboveMinimalRebroadcastWeight(publicKey: AttoPublicKey): Boolean = minimalRebroadcastWeight <= get(publicKey)

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    fun calculateMinimalWeights() {
        val minTimestamp = getMinTimestamp()

        val onlineWeights =
            weightMap
                .asSequence()
                .filter { minTimestamp < (it.value.lastVoteTimestamp ?: Instant.MIN) }
                .sortedByDescending { it.value.weight.raw }
                .toList()

        val onlineWeight = onlineWeights.sumOf { it.value.weight.raw }

        this.onlineWeight = onlineWeight.toAttoAmount()

        logger.info { "Total online vote weight $onlineWeight" }

        onlineWeights
            .asSequence()
            .take(10)
            .forEach { logger.info { "Top accounts ${it.key} ${it.value}" } }

        val confirmationThreshold = properties.confirmationThreshold!!.toULong()

        val minimalConfirmationWeight = (onlineWeight / 100UL) * confirmationThreshold
        val defaultMinimalConfirmationWeight = properties.minimalConfirmationWeight!!.replace("_", "").toULong()
        this.minimalConfirmationWeight = max(minimalConfirmationWeight, defaultMinimalConfirmationWeight).toAttoAmount()

        logger.info { "Minimal confirmation weight updated to ${this.minimalConfirmationWeight}" }

        if (onlineWeights.size >= 10) {
            this.minimalRebroadcastWeight = onlineWeights[9].value.weight
        } else if (onlineWeights.isNotEmpty()) {
            this.minimalRebroadcastWeight = onlineWeights.last().value.weight
        } else {
            this.minimalRebroadcastWeight = properties.minimalRebroadcastWeight!!.replace("_", "").toAttoAmount()
        }

        logger.info { "Minimal rebroadcast weight updated to ${this.minimalRebroadcastWeight}" }
    }

    fun getMinTimestamp(): Instant = Instant.now().minusSeconds(properties.samplePeriodInDays!!.days.inWholeSeconds)

    suspend fun retryUntilSuccess(
        pause: Duration = 1.seconds,
        maxAttempts: Int = Int.MAX_VALUE,
        block: suspend () -> Unit,
    ) {
        var attempts = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                if (++attempts >= maxAttempts) throw e
                delay(pause)
            }
        }
    }
}
