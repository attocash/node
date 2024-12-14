package cash.atto.node.vote.weight

import cash.atto.commons.AttoPublicKey
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface WeightRepository : CoroutineCrudRepository<Weight, AttoPublicKey> {
    @Modifying
    @Query(
        """
        INSERT INTO weight (representative_algorithm, representative_public_key, weight)
        SELECT representative_algorithm, representative_public_key, CAST(SUM(balance) AS UNSIGNED) AS weight
        FROM account
        GROUP BY representative_algorithm, representative_public_key
        """
    )
    suspend fun refreshWeights()
}
