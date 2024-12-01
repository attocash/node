package cash.atto.node.bootstrap.unchecked

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.node.AttoRepository
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface UncheckedTransactionRepository :
    CoroutineCrudRepository<UncheckedTransaction, AttoHash>,
    AttoRepository {
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
            ORDER BY timestamp
            LIMIT :limit
        """,
    )
    suspend fun findReadyToValidate(limit: Long): Flow<UncheckedTransaction>

    @Modifying
    @Query(
        """
            WITH hashes_to_delete AS (
                SELECT ut.hash
                FROM unchecked_transaction ut
                JOIN transaction t ON ut.hash = t.hash
            )
            DELETE FROM unchecked_transaction
            WHERE hash IN (SELECT hash FROM hashes_to_delete)
        """
    )
    suspend fun deleteExistingInTransaction() : Int
}

data class GapView(
    val publicKey: AttoPublicKey,
    val startHeight: AttoHeight,
    val endHeight: AttoHeight,
    val expectedEndHash: AttoHash,
)
