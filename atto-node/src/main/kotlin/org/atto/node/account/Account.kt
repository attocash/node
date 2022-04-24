package org.atto.node.account

import org.atto.commons.AttoAccount
import org.atto.commons.AttoAmount
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.springframework.data.annotation.Id
import java.time.Instant

data class Account(
    @Id
    val publicKey: AttoPublicKey,
    var version: UShort,
    var height: ULong,
    var balance: AttoAmount,
    var lastHash: AttoHash,
    var lastTimestamp: Instant,
    var representative: AttoPublicKey,
) {
    fun toAttoAccount(): AttoAccount {
        return AttoAccount(
            publicKey = publicKey,
            version = version,
            height = height,
            balance = balance,
            lastHash = lastHash,
            lastTimestamp = lastTimestamp,
            representative = representative,
        )
    }
}