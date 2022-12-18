package org.atto.node.account

import io.swagger.v3.oas.annotations.Operation
import org.atto.commons.AttoAccount
import org.atto.commons.AttoAmount
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.node.EventPublisher
import org.atto.node.network.NetworkMessagePublisher
import org.atto.protocol.AttoNode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigInteger
import java.time.Instant
import java.util.*

@RestController
@RequestMapping("/accounts")
class AccountController(
    val node: AttoNode,
    val eventPublisher: EventPublisher,
    val messagePublisher: NetworkMessagePublisher,
    val repository: AccountRepository
) {

    @GetMapping("/{publicKey}")
    @Operation(description = "Get account")
    suspend fun get(@PathVariable publicKey: AttoPublicKey): ResponseEntity<Account> {
        val transaction = repository.findById(publicKey)
        return ResponseEntity.of(Optional.ofNullable(transaction))
    }
}

/**
 * The DTO's are required due to https://github.com/FasterXML/jackson-module-kotlin/issues/199
 */
data class AccountDTO(
    val publicKey: String,
    var version: Short,
    var height: BigInteger,
    var balance: BigInteger,
    var lastTransactionHash: String,
    var lastTransactionTimestamp: Instant,
    var representative: String,
) {
    fun toAttoAccount(): AttoAccount {
        return AttoAccount(
            publicKey = AttoPublicKey.parse(publicKey),
            version = version.toUShort(),
            height = height.toLong().toULong(),
            balance = AttoAmount(balance.toLong().toULong()),
            lastTransactionHash = AttoHash.parse(lastTransactionHash),
            lastTransactionTimestamp = lastTransactionTimestamp,
            representative = AttoPublicKey.parse(representative)
        )
    }
}