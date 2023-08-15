//package atto.node.transaction
//
//import io.mockk.mockk
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import cash.atto.commons.AttoHash
//import cash.atto.commons.AttoPublicKey
//import atto.protocol.transaction.Transaction
//import java.util.concurrent.ConcurrentHashMap
//
//
//class MockTransactionRepository(properties: TransactionProperties) :
//    TransactionRepository(properties, CoroutineScope(Dispatchers.Default), mockk()) {
//    private val transactions = ConcurrentHashMap<AttoHash, Transaction>()
//
//    override suspend fun save(entity: Transaction): Transaction {
//        transactions[entity.hash] = entity
//        return entity
//    }
//
//    override suspend fun findById(id: AttoHash): Transaction? {
//        return transactions[id]
//    }
//
//    override suspend fun findLastConfirmedByPublicKeyId(publicKey: AttoPublicKey): Transaction? {
//        return transactions.values
//            .filter { it.block.publicKey == publicKey }
//            .maxByOrNull { it.block.height }
//    }
//
//    override suspend fun findConfirmedByHashLink(link: AttoHash): Transaction? {
//        return transactions.values.firstOrNull { it.block.link.hash == link }
//    }
//
//    override suspend fun findAnyTransaction(): Transaction? {
//        return transactions.values.firstOrNull()
//    }
//}