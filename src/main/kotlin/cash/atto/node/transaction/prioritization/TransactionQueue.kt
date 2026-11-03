package cash.atto.node.transaction.prioritization

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoSendBlock
import cash.atto.commons.toJavaInstant
import cash.atto.node.transaction.Transaction
import java.time.temporal.ChronoUnit
import java.util.Arrays
import java.util.Comparator.comparing
import java.util.SortedMap
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.absoluteValue
import kotlin.math.min

class TransactionQueue(
    private val groupMaxSize: Int,
    private val maxGroup: Int = 19,
) {
    companion object {
        /**
         * Smaller groups are easier to exploit during a spam attack due to low amount invested.
         *
         * To reduce the attack surface the groups below 0.001 USD should be removed
         */
        private val groupMap: SortedMap<ULong, Int> =
            TreeMap<ULong, Int>().apply {
                var value = 9UL
                for (i in 19 downTo 1) {
                    this[value] = i
                    value = value * 10UL + 9UL
                }
                this[ULong.MAX_VALUE] = 0
            }
    }

    private val hashComparator =
        Comparator<Transaction> { a, b ->
            Arrays.compareUnsigned(a.block.hash.value, b.block.hash.value)
        }

    private val balanceComparator: Comparator<Transaction> =
        comparing {
            it.block.balance
        }

    private val dateDifferenceComparator: Comparator<Transaction> =
        comparing {
            ChronoUnit.MILLIS.between(it.block.timestamp.toJavaInstant(), it.receivedAt).absoluteValue
        }

    private val versionComparator = compareByDescending<Transaction> { it.block.version }

    private val comparator =
        versionComparator
            .thenComparing(dateDifferenceComparator)
            .thenComparing(balanceComparator.reversed())
            .thenComparing(hashComparator)

    private inner class Group {
        private val transactions = TreeSet(comparator)
        private val hashes = HashSet<AttoHash>()
        private val lock = ReentrantLock()

        fun add(
            transaction: Transaction,
            canQueue: () -> Boolean,
        ): Transaction? =
            lock.withLock {
                if (transaction.hash in hashes || !canQueue()) {
                    return null
                }

                transactions.add(transaction)
                hashes.add(transaction.hash)

                if (transactions.size > groupMaxSize) {
                    transactions.pollLast().also { hashes.remove(it.hash) }
                } else {
                    null
                }
            }

        fun poll(claim: (Transaction) -> Boolean): Transaction? =
            lock.withLock {
                while (transactions.isNotEmpty()) {
                    val transaction = transactions.first()
                    val claimed = claim(transaction)
                    transactions.remove(transaction)
                    hashes.remove(transaction.hash)
                    if (claimed) {
                        return transaction
                    }
                }
                return null
            }

        fun size(): Int =
            lock.withLock {
                return transactions.size
            }

        fun clear() =
            lock.withLock {
                transactions.clear()
                hashes.clear()
            }
    }

    private val groups = Array(groupMap.size) { Group() }
    private val currentGroup = AtomicInteger(0)

    private fun getGroup(transaction: Transaction): Int {
        val block = transaction.block
        val raw = block.balance.raw + if (block is AttoSendBlock) block.amount.raw else 0UL
        val group = groupMap.tailMap(raw).firstEntry().value
        return min(group, maxGroup)
    }

    /**
     * [canQueue] runs under the group's lock, atomically with hash deduplication and polling claims.
     * Callbacks must not mutate this queue or publish events.
     *
     * @return deleted transaction if queue was full and eviction occurred
     */
    fun add(
        transaction: Transaction,
        canQueue: () -> Boolean = { true },
    ): Transaction? {
        val groupIndex = getGroup(transaction)
        return groups[groupIndex].add(transaction, canQueue)
    }

    /**
     * [claim] runs before removal under the group's lock. Returning false removes the candidate and
     * continues scanning; throwing leaves it queued. Callbacks must not mutate this queue or publish events.
     */
    fun poll(claim: (Transaction) -> Boolean = { true }): Transaction? {
        val start = currentGroup.getAndUpdate { (it + 1) % groups.size }

        for (i in groups.indices) {
            val index = (start + i) % groups.size
            val transaction = groups[index].poll(claim)

            if (transaction != null) {
                return transaction
            }
        }

        return null
    }

    fun size(): Int = groups.sumOf { it.size() }

    fun clear() {
        groups.forEach { it.clear() }
    }
}
