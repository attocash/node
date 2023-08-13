package atto.node.transaction

import atto.node.network.BroadcastNetworkMessage
import atto.node.network.BroadcastStrategy
import atto.node.network.InboundNetworkMessage
import atto.node.network.NetworkMessagePublisher
import atto.protocol.transaction.AttoTransactionPush
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoTransaction
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap

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

    private val holderMap = ConcurrentHashMap<AttoHash, TransactionSocketAddressHolder>()
    private val transactionQueue: Deque<TransactionSocketAddressHolder> = LinkedList()

    @EventListener
    @Async
    fun process(message: InboundNetworkMessage<AttoTransactionPush>) {
        val transaction = message.payload.transaction

        holderMap.compute(transaction.hash) { _, v ->
            val holder = v ?: TransactionSocketAddressHolder(transaction)
            holder.add(message.socketAddress)
            holder
        }

        logger.trace { "Started monitoring transaction to rebroadcast. $transaction" }
    }

    @EventListener
    @Async
    fun process(event: TransactionValidated) {
        val transactionHolder = holderMap.remove(event.transaction.hash)!!
        runBlocking(singleDispatcher) {
            transactionQueue.add(transactionHolder)
            logger.trace { "Transaction queued for rebroadcast. ${event.transaction}" }
        }
    }

    @EventListener
    @Async
    fun process(event: TransactionRejected) {
        holderMap.remove(event.transaction.hash)
        logger.trace { "Stopped monitoring transaction because it was rejected due to ${event.reason}. ${event.transaction}" }
    }

    @EventListener
    @Async
    fun process(event: TransactionDropped) {
        holderMap.remove(event.transaction.hash)
        logger.trace { "Stopped monitoring transaction because event was dropped. ${event.transaction}" }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @PostConstruct
    fun start() {
        job = GlobalScope.launch(CoroutineName(this.javaClass.simpleName)) {
            while (isActive) {
                val transactionHolder = withContext(singleDispatcher) {
                    transactionQueue.poll()
                }
                if (transactionHolder != null) {
                    val transaction = transactionHolder.transaction
                    logger.trace { "Transaction dequeued. $transaction" }
                    val transactionPush = AttoTransactionPush(transaction)
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