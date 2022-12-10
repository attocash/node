package org.atto.node.transaction

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.atto.commons.AttoHash
import org.atto.commons.AttoTransaction
import org.atto.node.network.BroadcastNetworkMessage
import org.atto.node.network.BroadcastStrategy
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.network.NetworkMessagePublisher
import org.atto.protocol.transaction.AttoTransactionPush
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.net.InetSocketAddress
import java.util.*

/**
 * This rebroadcaster aims to reduce data usage creating a list of nodes that already saw these transactions while
 * it waits for the internal validations.
 *
 * Once the account change is validated the transaction that triggered this change is added to the buffer and later
 * rebroadcasted.
 *
 */
@Service
class TransactionRebroadcaster(private val messagePublisher: NetworkMessagePublisher) {
    private val logger = KotlinLogging.logger {}

    private lateinit var job: Job

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val holderMap = HashMap<AttoHash, TransactionSocketAddressHolder>()
    private val transactionQueue: Deque<TransactionSocketAddressHolder> = LinkedList()

    @EventListener
    fun observe(message: InboundNetworkMessage<AttoTransactionPush>) = runBlocking(singleDispatcher) {
        val transaction = message.payload.transaction

        holderMap.compute(transaction.hash) { _, v ->
            val holder = v ?: TransactionSocketAddressHolder(transaction)
            holder.add(message.socketAddress)
            holder
        }

        logger.trace { "Started observing $transaction to rebroadcast" }
    }

    @EventListener
    fun process(event: TransactionValidated) = runBlocking(singleDispatcher) {
        val transactionHolder = holderMap.remove(event.payload.hash)!!
        transactionQueue.add(transactionHolder)
        logger.trace { "Stopped observing ${event.payload.hash} and add it to the buffer." }
    }

    @EventListener
    fun process(event: AttoTransactionDropped) = runBlocking(singleDispatcher) {
        holderMap.remove(event.payload.hash)
        logger.trace { "Stopped observing ${event.payload.hash}. Transaction was dropped" }
    }

    @EventListener
    fun process(event: TransactionRejected) = runBlocking(singleDispatcher) {
        holderMap.remove(event.payload.hash)
        logger.trace { "Stopped observing ${event.payload.hash}. Transaction was rejected" }
    }


    @OptIn(DelicateCoroutinesApi::class)
    @PostConstruct
    fun start() {
        job = GlobalScope.launch(CoroutineName("transaction-rebroadcaster")) {
            while (isActive) {
                val transactionHolder = withContext(singleDispatcher) {
                    transactionQueue.poll()
                }
                if (transactionHolder != null) {
                    val transactionPush = AttoTransactionPush(transactionHolder.transaction)
                    val exceptions = transactionHolder.socketAddresses

                    val message = BroadcastNetworkMessage(
                        BroadcastStrategy.EVERYONE,
                        exceptions,
                        transactionPush,
                    )

                    messagePublisher.publish(message)
                } else {
                    delay(100)
                }
            }
        }
    }

    @PreDestroy
    fun stop() {
        job.cancel()
    }


    private class TransactionSocketAddressHolder(val transaction: AttoTransaction) {
        val socketAddresses = HashSet<InetSocketAddress>()

        fun add(socketAddress: InetSocketAddress) {
            socketAddresses.add(socketAddress)
        }
    }

}