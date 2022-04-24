//package org.atto.node.bootstrap
//
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.launch
//import mu.KotlinLogging
//import org.atto.commons.AttoBlockType
//import org.atto.node.EventPublisher
//import org.atto.node.transaction.TransactionRepository
//import org.atto.node.transaction.TransactionService
//import org.atto.node.account.change.validation.AccountChangeValidator
//import org.atto.protocol.transaction.Transaction
//import org.atto.protocol.transaction.TransactionStatus
//import org.springframework.context.event.EventListener
//import org.springframework.stereotype.Service
//
//@Service
//class BootstrapService(
//    private val scope: CoroutineScope,
//    private val transactionService: TransactionService,
//    private val accountChangeValidator: AccountChangeValidator,
//    private val transactionRepository: TransactionRepository,
//    private val eventPublisher: EventPublisher,
//) {
//    private val logger = KotlinLogging.logger {}
//
//
//    @EventListener
//    fun process(event: AttoTransactionVoted) {
//        scope.launch {
//            val transaction = event.transaction
//            resolve(transaction, false)
//        }
//    }
//
//    @EventListener
//    fun process(event: AttoTransactionResolved) {
//        scope.launch {
//            val transaction = event.transaction
//            transactionService.save(transaction)
//            resolveLinked(transaction)
//            resolveNext(transaction)
//        }
//    }
//
//    private suspend fun resolveLinked(transaction: Transaction) {
//        if (transaction.block.type != AttoBlockType.SEND) {
//            return
//        }
//
//        val receiveTransactions = transactionRepository.findByStatusAndHashLink(
//            TransactionStatus.VOTED,
//            transaction.hash
//        )
//
//        if (receiveTransactions.isEmpty()) {
//            return
//        }
//
//        if (receiveTransactions.size != 1) {
//            logger.error { "For some reason there are more than one transaction voted for the link ${transaction.hash}" }
//            return
//        }
//
//        resolve(receiveTransactions[0])
//    }
//
//    private suspend fun resolveNext(transaction: Transaction) {
//        val nextTransactions = transactionRepository.findByStatusAndPrevious(
//            TransactionStatus.VOTED,
//            transaction.hash
//        )
//
//        if (nextTransactions.isEmpty()) {
//            return
//        }
//
//        if (nextTransactions.size != 1) {
//            logger.error { "For some reason there are more than one transaction voted after ${transaction.hash}" }
//            return
//        }
//
//        resolve(nextTransactions[0])
//    }
//
//    private suspend fun resolve(transaction: Transaction, sendVotedEvent: Boolean = true) {
//        val rejectionReason = accountChangeValidator.validate(transaction)
//
//        if (rejectionReason == null) {
//            val confirmedTransaction = transaction.copy(status = TransactionStatus.CONFIRMED)
//            transactionService.save(confirmedTransaction)
//            eventPublisher.publish(AttoTransactionResolved(confirmedTransaction))
//            return
//        }
//
//        if (sendVotedEvent) {
//            eventPublisher.publish(AttoTransactionVoted(transaction, rejectionReason))
//        }
//    }
//}