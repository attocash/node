package cash.atto.node

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DemandDrivenWorkerTest {
    @Test
    fun `waits once before draining accumulated requests`() =
        runTest {
            // Given
            var drains = 0
            val worker =
                DemandDrivenWorker(
                    name = "test-worker",
                    dispatcher = StandardTestDispatcher(testScheduler),
                ) {
                    drains++
                }

            try {
                // When
                worker.request()
                runCurrent()
                worker.request()

                // Then
                assertEquals(0, drains)

                // When
                advanceTimeBy(1)
                runCurrent()

                // Then
                assertEquals(2, drains)
                assertEquals(1, testScheduler.currentTime)
            } finally {
                worker.cancel()
            }
        }

    @Test
    fun `doubles retry backoff up to one minute`() =
        runTest {
            // Given
            val attemptedAt = mutableListOf<Long>()
            val worker =
                DemandDrivenWorker(
                    name = "test-worker",
                    dispatcher = StandardTestDispatcher(testScheduler),
                ) {
                    attemptedAt += testScheduler.currentTime
                    if (attemptedAt.size < 19) {
                        throw IllegalStateException("failed")
                    }
                }

            try {
                // When
                worker.request()
                advanceUntilIdle()

                // Then
                assertEquals(
                    listOf(
                        1L,
                        3L,
                        7L,
                        15L,
                        31L,
                        63L,
                        127L,
                        255L,
                        511L,
                        1_023L,
                        2_047L,
                        4_095L,
                        8_191L,
                        16_383L,
                        32_767L,
                        65_535L,
                        125_535L,
                        185_535L,
                        245_535L,
                    ),
                    attemptedAt,
                )
            } finally {
                worker.cancel()
            }
        }

    @Test
    fun `resets retry backoff after a successful drain`() =
        runTest {
            // Given
            val attemptedAt = mutableListOf<Long>()
            val failedAttempts = setOf(1, 3)
            val worker =
                DemandDrivenWorker(
                    name = "test-worker",
                    dispatcher = StandardTestDispatcher(testScheduler),
                ) {
                    attemptedAt += testScheduler.currentTime
                    if (attemptedAt.size in failedAttempts) {
                        throw IllegalStateException("failed")
                    }
                }

            try {
                // When
                worker.request()
                advanceUntilIdle()
                worker.request()
                advanceUntilIdle()

                // Then
                assertEquals(listOf(1L, 3L, 4L, 6L), attemptedAt)
            } finally {
                worker.cancel()
            }
        }
}
