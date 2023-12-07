package atto.node.account

import atto.node.AttoRepository
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.math.BigInteger
import java.time.Instant

interface AccountRepository : CoroutineCrudRepository<Account, AttoPublicKey>, AttoRepository {

    suspend fun getByPublicKey(publicKey: AttoPublicKey): Account {
        val account = findById(publicKey)
        if (account != null) {
            return account
        }

        return Account(
            publicKey = publicKey,
            version = 0u,
            height = 0u,
            representative = AttoPublicKey(ByteArray(32)),
            balance = AttoAmount.MIN,
            lastTransactionHash = AttoHash(ByteArray(32)),
            lastTransactionTimestamp = Instant.MIN
        )
    }

    @Query("SELECT representative AS public_key, CAST(SUM(balance) AS UNSIGNED) AS weight FROM account GROUP BY representative")
    suspend fun findAllWeights(): List<WeightView>
}

data class WeightView(
    val publicKey: AttoPublicKey,
    val weight: BigInteger // r2dbc doesn't seem to respect the DBConverter
)