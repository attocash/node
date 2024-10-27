package cash.atto.node.account.entry

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.node.AttoRepository
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.math.BigInteger

interface AccountEntryRepository :
    AttoRepository,
    CoroutineCrudRepository<AccountEntry, AttoHash> {
    @Query(
        "SELECT * FROM account_entry t WHERE t.public_key = :publicKey AND t.height BETWEEN :fromHeight and :toHeight ORDER BY height ASC",
    )
    suspend fun findAsc(
        publicKey: AttoPublicKey,
        fromHeight: BigInteger,
        toHeight: BigInteger,
    ): Flow<AccountEntry>
}
