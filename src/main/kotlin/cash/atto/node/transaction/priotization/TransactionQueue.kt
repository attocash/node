package cash.atto.node.transaction.priotization

import cash.atto.commons.AttoSendBlock
import cash.atto.node.transaction.Transaction
import kotlinx.datetime.toJavaInstant
import java.time.temporal.ChronoUnit
import java.util.Arrays
import java.util.Comparator.comparing
import java.util.SortedMap
import java.util.TreeMap
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicInteger
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

    private val hashComparator = Comparator<Transaction> { a, b ->
        Arrays.compareUnsigned(a.block.hash.value, b.block.hash.value)
    }

    private val dateComparator: Comparator<Transaction> =
        comparing {
            ChronoUnit.MILLIS.between(it.receivedAt, it.block.timestamp.toJavaInstant())
        }

    private val versionComparator = compareByDescending<Transaction> { it.block.version }

    private val comparator = versionComparator
        .thenComparing(dateComparator)
        .thenComparing(hashComparator)

    private val groups = Array<TreeSet<Transaction>>(groupMap.size) { TreeSet(comparator) }
    private val currentGroup = AtomicInteger(0)
    private val size = AtomicInteger(0)

    private fun getGroup(transaction: Transaction): Int {
        val block = transaction.block
        val raw = block.balance.raw + if (block is AttoSendBlock) block.amount.raw else 0UL
        val group = groupMap.tailMap(raw).firstEntry().value
        return min(group, maxGroup)
    }

    /**
     * @return deleted transaction
     */
    fun add(transaction: Transaction): Transaction? {
        val group = getGroup(transaction)
        val transactions = groups[group]

        if (transactions.add(transaction)) {
            size.addAndGet(1)
        }

        if (transactions.size > groupMaxSize) {
            size.addAndGet(-1)
            return transactions.pollFirst()
        }

        return null
    }

    internal fun poll(): Transaction? {
        val groupInitial = currentGroup.get()
        var groupIndex = currentGroup.get()

        do {
            val transactions = groups[groupIndex]
            val transaction = transactions.pollLast()

            groupIndex = ++groupIndex % groupMap.size

            if (transaction != null) {
                currentGroup.set(groupIndex)
                size.addAndGet(-1)
                return transaction
            }
        } while (groupInitial != groupIndex)

        return null
    }

    fun getSize(): Int = size.get()

    fun clear() {
        groups.forEach { it.clear() }
    }
}
