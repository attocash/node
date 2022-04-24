package org.atto.node.transaction.priotization

import org.atto.commons.AttoTransaction
import java.time.Instant
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

    private val dateComparator: Comparator<TimedTransaction> = comparing {
        ChronoUnit.MILLIS.between(it.receivedTimestamp, it.transaction.block.timestamp)
    }

    private val versionComparator: Comparator<TimedTransaction> = comparing {
        it.transaction.block.version
    }

    private val comparator = versionComparator.thenComparing(dateComparator)

    private var currentGroup = 0
    private val groups = Array<TreeSet<TimedTransaction>>(groupMap.size) { TreeSet(comparator) }
    private var size = 0

    internal fun add(timedTransaction: TimedTransaction): TimedTransaction? {
        val group = getGroup(timedTransaction.transaction.block.balance.raw)
        val transactions = groups[group]

        if (transactions.add(timedTransaction)) {
            size++
        }

        if (transactions.size > groupMaxSize) {
            size--
            return transactions.pollFirst()
        }

        return null
    }

    /**
     * @return deleted transaction
     */
    fun add(transaction: AttoTransaction): AttoTransaction? {
        val timedTransaction = TimedTransaction(transaction)
        return add(timedTransaction)?.transaction
    }

    internal fun pollTimed(): TimedTransaction? {
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

    fun poll(): AttoTransaction? {
        return pollTimed()?.transaction
    }

    fun getSize(): Int {
        return size
    }

    internal data class TimedTransaction(
        val transaction: AttoTransaction,
        val receivedTimestamp: Instant = Instant.now()
    )
}