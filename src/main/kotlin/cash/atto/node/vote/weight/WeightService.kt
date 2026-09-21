package cash.atto.node.vote.weight

import cash.atto.commons.AttoPublicKey
import cash.atto.commons.toBigInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Arrays
import java.util.concurrent.TimeUnit

@Service
class WeightService(
    private val weightRepository: WeightRepository,
    transactionManager: ReactiveTransactionManager,
) {
    private val writeMutex = Mutex()
    private val writeTransaction = TransactionalOperator.create(transactionManager)
    private val publicKeyComparator = Comparator<AttoPublicKey> { left, right -> Arrays.compareUnsigned(left.value, right.value) }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    suspend fun refresh(): Flow<Weight> {
        val weights = weightRepository.findCalculatedWeights().toList()
        replaceAll(weights)
        return weights.asFlow()
    }

    suspend fun recordLastVoteTimestamps(timestamps: Map<AttoPublicKey, Instant>) {
        writeMutex.withLock {
            writeTransaction.executeAndAwait {
                timestamps.entries
                    .sortedWith(compareBy(publicKeyComparator) { it.key })
                    .forEach { (publicKey, timestamp) ->
                        weightRepository.recordLastVoteTimestamp(publicKey, timestamp.toUtcDateTime())
                    }
            }
        }
    }

    private suspend fun replaceAll(weights: Collection<Weight>) {
        writeMutex.withLock {
            writeTransaction.executeAndAwait {
                val sortedWeights = weights.sortedWith(compareBy(publicKeyComparator) { it.representativePublicKey })
                sortedWeights.forEach { weight ->
                    weightRepository.upsert(
                        weight.representativePublicKey,
                        weight.weight.raw.toBigInteger(),
                        weight.lastVoteTimestamp.toUtcDateTime(),
                    )
                }

                if (sortedWeights.isEmpty()) {
                    weightRepository.deleteAll()
                } else {
                    weightRepository.deleteAllExcept(sortedWeights.map { it.representativePublicKey })
                }
            }
        }
    }

    private fun Instant.toUtcDateTime(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
}
