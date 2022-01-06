package org.atto.node

interface AttoRepository<T, ID> {
    suspend fun save(entity: T): T
    suspend fun findById(id: ID): T?
    suspend fun deleteAll(): Int
}