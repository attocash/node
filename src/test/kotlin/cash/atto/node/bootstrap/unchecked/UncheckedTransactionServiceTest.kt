package cash.atto.node.bootstrap.unchecked

import cash.atto.node.EventPublisher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UncheckedTransactionServiceTest {
    @Test
    fun `cleans up at most the requested unchecked transactions`() =
        runTest {
            // given
            val repository = mockk<UncheckedTransactionRepository>()
            val inserter = mockk<UncheckedTransactionInserter>()
            val eventPublisher = mockk<EventPublisher>()
            val service = UncheckedTransactionService(repository, inserter, eventPublisher)
            coEvery { repository.deleteExistingInTransaction(1_000L) } returns 37

            // when
            val deleted = service.cleanUp(1_000L)

            // then
            assertEquals(37, deleted)
            coVerify(exactly = 1) { repository.deleteExistingInTransaction(1_000L) }
        }
}
