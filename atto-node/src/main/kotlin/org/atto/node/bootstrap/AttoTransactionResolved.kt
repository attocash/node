//package org.atto.node.bootstrap
//
//import org.atto.node.transaction.AttoTransactionEvent
//import org.atto.protocol.transaction.Transaction
//import org.atto.protocol.transaction.TransactionStatus
//
///**
// * Sent when a transaction with VOTED status is confirmed
// */
//class AttoTransactionResolved(
//    val transaction: Transaction,
//) : AttoTransactionEvent(TransactionStatus.CONFIRMED, transaction)