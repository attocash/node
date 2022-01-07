package org.atto.node.vote

import org.atto.protocol.vote.HashVote

data class WeightedHashVote(val hashVote: HashVote, val weight: ULong) {
    fun isFinal(): Boolean {
        return hashVote.vote.isFinal()
    }
}