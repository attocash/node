//package org.atto.node.bootstrap
//
//import org.atto.node.transaction.AttoTransactionEvent
//import org.atto.node.transaction.TransactionRejectionReason
//import org.atto.protocol.transaction.Transaction
//import org.atto.protocol.transaction.TransactionStatus
//
///**
// * See TransactionStatus.VOTED
// */
//class AttoTransactionVoted(
//    val transaction: Transaction,
//    val rejectionReason: TransactionRejectionReason?
//) : AttoTransactionEvent(TransactionStatus.VOTED, transaction)