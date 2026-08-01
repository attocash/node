package cash.atto.node.stream

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.node.ApplicationProperties
import cash.atto.node.EventPublisher
import cash.atto.node.account.AccountController
import cash.atto.node.account.AccountCrudRepository
import cash.atto.node.account.AccountRepository
import cash.atto.node.account.entry.AccountEntryController
import cash.atto.node.account.entry.AccountEntryRepository
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.receivable.ReceivableController
import cash.atto.node.receivable.ReceivableRepository
import cash.atto.node.transaction.TransactionController
import cash.atto.node.transaction.TransactionRepository
import cash.atto.protocol.AttoNode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient

internal class ControllerStreamRequestValidationTest {
    private val validator = StreamRequestValidator(StreamProperties())

    @Test
    fun `transaction range POST rejects invalid body before repository access`() {
        // given
        val repository = mockk<TransactionRepository>(relaxed = true)
        val client = WebTestClient.bindToController(transactionController(repository)).build()

        // when
        val response = post(client, "/accounts/transactions/stream", "{\"search\":[]}")

        // then
        response.expectStatus().isBadRequest
        coVerify(exactly = 0) { repository.findAsc(any(), any(), any()) }
    }

    @Test
    fun `account entry range POST rejects invalid body before repository access`() {
        // given
        val repository = mockk<AccountEntryRepository>(relaxed = true)
        val client = WebTestClient.bindToController(AccountEntryController(repository, validator)).build()

        // when
        val response = post(client, "/accounts/entries/stream", "{\"search\":[]}")

        // then
        response.expectStatus().isBadRequest
        coVerify(exactly = 0) { repository.findAsc(any(), any(), any()) }
    }

    @Test
    fun `account address POST rejects invalid body before repository access`() {
        // given
        val repository = mockk<AccountRepository>(relaxed = true)
        val controller =
            AccountController(
                mockk(relaxed = true),
                mockk(relaxed = true),
                repository,
                mockk<AccountCrudRepository>(relaxed = true),
                validator,
            )
        val client = WebTestClient.bindToController(controller).build()

        // when
        val response = post(client, "/accounts/stream", "{\"addresses\":[]}")

        // then
        response.expectStatus().isBadRequest
        verify(exactly = 0) { repository.findAllById(any()) }
    }

    @Test
    fun `receivable address POST rejects invalid body before repository access`() {
        // given
        val repository = mockk<ReceivableRepository>(relaxed = true)
        val client =
            WebTestClient
                .bindToController(ReceivableController(repository, validator))
                .formatters { registry ->
                    registry.addConverter(String::class.java, AttoAmount::class.java) { AttoAmount(it.toULong()) }
                }.build()

        // when
        val response = post(client, "/accounts/receivables/stream", "{\"addresses\":[]}")

        // then
        response.expectStatus().isBadRequest
        coVerify(exactly = 0) { repository.findAllDesc(any(), any()) }
    }

    @Test
    fun `single account GET retains open ended height compatibility`() =
        runTest {
            // given
            val repository = mockk<TransactionRepository>(relaxed = true)
            coEvery { repository.findAsc(any(), any(), any()) } returns emptyFlow()
            val strictValidator =
                StreamRequestValidator(
                    StreamProperties().apply { maxRequestedEntries = 1 },
                )
            val controller = transactionController(repository, strictValidator)

            // when
            controller.stream(AttoPublicKey(ByteArray(32)), AttoHeight(1UL), AttoHeight.MAX)

            // then
            coVerify(exactly = 1) { repository.findAsc(any(), any(), any()) }
        }

    private fun transactionController(
        repository: TransactionRepository,
        streamRequestValidator: StreamRequestValidator = validator,
    ): TransactionController =
        TransactionController(
            ApplicationProperties(),
            mockk<AttoNode>(relaxed = true),
            mockk<EventPublisher>(relaxed = true),
            mockk<NetworkMessagePublisher>(relaxed = true),
            repository,
            streamRequestValidator,
        )

    private fun post(
        client: WebTestClient,
        uri: String,
        body: String,
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri(uri)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
}
