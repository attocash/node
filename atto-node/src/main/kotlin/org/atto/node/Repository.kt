package org.atto.node

interface AttoRepository {
    suspend fun deleteAll()
}