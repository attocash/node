package org.atto.node.transaction

import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.protocol.transaction.Transaction
import java.util.concurrent.ConcurrentHashMap


class MockTransactionRepository(properties: TransactionProperties) :
    TransactionRepository(properties, CoroutineScope(Dispatchers.Default), mockk()) {
    private val transactions = ConcurrentHashMap<AttoHash, Transaction>()

    override suspend fun save(entity: Transaction): Transaction {
        transactions[entity.hash] = entity
        return entity
    }

    override suspend fun findById(id: AttoHash): Transaction? {
        return transactions[id]
    }

    override suspend fun findLastByPublicKeyId(publicKey: AttoPublicKey): Transaction? {
        return transactions.values
            .filter { it.block.publicKey.value.contentEquals(publicKey.value) }
            .maxByOrNull { it.block.height }
    }

    override suspend fun findAnyTransaction(): Transaction? {
        return transactions.values.firstOrNull()
    }
}