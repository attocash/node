package org.atto.node.transaction.priotization

import org.atto.node.transaction.Transaction
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.Comparator.comparing

class TransactionQueue(private val groupMaxSize: Int) {
    companion object {
        /**
         * Smaller groups are easier to exploit during a spam attack due to low amount invested.
         *
         * To reduce the attack surface the groups below 0.001 USD should be removed
         */
        private val groupMap = TreeMap(
            mapOf(
                9UL to 0,
                99UL to 1,
                999UL to 2,
                9_999UL to 3,
                99_999UL to 4,
                999_999UL to 5,
                9_999_999UL to 6,
                99_999_999UL to 7,
                999_999_999UL to 8,
                9_999_999_999UL to 9,
                99_999_999_999UL to 10,
                999_999_999_999UL to 11,
                9_999_999_999_999UL to 12,
                99_999_999_999_999UL to 13,
                999_999_999_999_999UL to 14,
                9_999_999_999_999_999UL to 15,
                99_999_999_999_999_999UL to 16,
                999_999_999_999_999_999UL to 17,
                9_999_999_999_999_999_999UL to 18,
                ULong.MAX_VALUE to 19
            )
        )

        fun getGroup(value: ULong): Int {
            return groupMap.ceilingEntry(value).value
        }
    }

    private val dateComparator: Comparator<Transaction> = comparing {
        ChronoUnit.MILLIS.between(it.receivedAt, it.block.timestamp)
    }

    private val versionComparator: Comparator<Transaction> = comparing {
        it.block.version
    }

    private val comparator = versionComparator.thenComparing(dateComparator)

    private var currentGroup = 0
    private val groups = Array<TreeSet<Transaction>>(groupMap.size) { TreeSet(comparator) }
    private var size = 0

    /**
     * @return deleted transaction
     */
    fun add(transaction: Transaction): Transaction? {
        val group = getGroup(transaction.block.balance.raw)
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
        val groupAnchor = currentGroup
        var groupIndex = currentGroup

        do {
            val transactions = groups[groupIndex]
            val timedTransaction = transactions.pollLast()

            groupIndex = ++groupIndex % groupMap.size

            if (timedTransaction != null) {
                currentGroup = groupIndex
                size--
                return timedTransaction
            }
        } while (groupAnchor != groupIndex)

        return null
    }

    fun getSize(): Int {
        return size
    }

    fun clear() {
        groups.forEach { it.clear() }
    }
}