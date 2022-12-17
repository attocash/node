package org.atto.node.transaction

import io.swagger.v3.oas.annotations.Operation
import org.atto.commons.AttoTransaction
import org.atto.node.EventPublisher
import org.atto.protocol.AttoNode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping
class TransactionController(
    val node: AttoNode,
    val eventPublisher: EventPublisher
) {

    @PostMapping("/transactions")
    @Operation(description = "Publish transaction")
    suspend fun publish(@RequestBody attoTransaction: AttoTransaction): ResponseEntity.BodyBuilder {
        if (!attoTransaction.isValid(node.network)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        }
        eventPublisher.publish(TransactionReceived(attoTransaction.toTransaction()))
        return ResponseEntity.ok()
    }
}