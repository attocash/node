package cash.atto.node.vote.prioritization

import cash.atto.node.transaction.Transaction
import cash.atto.node.vote.PublicKeyHash
import cash.atto.node.vote.Vote
import java.util.Arrays
import java.util.Comparator.comparing
import java.util.TreeSet

class VoteQueue(
    private val maxSize: Int,
) {
    private val hashComparator =
        Comparator<TransactionVote> { a, b ->
            Arrays.compareUnsigned(a.vote.hash.value, b.vote.hash.value)
        }
    private val weightComparator: Comparator<TransactionVote> = comparing { it.vote.weight.raw }

    private val map = HashMap<PublicKeyHash, TransactionVote>()
    private val set = TreeSet(weightComparator.thenComparing(hashComparator))

    fun add(entry: TransactionVote): TransactionVote? {
        val vote = entry.vote
        val publicKeyHash = vote.toPublicKeyHash()

        val oldEntry = map.remove(publicKeyHash)
        val oldVote = oldEntry?.vote
        if (oldVote != null && oldVote.timestamp > vote.timestamp) {
            map[publicKeyHash] = oldEntry
            return entry
        }

        if (oldEntry != null) {
            set.remove(oldEntry)
        }

        map[publicKeyHash] = entry

        set.add(entry)

        if (oldEntry == null && set.size > maxSize) {
            val removedEntry = set.pollFirst()!!
            return map.remove(removedEntry.vote.toPublicKeyHash())
        }

        return null
    }

    fun poll(): TransactionVote? {
        val entry = set.pollLast()

        if (entry != null) {
            map.remove(entry.vote.toPublicKeyHash())
        }

        return entry
    }

    fun getSize(): Int = set.size

    fun clear() {
        map.clear()
        set.clear()
    }

    public data class TransactionVote(
        val transaction: Transaction,
        val vote: Vote,
    )
}
