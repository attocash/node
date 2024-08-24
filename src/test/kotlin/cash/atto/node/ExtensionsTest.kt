package cash.atto.node

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ExtensionsTest {
    private val publicKey = AttoPublicKey(Random.Default.nextBytes(32))

    @Test
    fun `sortByHeight should sort stream`() {
        // given
        val account1 = create(1U)
        val account2 = create(2U)
        val account3 = create(3U)

        // when

        val sorted =
            runBlocking {
                arrayOf(account3, account1, account2)
                    .asFlow()
                    .sortByHeight(1U.toAttoHeight())
                    .toList()
            }

        // then
        assertThat(sorted).containsExactly(account1, account2, account3)
    }

    @Test
    fun `sortByHeight should deduplicate stream`() {
        // given
        val account1 = create(1U)
        val account2 = create(2U)
        val account3 = create(3U)

        // when

        val sorted =
            runBlocking {
                arrayOf(account3, account1, account1, account2, account3)
                    .asFlow()
                    .sortByHeight(1U.toAttoHeight())
                    .toList()
            }

        // then
        assertThat(sorted).containsExactly(account1, account2, account3)
    }

    @Test
    fun `sortByHeight should skip old heights`() {
        // given
        val account1 = create(1U)
        val account2 = create(2U)
        val account3 = create(3U)

        // when

        val sorted =
            runBlocking {
                arrayOf(account3, account1, account2)
                    .asFlow()
                    .sortByHeight(2U.toAttoHeight())
                    .toList()
            }

        // then
        assertThat(sorted).containsExactly(account2, account3)
    }

    @Test
    fun `forwardHeight should ignore late height`() {
        // given
        val account1 = create(1U)
        val account2 = create(2U)
        val account3 = create(3U)

        // when

        val sorted =
            runBlocking {
                arrayOf(account2, account1, account3)
                    .asFlow()
                    .forwardHeight()
                    .toList()
            }

        // then
        assertThat(sorted).containsExactly(account2, account3)
    }

    private fun create(height: ULong): AttoAccount =
        AttoAccount(
            publicKey = publicKey,
            network = AttoNetwork.LOCAL,
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            height = height.toAttoHeight(),
            balance = AttoAmount.MAX,
            lastTransactionHash = AttoHash(Random.Default.nextBytes(32)),
            lastTransactionTimestamp = Clock.System.now(),
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = publicKey,
        )
}
