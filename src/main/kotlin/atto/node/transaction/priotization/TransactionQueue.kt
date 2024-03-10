package atto.node.transaction.priotization

import atto.node.transaction.Transaction
import cash.atto.commons.AttoSendBlock
import kotlinx.datetime.toJavaInstant
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.Comparator.comparing
import kotlin.math.min

class TransactionQueue(private val groupMaxSize: Int, private val maxGroup: Int = 19) {
    companion object {
        /**
         * Smaller groups are easier to exploit during a spam attack due to low amount invested.
         *
         * To reduce the attack surface the groups below 0.001 USD should be removed
         */
        private val groupMap: SortedMap<ULong, Int> = TreeMap<ULong, Int>().apply {
            var value = 9UL
            for (i in 19 downTo 1) {
                this[value] = i
                value = value * 10UL + 9UL
            }
            this[ULong.MAX_VALUE] = 0
        }
    }

    private val dateComparator: Comparator<Transaction> = comparing {
        ChronoUnit.MILLIS.between(it.receivedAt, it.block.timestamp.toJavaInstant())
    }

    private val versionComparator: Comparator<Transaction> = comparing {
        it.block.version
    }

    private val comparator = versionComparator.thenComparing(dateComparator)

    private var currentGroup = 0
    private val groups = Array<TreeSet<Transaction>>(groupMap.size) { TreeSet(comparator) }
    private var size = 0

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
            size++
        }

        if (transactions.size > groupMaxSize) {
            size--
            return transactions.pollFirst()
        }

        return null
    }

    internal fun poll(): Transaction? {
        val groupInitial = currentGroup
        var groupIndex = currentGroup

        do {
            val transactions = groups[groupIndex]
            val transaction = transactions.pollLast()

            groupIndex = ++groupIndex % groupMap.size

            if (transaction != null) {
                currentGroup = groupIndex
                size--
                return transaction
            }
        } while (groupInitial != groupIndex)

        return null
    }

    fun getSize(): Int {
        return size
    }

    fun clear() {
        groups.forEach { it.clear() }
    }
}