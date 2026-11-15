package cash.atto.node.stream

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.node.AccountHeightSearch
import cash.atto.commons.node.AccountSearch
import cash.atto.commons.node.AttoStreamMethod
import cash.atto.commons.node.AttoStreamRequest
import cash.atto.commons.node.HeightSearch
import cash.atto.commons.toAttoVersion
import cash.atto.node.HttpSerializationConfiguration
import cash.atto.node.account.AccountQueries
import cash.atto.node.account.entry.AccountEntryQueries
import cash.atto.node.receivable.ReceivableQueries
import cash.atto.node.transaction.TransactionQueries
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class NodeStreamDispatcherTest {
    private val json: Json = HttpSerializationConfiguration().publicApiJson()
    private val accounts = mockk<AccountQueries>()
    private val receivables = mockk<ReceivableQueries>()
    private val entries = mockk<AccountEntryQueries>()
    private val transactions = mockk<TransactionQueries>()
    private val dispatcher = NodeStreamDispatcher(accounts, receivables, entries, transactions, json)
    private val address = AttoAddress(AttoAlgorithm.V1, AttoPublicKey(ByteArray(32) { 1 }))
    private val encodedAddress = URLEncoder.encode(address.toString(), StandardCharsets.UTF_8)
    private val hash = AttoHash(ByteArray(32) { 2 })

    @Test
    fun `mutable account plan prepares independent address streams without starting them`(): Unit =
        runTest {
            // Given
            val second = AttoAddress(AttoAlgorithm.V1, AttoPublicKey(ByteArray(32) { 3 }))
            coEvery { accounts.stream(any<AccountSearch>()) } returns emptyFlow()
            val request = post("/v2/accounts/stream", json.encodeToJsonElement(AccountSearch(setOf(address, second))))

            // When
            val plan = dispatcher.plan(request, true)

            // Then
            assertEquals(setOf(address.toString(), second.toString()), plan.members.keys)
            coVerify(exactly = 0) { accounts.stream(any<AccountSearch>()) }

            // When
            plan.members
                .getValue(address.toString())
                .stream()
                .toList()

            // Then
            coVerify(exactly = 1) { accounts.stream(AccountSearch(setOf(address))) }
            coVerify(exactly = 0) { accounts.stream(AccountSearch(setOf(second))) }
        }

    @Test
    fun `mutable planner validates the complete ranges and excludes publication before starting work`(): Unit =
        runTest {
            // Given
            val second = AttoAddress(AttoAlgorithm.V1, AttoPublicKey(ByteArray(32) { 3 }))
            val invalid = HeightSearch(listOf(AccountHeightSearch(address, AttoHeight(1UL)), AccountHeightSearch(second, AttoHeight(0UL))))
            val duplicate =
                HeightSearch(listOf(AccountHeightSearch(address, AttoHeight(1UL)), AccountHeightSearch(address, AttoHeight(2UL))))
            val requests =
                listOf(
                    post("/v2/accounts/entries/stream", json.encodeToJsonElement(invalid)),
                    post("/v2/accounts/transactions/stream", json.encodeToJsonElement(duplicate)),
                    post("/v2/transactions", JsonPrimitive("ignored")),
                )

            // When / Then
            requests.forEach { request ->
                val failure = runCatching { dispatcher.plan(request, true) }.exceptionOrNull() as ResponseStatusException
                assertEquals(400, failure.statusCode.value())
            }
            coVerify(exactly = 0) { entries.streamMultiple(any()) }
            coVerify(exactly = 0) { transactions.streamMultiple(any()) }
        }

    @Test
    fun `publication is unavailable through static and mutable WebSocket subscriptions`(): Unit =
        runTest {
            // Given
            val request = post("/v2/transactions", JsonPrimitive("ignored"))

            // When
            val plan = dispatcher.plan(request, false)
            val staticFailure =
                runCatching {
                    plan.members.values
                        .single()
                        .stream()
                        .toList()
                }.exceptionOrNull()
            val mutableFailure = runCatching { dispatcher.plan(request, true) }.exceptionOrNull()

            // Then
            assertEquals(404, (staticFailure as ResponseStatusException).statusCode.value())
            assertEquals(400, (mutableFailure as ResponseStatusException).statusCode.value())
        }

    @Test
    fun `all thirteen read stream operations delegate directly with typed inputs`(): Unit =
        runTest {
            // Given
            val search = AccountSearch(listOf(address))
            val heights = HeightSearch(listOf(AccountHeightSearch(address, AttoHeight(2UL), AttoHeight(5UL))))
            coEvery { accounts.stream() } returns emptyFlow()
            coEvery { accounts.stream(search) } returns emptyFlow()
            coEvery { accounts.stream(address) } returns emptyFlow()
            coEvery { receivables.stream(search, AttoAmount(7UL)) } returns emptyFlow()
            coEvery { receivables.stream(address, AttoAmount(1UL)) } returns emptyFlow()
            coEvery { entries.stream() } returns emptyFlow()
            coEvery { entries.stream(hash) } returns emptyFlow()
            coEvery { entries.streamMultiple(heights) } returns emptyFlow()
            coEvery { entries.stream(address, AttoHeight(1UL), null) } returns emptyFlow()
            coEvery { transactions.stream() } returns emptyFlow()
            coEvery { transactions.stream(hash) } returns emptyFlow()
            coEvery { transactions.streamMultiple(heights) } returns emptyFlow()
            coEvery { transactions.stream(address, AttoHeight(2UL), AttoHeight(5UL)) } returns emptyFlow()
            val operations =
                listOf(
                    get("/v2/accounts/stream"),
                    post("/v2/accounts/stream", json.encodeToJsonElement(search)),
                    get("/v2/accounts/$encodedAddress/stream"),
                    post("/v2/accounts/receivables/stream?minAmount=7", json.encodeToJsonElement(search)),
                    get("/v2/accounts/$encodedAddress/receivables/stream"),
                    get("/v2/accounts/entries/stream"),
                    get("/v2/accounts/entries/$hash/stream"),
                    post("/v2/accounts/entries/stream", json.encodeToJsonElement(heights)),
                    get("/v2/accounts/$encodedAddress/entries/stream"),
                    get("/v2/transactions/stream"),
                    get("/v2/transactions/$hash/stream"),
                    post("/v2/accounts/transactions/stream", json.encodeToJsonElement(heights)),
                    get("/v2/accounts/$encodedAddress/transactions/stream?fromHeight=2&toHeight=5"),
                )

            // When
            operations.forEach { assertEquals(emptyList<JsonElement>(), dispatcher.dispatch(it).toList()) }

            // Then
            coVerify(exactly = 1) { accounts.stream() }
            coVerify(exactly = 1) { accounts.stream(search) }
            coVerify(exactly = 1) { accounts.stream(address) }
            coVerify(exactly = 1) { receivables.stream(search, AttoAmount(7UL)) }
            coVerify(exactly = 1) { receivables.stream(address, AttoAmount(1UL)) }
            coVerify(exactly = 1) { entries.stream() }
            coVerify(exactly = 1) { entries.stream(hash) }
            coVerify(exactly = 1) { entries.streamMultiple(heights) }
            coVerify(exactly = 1) { entries.stream(address, AttoHeight(1UL), null) }
            coVerify(exactly = 1) { transactions.stream() }
            coVerify(exactly = 1) { transactions.stream(hash) }
            coVerify(exactly = 1) { transactions.streamMultiple(heights) }
            coVerify(exactly = 1) { transactions.stream(address, AttoHeight(2UL), AttoHeight(5UL)) }
        }

    @Test
    fun `stream uses the exact HTTP data serializer`(): Unit =
        runTest {
            // Given
            val account =
                AttoAccount(
                    address = address,
                    network = AttoNetwork.LOCAL,
                    version = 0U.toAttoVersion(),
                    height = AttoHeight(1UL),
                    balance = AttoAmount(5UL),
                    lastTransactionHash = hash,
                    lastTransactionTimestamp = AttoInstant.now(),
                    representativeAddress = address,
                )
            coEvery { accounts.stream() } returns flowOf(account)

            // When
            val values = dispatcher.dispatch(get("/v2/accounts/stream")).toList()

            // Then
            assertEquals(listOf(json.encodeToJsonElement(account)), values)
        }

    @Test
    fun `finite endpoints unknown operations invalid inputs and wrong methods are rejected`(): Unit =
        runTest {
            // Given
            val cases =
                listOf(
                    post("/v2/accounts", JsonPrimitive("ignored")) to 404,
                    get("/v2/accounts/$encodedAddress") to 404,
                    get("/v2/transactions/$hash") to 404,
                    get("/v2/accounts/receivables/stream") to 405,
                    post("/v2/transactions/$hash/stream", null) to 405,
                    get("/v2/accounts/invalid/stream") to 400,
                    get("/v2/accounts/$encodedAddress/receivables/stream?minAmount=invalid") to 400,
                    get("/v2/accounts/$encodedAddress/entries/stream?fromHeight=-1") to 400,
                    post("/v2/transactions?deduplicate=invalid", JsonPrimitive("ignored")) to 404,
                    post("/v2/accounts/stream", null) to 400,
                    post("/v2/accounts/stream", JsonPrimitive("invalid")) to 400,
                )

            // When / Then
            cases.forEach { (operation, status) ->
                val result = runCatching { dispatcher.dispatch(operation) }.exceptionOrNull()
                assertEquals(status, (result as ResponseStatusException).statusCode.value(), operation.path)
            }
        }

    @Test
    fun `relative target helper rejects a double decoded address`() {
        // Given
        val target = "/v2/accounts/${URLEncoder.encode(encodedAddress, StandardCharsets.UTF_8)}/stream"

        // When / Then
        assertThrows(ResponseStatusException::class.java) {
            kotlinx.coroutines.runBlocking { dispatcher.dispatch(get(target)) }
        }
    }

    private fun get(path: String): AttoStreamRequest = AttoStreamRequest(AttoStreamMethod.GET, path)

    private fun post(
        path: String,
        body: JsonElement?,
    ): AttoStreamRequest = AttoStreamRequest(AttoStreamMethod.POST, path, body)
}
