package org.atto.node.transaction

import org.atto.protocol.transaction.Transaction
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.Comparator.comparing

class TransactionQueue(private val groupMaxSize: Int) {
    companion object {
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
        ChronoUnit.MILLIS.between(it.receivedTimestamp, it.block.timestamp)
    }

    private var currentGroup = 0
    private val groups = Array<TreeSet<Transaction>>(groupMap.size) { TreeSet(dateComparator) }
    private var size = 0

    /**
     * Return deleted transaction
     */
    fun add(transaction: Transaction): Transaction? {
        val group = getGroup(transaction.block.amount.raw)
        val transactions = groups[group]
        transactions.add(transaction)
        size++
        if (transactions.size > groupMaxSize) {
            size--
            return transactions.pollFirst()
        }
        return null
    }

    fun poll(): Transaction? {
        val groupAnchor = currentGroup
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
        } while (groupAnchor != groupIndex)

        return null
    }

    fun size(): Int {
        return size
    }
}