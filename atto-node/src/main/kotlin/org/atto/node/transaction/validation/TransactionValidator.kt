package org.atto.node.transaction.validation

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.atto.commons.AttoBlockType
import org.atto.commons.AttoHash
import org.atto.node.CacheSupport
import org.atto.node.EventPublisher
import org.atto.node.network.BroadcastNetworkMessage
import org.atto.node.network.BroadcastStrategy
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.transaction.*
import org.atto.protocol.Node
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.transaction.TransactionPush
import org.atto.protocol.transaction.TransactionStatus
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

@Service
class TransactionValidator(
    properties: TransactionValidatorProperties,
    private val scope: CoroutineScope,
    private val thisNode: Node,
    private val eventPublisher: EventPublisher,
    private val messagePublisher: NetworkMessagePublisher,
    private val transactionRepository: TransactionRepository
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private lateinit var job: Job

    // SEND also has links but we can't validate it
    private val linkSupport = setOf(AttoBlockType.OPEN, AttoBlockType.RECEIVE)
    private val previousSupport = setOf(AttoBlockType.RECEIVE, AttoBlockType.SEND, AttoBlockType.CHANGE)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val queue = TransactionQueue(properties.groupMaxSize!!)

    private val activeElections = HashSet<AttoHash>()
    private val previousBuffer = HashMap<AttoHash, MutableList<Transaction>>()
    private val linkBuffer = HashMap<AttoHash, MutableList<Transaction>>()

    private val socketAddresses: Cache<AttoHash, InetSocketAddress> = Caffeine.newBuilder()
        .expireAfterAccess(properties.cacheExpirationTimeInSeconds!!, TimeUnit.SECONDS)
        .maximumSize(properties.cacheMaxSize!!.toLong())
        .build()

    @EventListener
    fun process(event: TransactionStaled) {
        scope.launch {
            remove(event.transaction.hash)
        }
    }

    @EventListener
    fun process(event: TransactionConfirmed) {
        scope.launch {
            val hash = event.transaction.hash
            withContext(singleDispatcher) {
                val bufferedTransactions =
                    previousBuffer.getOrDefault(hash, arrayListOf()) + linkBuffer.getOrDefault(hash, arrayListOf())
                remove(event.transaction.hash)
                bufferedTransactions.forEach {
                    logger.trace { "Unbuffered $it" }
                    add(it)
                }
            }
        }
    }

    private suspend fun remove(hash: AttoHash) = withContext(singleDispatcher) {
        activeElections.remove(hash)
        previousBuffer.remove(hash)
        linkBuffer.remove(hash)
    }

    @EventListener
    fun add(message: InboundNetworkMessage<TransactionPush>) {
        val transaction = message.payload.transaction

        val previousSocketAddress = socketAddresses.asMap().putIfAbsent(transaction.hash, message.socketAddress)
        if (previousSocketAddress != null) {
            logger.trace { "Ignored duplicated $transaction" }
            return
        }

        scope.launch {
            add(transaction)
        }
    }

    suspend fun add(transaction: Transaction) = withContext(singleDispatcher) {
        val block = transaction.block
        if (previousSupport.contains(block.type) && activeElections.contains(block.previous)) {
            previousBuffer.compute(block.previous) { _, v ->
                val list = v ?: ArrayList()
                list.add(transaction)
                list
            }
            logger.trace { "Buffered $transaction" }
        } else if (linkSupport.contains(block.type) && activeElections.contains(block.link.hash)) {
            linkBuffer.compute(block.link.hash!!) { _, v ->
                val list = v ?: ArrayList()
                list.add(transaction)
                list
            }
            logger.trace { "Buffered ${transaction.hash}" }
        } else {
            queue.add(transaction)
            logger.trace { "Queued $transaction" }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @PostConstruct
    fun start() {
        job = GlobalScope.launch(CoroutineName("transaction-validator")) {
            while (isActive) {
                val transaction = withContext(singleDispatcher) {
                    queue.poll()
                }
                if (transaction != null) {
                    scope.launch {
                        process(transaction)
                    }
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

    internal suspend fun process(transaction: Transaction) {
        val rejectionReason = validate(transaction)
        val socketAddress = socketAddresses.getIfPresent(transaction.hash)


        if (rejectionReason != null) {
            val event = TransactionRejected(socketAddress, rejectionReason, transaction)
            sendEvent(event)
        } else {
            withContext(singleDispatcher) {
                activeElections.add(transaction.hash)
            }
            val event = TransactionValidated(transaction.copy(status = TransactionStatus.VALIDATED))
            sendEvent(event)
            broadcast(transaction)
        }
    }

    private fun sendEvent(event: TransactionEvent) {
        logger.trace { "$event" }
        eventPublisher.publish(event)
    }

    private suspend fun validate(transaction: Transaction): TransactionRejectionReasons? {
        if (!transaction.isValid(thisNode.network)) {
            return TransactionRejectionReasons.INVALID_TRANSACTION
        }

        val block = transaction.block

        if (linkSupport.contains(block.type)) {
            val linkTransaction = transactionRepository.findById(block.link.hash!!)

            if (linkTransaction == null) {
                return TransactionRejectionReasons.LINK_NOT_FOUND
            }

            if (linkTransaction.status != TransactionStatus.CONFIRMED) {
                return TransactionRejectionReasons.LINK_NOT_CONFIRMED
            }

            if (linkTransaction.block.type != AttoBlockType.SEND) {
                return TransactionRejectionReasons.INVALID_LINK
            }

            if (linkTransaction.block.amount != block.amount) {
                return TransactionRejectionReasons.INVALID_AMOUNT
            }

            if (linkTransaction.block.timestamp >= block.timestamp) {
                return TransactionRejectionReasons.INVALID_TIMESTAMP
            }

            if (linkTransaction.block.link.publicKey != transaction.block.publicKey) {
                return TransactionRejectionReasons.INVALID_LINK
            }

            if (linkTransaction.block.version > transaction.block.version) {
                return TransactionRejectionReasons.INVALID_VERSION
            }
        }

        if (previousSupport.contains(block.type)) {
            val latestTransaction = transactionRepository.findLastByPublicKeyId(transaction.block.publicKey)

            if (latestTransaction == null) {
                return TransactionRejectionReasons.ACCOUNT_NOT_FOUND
            }

            if (latestTransaction.block.height >= block.height) {
                return TransactionRejectionReasons.OLD_TRANSACTION
            }

            if (latestTransaction.block.height + 1u < block.height) {
                return TransactionRejectionReasons.PREVIOUS_NOT_FOUND
            }

            if (latestTransaction.hash != transaction.block.previous) {
                return TransactionRejectionReasons.INVALID_PREVIOUS
            }

            if (latestTransaction.status != TransactionStatus.CONFIRMED) {
                return TransactionRejectionReasons.PREVIOUS_NOT_CONFIRMED
            }

            if (latestTransaction.block.timestamp >= block.timestamp) {
                return TransactionRejectionReasons.INVALID_TIMESTAMP
            }

            if (latestTransaction.block.version > transaction.block.version) {
                return TransactionRejectionReasons.INVALID_VERSION
            }

            if (block.type == AttoBlockType.CHANGE && latestTransaction.block.representative.value.contentEquals(block.representative.value)) {
                return TransactionRejectionReasons.INVALID_CHANGE
            }

            val latestBalance = latestTransaction.block.balance

            if (block.type != AttoBlockType.SEND && latestBalance + block.amount != block.balance) {
                return TransactionRejectionReasons.INVALID_BALANCE
            }

            if (block.type == AttoBlockType.SEND) {
                if (latestBalance - block.amount != block.balance || block.amount > latestBalance) {
                    return TransactionRejectionReasons.INVALID_BALANCE
                }
            }
        }

        return null
    }

    private fun broadcast(transaction: Transaction) {
        val socketAddress = socketAddresses.getIfPresent(transaction.hash)
        val broadcastStrategy = if (socketAddress == null) {
            BroadcastStrategy.EVERYONE
        } else {
            BroadcastStrategy.MAJORITY
        }
        val exceptions = if (socketAddress != null) setOf(socketAddress) else setOf()
        val transactionPush = TransactionPush(transaction)
        val broadcast = BroadcastNetworkMessage(broadcastStrategy, exceptions, this, transactionPush)
        messagePublisher.publish(broadcast)
    }

    internal fun getQueueSize(): Int {
        return queue.size()
    }


    internal suspend fun getPreviousBuffer(): Map<AttoHash, List<Transaction>> = withContext(singleDispatcher) {
        previousBuffer.entries.associate { it.key to it.value.toList() }
    }

    internal suspend fun getLinkBuffer(): Map<AttoHash, List<Transaction>> = withContext(singleDispatcher) {
        linkBuffer.entries.associate { it.key to it.value.toList() }
    }

    override fun clear() {
        socketAddresses.invalidateAll()
    }
}