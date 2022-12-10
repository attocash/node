package org.atto.node.wallet

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.atto.commons.*
import org.atto.node.account.AccountRepository
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.transaction.TransactionConfirmed
import org.atto.protocol.AttoNode
import org.atto.protocol.transaction.AttoTransactionPush
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
@Profile("default")
class WalletService(
    private val thisNode: AttoNode,
    private val privateKey: AttoPrivateKey,
    private val accountRepository: AccountRepository,
    private val messagePublisher: NetworkMessagePublisher
) {
    private val logger = KotlinLogging.logger {}

    @EventListener
    fun listen(changeConfirmed: TransactionConfirmed) {
        val transaction = changeConfirmed.payload
        if (transaction.block.type != AttoBlockType.SEND) {
            return
        }

        val sendBlock = transaction.block as AttoSendBlock

        if (sendBlock.receiverPublicKey != thisNode.publicKey) {
            return
        }

        runBlocking {
            launch {
                receive(sendBlock)
            }
        }
    }

    private suspend fun receive(sendBlock: AttoSendBlock) {
        val receiverAccount = accountRepository.findById(sendBlock.receiverPublicKey)?.toAttoAccount()

        val receiveTransaction = if (receiverAccount == null) {
            val openBlock = AttoAccount.open(thisNode.publicKey, thisNode.publicKey, sendBlock)
            AttoTransaction(
                block = openBlock,
                signature = privateKey.sign(openBlock.hash.value),
                work = AttoWork.work(thisNode.network, openBlock.timestamp, openBlock.publicKey)
            )
        } else {
            val receiveBlock = receiverAccount.receive(sendBlock)
            AttoTransaction(
                block = receiveBlock,
                signature = privateKey.sign(receiveBlock.hash.value),
                work = AttoWork.Companion.work(
                    thisNode.network,
                    receiveBlock.timestamp,
                    receiverAccount.lastTransactionHash
                )
            )
        }

        logger.trace { "Created $receiveTransaction" }
        messagePublisher.publish(
            InboundNetworkMessage(
                thisNode.socketAddress,
                AttoTransactionPush(receiveTransaction)
            )
        )
    }
}