package org.atto.node.vote

import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.protocol.vote.HashVote
import java.util.*
import java.util.Comparator.comparing


class HashVoteQueue(private val maxSize: Int) {
    private val weightComparator: Comparator<WeightedHashVote> = comparing { it.weight }

    private val map = HashMap<PublicKeyHash, WeightedHashVote>()
    private val set = TreeSet(weightComparator)

    fun add(weight: ULong, hashVote: HashVote): HashVote? {
        val entry = WeightedHashVote(weight, hashVote)

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
            return map.remove(removedEntry.toPublicKeyHash())?.hashVote
        }

        return null
    }

    fun poll(): HashVote? {
        val entry = set.pollLast()

        if (entry != null) {
            map.remove(entry.toPublicKeyHash())
        }

        return entry?.hashVote
    }

    private data class WeightedHashVote(val weight: ULong, val hashVote: HashVote) {
        fun toPublicKeyHash(): PublicKeyHash {
            return PublicKeyHash(hashVote.vote.publicKey, hashVote.hash)
        }
    }

    private data class PublicKeyHash(val publicKey: AttoPublicKey, val hash: AttoHash)
}