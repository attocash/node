package atto.node.receivable

import atto.node.AttoRepository
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository


interface ReceivableRepository : CoroutineCrudRepository<Receivable, AttoHash>, AttoRepository {

    @Query("SELECT * FROM receivable t WHERE t.receiver_public_key = :publicKey ORDER BY t.amount DESC")
    suspend fun findAsc(publicKey: AttoPublicKey): Flow<Receivable>

}