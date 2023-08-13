package cash.atto.commons

import java.time.Instant


data class AttoAccount(
    val publicKey: cash.atto.commons.AttoPublicKey,
    var version: UShort,
    var height: ULong,
    var balance: cash.atto.commons.AttoAmount,
    var lastTransactionHash: cash.atto.commons.AttoHash,
    var lastTransactionTimestamp: Instant,
    var representative: cash.atto.commons.AttoPublicKey,
) {

    companion object {
        fun open(
            representative: cash.atto.commons.AttoPublicKey,
            sendBlock: cash.atto.commons.AttoSendBlock
        ): cash.atto.commons.AttoOpenBlock {
            return cash.atto.commons.AttoOpenBlock(
                version = sendBlock.version,
                publicKey = sendBlock.receiverPublicKey,
                balance = sendBlock.amount,
                timestamp = Instant.now(),
                sendHash = sendBlock.hash,
                representative = representative,
            )
        }
    }

    fun send(
        publicKey: cash.atto.commons.AttoPublicKey,
        amount: cash.atto.commons.AttoAmount
    ): cash.atto.commons.AttoSendBlock {
        if (publicKey == this.publicKey) {
            throw IllegalArgumentException("You can't send money to yourself");
        }
        return cash.atto.commons.AttoSendBlock(
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

    fun receive(sendBlock: cash.atto.commons.AttoSendBlock): cash.atto.commons.AttoReceiveBlock {
        return cash.atto.commons.AttoReceiveBlock(
            version = max(version, sendBlock.version),
            publicKey = publicKey,
            height = height + 1U,
            balance = balance.plus(sendBlock.amount),
            timestamp = Instant.now(),
            previous = lastTransactionHash,
            sendHash = sendBlock.hash,
        )
    }

    fun change(representative: cash.atto.commons.AttoPublicKey): cash.atto.commons.AttoChangeBlock {
        return cash.atto.commons.AttoChangeBlock(
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

