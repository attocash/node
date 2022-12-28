package org.atto.commons

import java.time.Instant


data class AttoAccount(
    val publicKey: AttoPublicKey,
    var version: UShort,
    var height: ULong,
    var balance: AttoAmount,
    var lastTransactionHash: AttoHash,
    var lastTransactionTimestamp: Instant,
    var representative: AttoPublicKey,
) {

    companion object {
        fun open(representative: AttoPublicKey, sendBlock: AttoSendBlock): AttoOpenBlock {
            return AttoOpenBlock(
                version = sendBlock.version,
                publicKey = sendBlock.receiverPublicKey,
                balance = sendBlock.amount,
                timestamp = Instant.now(),
                sendHash = sendBlock.hash,
                representative = representative,
            )
        }
    }

    fun send(publicKey: AttoPublicKey, amount: AttoAmount): AttoSendBlock {
        if (publicKey == this.publicKey) {
            throw IllegalArgumentException("You can't send money to yourself");
        }
        return AttoSendBlock(
            version = version,
            publicKey = this.publicKey,
            height = height + 1U,
            balance = balance.minus(amount),
            timestamp = Instant.now(),
            previous = lastTransactionHash,
            receiverPublicKey = publicKey,
            amount = amount,
        )
    }

    fun receive(sendBlock: AttoSendBlock): AttoReceiveBlock {
        return AttoReceiveBlock(
            version = max(version, sendBlock.version),
            publicKey = publicKey,
            height = height + 1U,
            balance = balance.plus(sendBlock.amount),
            timestamp = Instant.now(),
            previous = lastTransactionHash,
            sendHash = sendBlock.hash,
        )
    }

    fun change(representative: AttoPublicKey): AttoChangeBlock {
        return AttoChangeBlock(
            version = version,
            publicKey = publicKey,
            height = height + 1U,
            balance = balance,
            timestamp = Instant.now(),
            previous = lastTransactionHash,
            representative = representative,
        )
    }

    private fun max(n1: UShort, n2: UShort): UShort {
        if (n1 > n2) {
            return n1
        }
        return n2
    }
}

