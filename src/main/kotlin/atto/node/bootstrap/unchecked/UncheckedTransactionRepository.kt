package atto.node.bootstrap.unchecked

import atto.node.AttoRepository
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository


interface UncheckedTransactionRepository : CoroutineCrudRepository<UncheckedTransaction, AttoHash>, AttoRepository {
    @Query(
        """
            SELECT * FROM ( 
                    SELECT ROW_NUMBER() OVER(PARTITION BY ut.public_key ORDER BY ut.height) AS row_num, 
                            COALESCE(a.height, 0) account_height, 
                            ut.* 
                    FROM unchecked_transaction ut 
                    LEFT JOIN account a on ut.public_key = a.public_key and ut.height > a.height 
                    ORDER BY ut.public_key, ut.height ) ready 
            WHERE height = account_height + row_num 
            LIMIT :limit 
        """
    )
    suspend fun findReadyToValidate(limit: Long): Flow<UncheckedTransaction>
}

data class GapView(
    val algorithm: AttoAlgorithm,
    val publicKey: AttoPublicKey,
    val accountHeight: ULong,
    val transactionHeight: ULong,
    val previousTransactionHash: AttoHash
)

