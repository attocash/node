package org.atto.node.transaction.validation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.stereotype.Component

@ExperimentalCoroutinesApi
@Component
class TransactionValidationInfoContributor(val validator: TransactionValidator) : InfoContributor {

    override fun contribute(builder: Info.Builder) {
        val election = mapOf(
            "queue-size" to validator.getQueueSize(),
            "previous-buffer" to runBlocking { validator.getPreviousBuffer() },
            "link-buffer" to runBlocking { validator.getLinkBuffer() }
        )
        builder.withDetail("transaction-validator", election)
    }

}