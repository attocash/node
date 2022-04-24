package org.atto.node.account

import org.atto.commons.AttoAmount
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.node.AttoRepository
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.Repository
import java.time.Instant

interface AccountRepository : Repository<AttoPublicKey, Account>, AttoRepository {

    suspend fun save(account: Account)

    suspend fun findByPublicKey(publicKey: AttoPublicKey): Account?

    suspend fun getByPublicKey(publicKey: AttoPublicKey): Account {
        val account = findByPublicKey(publicKey)
        if (account != null) {
            return account
        }

        return Account(
            publicKey = publicKey,
            version = 0u,
            height = 0u,
            representative = AttoPublicKey(ByteArray(32)),
            balance = AttoAmount.min,
            lastHash = AttoHash(ByteArray(32)),
            lastTimestamp = Instant.MIN
        )
    }

    @Query("select a.publicKey, sum(a.balance) from Account a group by a.publicKey")
    suspend fun findAllWeights(): List<WeightView>

    data class WeightView(val publicKey: AttoPublicKey, val weight: AttoAmount)
}