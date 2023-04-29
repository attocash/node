package atto.commons

import java.time.Instant


data class AttoAccount(
    val publicKey: atto.commons.AttoPublicKey,
    var version: UShort,
    var height: ULong,
    var balance: atto.commons.AttoAmount,
    var lastTransactionHash: atto.commons.AttoHash,
    var lastTransactionTimestamp: Instant,
    var representative: atto.commons.AttoPublicKey,
) {

    companion object {
        fun open(representative: atto.commons.AttoPublicKey, sendBlock: atto.commons.AttoSendBlock): atto.commons.AttoOpenBlock {
            return atto.commons.AttoOpenBlock(
                version = sendBlock.version,
                publicKey = sendBlock.receiverPublicKey,
                balance = sendBlock.amount,
                timestamp = Instant.now(),
                sendHash = sendBlock.hash,
                representative = representative,
            )
        }
    }

    fun send(publicKey: atto.commons.AttoPublicKey, amount: atto.commons.AttoAmount): atto.commons.AttoSendBlock {
        if (publicKey == this.publicKey) {
            throw IllegalArgumentException("You can't send money to yourself");
        }
        return atto.commons.AttoSendBlock(
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

    fun receive(sendBlock: atto.commons.AttoSendBlock): atto.commons.AttoReceiveBlock {
        return atto.commons.AttoReceiveBlock(
            version = max(version, sendBlock.version),
            publicKey = publicKey,
            height = height + 1U,
            balance = balance.plus(sendBlock.amount),
            timestamp = Instant.now(),
            previous = lastTransactionHash,
            sendHash = sendBlock.hash,
        )
    }

    fun change(representative: atto.commons.AttoPublicKey): atto.commons.AttoChangeBlock {
        return atto.commons.AttoChangeBlock(
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

