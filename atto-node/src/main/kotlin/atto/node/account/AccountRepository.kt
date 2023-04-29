package atto.node.account

import io.r2dbc.spi.Row
import atto.commons.AttoAmount
import atto.commons.AttoHash
import atto.commons.AttoPublicKey
import atto.node.AttoRepository
import atto.node.convertion.DBConverter
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component
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

    @Query("select representative public_key, CAST(sum(balance) AS BIGINT) weight from Account group by representative")
    suspend fun findAllWeights(): List<WeightView>
}

data class WeightView(
    val publicKey: AttoPublicKey,
    val weight: AttoAmount
)

@Component
class WeightViewDeserializerDBConverter : DBConverter<Row, WeightView> {
    override fun convert(row: Row): WeightView {
        return WeightView(
            publicKey = AttoPublicKey(row.get("public_key", ByteArray::class.java)!!),
            weight = AttoAmount(row.get("weight", Long::class.javaObjectType)!!.toULong()),
        )
    }

}