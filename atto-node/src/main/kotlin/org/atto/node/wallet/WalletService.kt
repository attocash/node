package org.atto.node.wallet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.atto.commons.*
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.transaction.TransactionConfirmed
import org.atto.node.transaction.TransactionRepository
import org.atto.protocol.Node
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.transaction.TransactionPush
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
@Profile("default")
class WalletService(
    private val thisNode: Node,
    private val privateKey: AttoPrivateKey,
    private val transactionRepository: TransactionRepository,
    private val messagePublisher: NetworkMessagePublisher
) {
    private val logger = KotlinLogging.logger {}

    private val scope = CoroutineScope(Dispatchers.Default)

    @EventListener
    fun listen(transactionConfirmed: TransactionConfirmed) {
        val transaction = transactionConfirmed.transaction
        if (transaction.block.type != AttoBlockType.SEND) {
            return
        }

        val sendBlock = transaction.block

        if (sendBlock.link.publicKey != thisNode.publicKey) {
            return
        }

        scope.launch {
            receive(sendBlock)
        }
    }

    private suspend fun receive(sendBlock: AttoBlock) {
        val latestTransaction = transactionRepository.findLastByPublicKeyId(thisNode.publicKey)

        val receiveTransaction = if (latestTransaction == null) {
            val openBlock = AttoBlock.open(thisNode.publicKey, thisNode.publicKey, sendBlock)
            Transaction(
                block = openBlock,
                signature = privateKey.sign(openBlock.getHash().value),
                work = AttoWork.Companion.work(openBlock.publicKey, thisNode.network)
            )
        } else {
            val receiveBlock = latestTransaction.block.receive(sendBlock)
            Transaction(
                block = receiveBlock,
                signature = privateKey.sign(receiveBlock.getHash().value),
                work = AttoWork.Companion.work(latestTransaction.hash, thisNode.network)
            )
        }

        logger.trace { "Created $receiveTransaction" }
        messagePublisher.publish(
            InboundNetworkMessage(
                thisNode.socketAddress,
                this,
                TransactionPush(receiveTransaction)
            )
        )
    }
}