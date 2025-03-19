package cash.atto.node.vote.keep

import cash.atto.node.PropertyHolder
import cash.atto.node.Waiter.waitUntilNonNull
import cash.atto.node.transaction.Transaction
import cash.atto.node.vote.VoteRepository
import cash.atto.node.vote.keeping.VoteKeeper
import io.cucumber.java.en.When
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class VoteKeepStepDefinition(
    private val voteKeeper: VoteKeeper,
    private val voteRepository: VoteRepository,
) {
    @When("transaction {word} missing votes are grabbed")
    fun assertUncheckedCount(transactionShortId: String) {
        val transaction = PropertyHolder.get(Transaction::class.java, transactionShortId)

        runBlocking {
            voteKeeper.keep()
            waitUntilNonNull {
                runBlocking {
                    voteRepository.findByBlockHash(transaction.hash).toList().firstOrNull()
                }
            }
        }
    }
}
