package org.atto.node.transaction

import io.swagger.annotations.ApiOperation
import org.atto.commons.*
import org.atto.node.transaction.validation.TransactionValidator
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.transaction.TransactionStatus
import org.springframework.web.bind.annotation.*
import java.time.Instant
import kotlin.random.Random

@RestController
@RequestMapping("/transactions")
class TransactionController(val transactionValidator: TransactionValidator) {

    @PostMapping
    @ApiOperation("Publish transaction")
    suspend fun publish(@RequestBody transaction: Transaction) {
        transactionValidator.process(transaction)
    }

    @GetMapping
    fun get(): Transaction {
        val block = AttoBlock(
            type = AttoBlockType.SEND,
            version = 0u,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            height = 1u,
            previous = AttoHash(Random.nextBytes(ByteArray(32))),
            representative = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            link = AttoLink.from(AttoHash(ByteArray(32))),
            balance = AttoAmount.max,
            amount = AttoAmount.max,
            timestamp = Instant.now()
        )
        return Transaction(
            block = block,
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            work = AttoWork(Random.nextBytes(ByteArray(8))),
            hash = block.getHash(),
            status = TransactionStatus.RECEIVED,
            receivedTimestamp = Instant.now()
        )
    }

}