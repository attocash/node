package org.atto.node.bootstrap.unchecked

import kotlinx.coroutines.flow.Flow
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.node.AttoRepository
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

//    @Query(
//        """
//            SELECT public_key, account_height, transaction_height, transaction_hash FROM (
//                    SELECT  ROW_NUMBER() OVER(PARTITION BY ut.public_key ORDER BY ut.height DESC) AS row_num,
//                            ut.public_key public_key,
//                            COALESCE(a.height, 0) account_height,
//                            ut.height transaction_height,
//                            ut.hash transaction_hash
//                    FROM unchecked_transaction ut
//                    LEFT JOIN account a on ut.public_key = a.public_key and ut.height > a.height
//                    ORDER BY ut.public_key, ut.height ) ready
//            WHERE transaction_height > account_height + row_num
//            AND row_num = 1
//        """
//    )
//    suspend fun findGaps(): Flow<GapView>
}

data class GapView(
    val publicKey: AttoPublicKey,
    val accountHeight: ULong,
    val transactionHeight: ULong,
    val previousTransactionHash: AttoHash
)

