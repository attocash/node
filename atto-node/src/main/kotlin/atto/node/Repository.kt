package atto.node

interface AttoRepository {
    suspend fun deleteAll()
}