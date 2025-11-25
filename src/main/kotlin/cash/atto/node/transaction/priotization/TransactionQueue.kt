package cash.atto.node.transaction.priotization

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
        val lock = ReentrantLock()

        fun add(transaction: Transaction): Transaction? =
            lock.withLock {
                transactions.add(transaction)

                if (transactions.size > groupMaxSize) {
                    transactions.pollLast()
                } else {
                    null
                }
            }

        fun poll(): Transaction? =
            lock.withLock {
                return transactions.pollFirst()
            }

        fun size(): Int =
            lock.withLock {
                return transactions.size
            }

        fun clear() =
            lock.withLock {
                transactions.clear()
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
     * @return deleted transaction if queue was full and eviction occurred
     */
    fun add(transaction: Transaction): Transaction? {
        val groupIndex = getGroup(transaction)
        return groups[groupIndex].add(transaction)
    }

    fun poll(): Transaction? {
        val start = currentGroup.getAndUpdate { (it + 1) % groups.size }

        for (i in groups.indices) {
            val index = (start + i) % groups.size
            val transaction = groups[index].poll()

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
