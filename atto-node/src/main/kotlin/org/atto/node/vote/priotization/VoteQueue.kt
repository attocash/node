package org.atto.node.vote.priotization

import org.atto.node.vote.PublicKeyHash
import org.atto.node.vote.Vote
import java.util.*
import java.util.Comparator.comparing


class VoteQueue(private val maxSize: Int) {
    private val weightComparator: Comparator<Vote> = comparing { it.weight.raw }

    private val map = HashMap<PublicKeyHash, Vote>()
    private val set = TreeSet(weightComparator)
    private var size = 0

    fun add(entry: Vote): Vote? {
        val publicKeyHash = entry.toPublicKeyHash()

        val oldEntry = map.remove(publicKeyHash)
        if (oldEntry != null && oldEntry.timestamp > entry.timestamp) {
            map[publicKeyHash] = oldEntry
            return entry
        }

        if (oldEntry != null) {
            set.remove(oldEntry)
        }

        map[publicKeyHash] = entry

        if (set.add(entry)) {
            size++
        }

        if (oldEntry == null && set.size > maxSize) {
            size--
            val removedEntry = set.pollFirst()!!
            return map.remove(removedEntry.toPublicKeyHash())
        }

        return null
    }

    fun poll(): Vote? {
        val entry = set.pollLast()

        if (entry != null) {
            size--
            map.remove(entry.toPublicKeyHash())
        }

        return entry
    }

    fun getSize(): Int {
        return size
    }
}