package cash.atto.node.vote.keep

import cash.atto.commons.toBigInteger
import cash.atto.node.PropertyHolder
import cash.atto.node.Waiter.waitUntilTrue
import cash.atto.node.transaction.Transaction
import cash.atto.node.vote.VoteRepository
import cash.atto.node.vote.keeping.VoteKeeper
import cash.atto.node.vote.weight.VoteWeighter
import io.cucumber.java.en.When
import kotlinx.coroutines.runBlocking

class VoteKeepStepDefinition(
    private val voteKeeper: VoteKeeper,
    private val voteRepository: VoteRepository,
    private val voteWeighter: VoteWeighter,
) {
    @When("transaction {word} missing votes are grabbed")
    fun assertUncheckedCount(transactionShortId: String) {
        val transaction = PropertyHolder.get(Transaction::class.java, transactionShortId)

        runBlocking {
            waitUntilTrue {
                runBlocking {
                    voteKeeper.keep()
                    voteKeeper.flush()
                    val minimalWeight = voteWeighter.getMinimalConfirmationWeight()
                    voteRepository.findMissingVote(minimalWeight.raw.toBigInteger()).none { it.lastTransactionHash == transaction.hash }
                }
            }
        }
    }
}
