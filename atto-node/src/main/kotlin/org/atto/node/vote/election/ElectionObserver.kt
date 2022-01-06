package org.atto.node.vote.election

import org.atto.protocol.transaction.Transaction
import org.atto.protocol.vote.HashVote

interface ElectionObserver {

    /**
     * Triggered when a new vote arrives
     *
     * @param transaction with more voting weight
     */
    suspend fun observed(transaction: Transaction) {
    }

    /**
     * Triggered when a new vote arrives
     *
     * @param transaction with more voting weight
     */
    suspend fun consensed(transaction: Transaction) {
    }

    /**
     * Triggered when transaction reach minimal confirmation threshold with non-final votes
     */
    suspend fun agreed(transaction: Transaction) {
    }

    /**
     * Triggered when transaction reach minimal confirmation threshold with final votes
     */
    suspend fun confirmed(transaction: Transaction, hashVotes: Collection<HashVote>) {
    }

    /**
     * Triggered when transaction is taking to long to confirm
     */
    suspend fun staling(transaction: Transaction) {
    }

    /**
     * Triggered when transaction will take too long to confirm
     */
    suspend fun staled(transaction: Transaction) {
    }
}