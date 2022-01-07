package org.atto.node.vote

import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import java.util.*
import java.util.Comparator.comparing


class HashVoteQueue(private val maxSize: Int) {
    private val weightComparator: Comparator<WeightedHashVote> = comparing { it.weight }

    private val map = HashMap<PublicKeyHash, WeightedHashVote>()
    private val set = TreeSet(weightComparator)

    fun add(entry: WeightedHashVote): WeightedHashVote? {
        val publicKeyHash = entry.toPublicKeyHash()
        val oldEntry = map.remove(publicKeyHash)

        if (oldEntry != null && oldEntry.hashVote.vote.timestamp > entry.hashVote.vote.timestamp) {
            map[publicKeyHash] = oldEntry
            return null
        }

        if (oldEntry != null) {
            set.remove(oldEntry)
        }

        map[publicKeyHash] = entry
        set.add(entry)

        if (oldEntry == null && set.size > maxSize) {
            val removedEntry = set.pollFirst()!!
            return map.remove(removedEntry.toPublicKeyHash())
        }

        return null
    }

    fun poll(): WeightedHashVote? {
        val entry = set.pollLast()

        if (entry != null) {
            map.remove(entry.toPublicKeyHash())
        }

        return entry
    }

    private fun WeightedHashVote.toPublicKeyHash(): PublicKeyHash {
        return PublicKeyHash(this.hashVote.vote.publicKey, this.hashVote.hash)
    }

    private data class PublicKeyHash(val publicKey: AttoPublicKey, val hash: AttoHash)
}