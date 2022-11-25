package org.atto.node.transaction

import io.swagger.v3.oas.annotations.Operation
import org.atto.commons.*
import org.atto.node.EventPublisher
import org.atto.protocol.AttoNode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Instant
import kotlin.random.Random

@RestController
@RequestMapping
class TransactionController(
    val node: AttoNode,
    val eventPublisher: EventPublisher
) {

    @PostMapping("/transactions")
    @Operation(description = "Publish transaction")
    suspend fun publish(@RequestBody transaction: AttoTransaction): ResponseEntity.BodyBuilder {
        if (!transaction.isValid(node.network)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        }
        eventPublisher.publish(AttoTransactionReceived(transaction))
        return ResponseEntity.ok()
    }

    @GetMapping
    fun get(): AttoTransaction {
        val block = AttoSendBlock(
            version = 0u,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            height = 1u,
            balance = AttoAmount.max,
            timestamp = Instant.now(),
            previous = AttoHash(Random.nextBytes(ByteArray(32))),
            receiverPublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            amount = AttoAmount.max,
        )
        return AttoTransaction(
            block = block,
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            work = AttoWork(Random.nextBytes(ByteArray(8))),
        )
    }

}