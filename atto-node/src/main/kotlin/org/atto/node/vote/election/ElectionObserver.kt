package org.atto.node.vote.election

import org.atto.node.account.Account
import org.atto.node.transaction.Transaction
import org.atto.node.vote.Vote

interface ElectionObserver {

    /**
     * Triggered when a new vote arrives
     *
     * @param transaction with more voting weight
     */
    suspend fun observed(account: Account, transaction: Transaction) {
    }

    /**
     * Triggered when a new vote arrives
     *
     * @param transaction with more voting weight
     */
    suspend fun consensed(account: Account, transaction: Transaction) {
    }

    /**
     * Triggered when transaction reach minimal confirmation threshold with non-final votes
     */
    suspend fun agreed(account: Account, transaction: Transaction) {
    }

    /**
     * Triggered when transaction reach minimal confirmation threshold with final votes
     */
    suspend fun confirmed(account: Account, transaction: Transaction, votes: Collection<Vote>) {
    }

    /**
     * Triggered when transaction is taking to long to confirm
     */
    suspend fun staling(account: Account, transaction: Transaction) {
    }

    /**
     * Triggered when transaction will take too long to confirm
     */
    suspend fun staled(account: Account, transaction: Transaction) {
    }
}