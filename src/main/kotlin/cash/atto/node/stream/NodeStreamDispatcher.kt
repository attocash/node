package cash.atto.node.stream

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.node.AccountSearch
import cash.atto.commons.node.AttoStreamMethod
import cash.atto.commons.node.AttoStreamProtocol
import cash.atto.commons.node.AttoStreamRequest
import cash.atto.commons.node.HeightSearch
import cash.atto.commons.node.stream.server.AttoStreamMember
import cash.atto.commons.node.stream.server.AttoStreamPlan
import cash.atto.commons.spring.AttoStreamRequestTarget
import cash.atto.node.account.AccountQueries
import cash.atto.node.account.entry.AccountEntryQueries
import cash.atto.node.account.validateRanges
import cash.atto.node.receivable.ReceivableQueries
import cash.atto.node.transaction.TransactionQueries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/** Translates the public stream protocol into the same query operations used by HTTP. */
@Component
class NodeStreamDispatcher(
    private val accounts: AccountQueries,
    private val receivables: ReceivableQueries,
    private val entries: AccountEntryQueries,
    private val transactions: TransactionQueries,
    @param:Qualifier("publicApiJson") private val json: Json,
) {
    suspend fun plan(
        request: AttoStreamRequest,
        mutable: Boolean,
    ): AttoStreamPlan {
        if (!mutable) {
            return AttoStreamPlan(
                mapOf(
                    "stream" to AttoStreamMember(AttoStreamProtocol.encodeRequest(request)) { dispatch(request) },
                ),
            )
        }
        request.requireMethod(AttoStreamMethod.POST)
        val target = AttoStreamRequestTarget(request.path)
        val path = target.segments
        return when (path) {
            listOf("v2", "accounts", "stream") -> {
                val search = request.body<AccountSearch>()
                AttoStreamPlan(
                    search.addresses.toSet().associate { address ->
                        address.toString() to AttoStreamMember("account") { accounts.stream(AccountSearch(setOf(address))).encoded() }
                    },
                )
            }

            listOf("v2", "accounts", "receivables", "stream") -> {
                val search = request.body<AccountSearch>()
                val minAmount = target.minAmount()
                AttoStreamPlan(
                    search.addresses.toSet().associate { address ->
                        address.toString() to
                            AttoStreamMember("receivables:$minAmount") {
                                receivables.stream(AccountSearch(setOf(address)), minAmount).encoded()
                            }
                    },
                )
            }

            listOf("v2", "accounts", "entries", "stream"), listOf("v2", "accounts", "transactions", "stream") -> {
                val search = request.body<HeightSearch>()
                search.validateRanges()
                if (search.search
                        .map { it.address }
                        .distinct()
                        .size != search.search.size
                ) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Mutable stream addresses must be unique")
                }
                AttoStreamPlan(
                    search.search.associate { range ->
                        range.address.toString() to
                            AttoStreamMember("${path[2]}:${range.fromHeight}:${range.toHeight}") {
                                val memberSearch = HeightSearch(listOf(range))
                                if (path[2] == "entries") {
                                    entries.streamMultiple(memberSearch).encoded()
                                } else {
                                    transactions.streamMultiple(memberSearch).encoded()
                                }
                            }
                    },
                )
            }

            else -> {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "This stream does not support membership updates")
            }
        }
    }

    suspend fun dispatch(request: AttoStreamRequest): Flow<JsonElement> {
        val target = AttoStreamRequestTarget(request.path)
        val path = target.segments
        return when {
            path == listOf("v2", "accounts", "stream") -> {
                when (request.method) {
                    AttoStreamMethod.GET -> accounts.stream().encoded()
                    AttoStreamMethod.POST -> accounts.stream(request.body<AccountSearch>()).encoded()
                }
            }

            path == listOf("v2", "accounts", "receivables", "stream") -> {
                request.requireMethod(AttoStreamMethod.POST)
                receivables.stream(request.body<AccountSearch>(), target.minAmount()).encoded()
            }

            path == listOf("v2", "accounts", "entries", "stream") -> {
                when (request.method) {
                    AttoStreamMethod.GET -> entries.stream().encoded()
                    AttoStreamMethod.POST -> entries.streamMultiple(request.body<HeightSearch>()).encoded()
                }
            }

            path == listOf("v2", "accounts", "transactions", "stream") -> {
                request.requireMethod(AttoStreamMethod.POST)
                transactions.streamMultiple(request.body()).encoded()
            }

            path == listOf("v2", "transactions", "stream") -> {
                request.requireMethod(AttoStreamMethod.GET)
                transactions.stream().encoded()
            }

            path.size == 4 && path.take(2) == listOf("v2", "transactions") && path.last() == "stream" -> {
                request.requireMethod(AttoStreamMethod.GET)
                transactions.stream(bind { AttoHash.parse(path[2]) }).encoded()
            }

            path.size == 5 && path.take(3) == listOf("v2", "accounts", "entries") && path.last() == "stream" -> {
                request.requireMethod(AttoStreamMethod.GET)
                entries.stream(bind { AttoHash.parse(path[3]) }).encoded()
            }

            path.size in 4..5 && path.take(2) == listOf("v2", "accounts") && path.last() == "stream" -> {
                if (path.size == 5 && path[3] !in setOf("receivables", "entries", "transactions")) {
                    throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown stream operation")
                }
                request.requireMethod(AttoStreamMethod.GET)
                val address = bind { AttoAddress.parse(path[2]) }
                when {
                    path.size == 4 -> {
                        accounts.stream(address).encoded()
                    }

                    path[3] == "receivables" -> {
                        receivables.stream(address, target.minAmount()).encoded()
                    }

                    path[3] == "entries" -> {
                        entries.stream(address, target.fromHeight(), target.toHeight()).encoded()
                    }

                    path[3] == "transactions" -> {
                        transactions.stream(address, target.fromHeight(), target.toHeight() ?: AttoHeight.MAX).encoded()
                    }

                    else -> {
                        throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown stream operation")
                    }
                }
            }

            else -> {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown stream operation")
            }
        }
    }

    private fun AttoStreamRequest.requireMethod(expected: AttoStreamMethod) {
        if (method != expected) {
            throw ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "Stream operation requires $expected")
        }
    }

    private inline fun <reified T> AttoStreamRequest.body(): T =
        bind {
            val value = body ?: throw IllegalArgumentException("Missing stream request body")
            json.decodeFromJsonElement<T>(value)
        }

    private fun AttoStreamRequestTarget.scalar(name: String): String? {
        if (queryValues(name).size > 1) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Repeated stream parameter: $name")
        return query(name)
    }

    private fun AttoStreamRequestTarget.minAmount(): AttoAmount = bind { AttoAmount(scalar("minAmount").orDefault("1").toULong()) }

    private fun AttoStreamRequestTarget.fromHeight(): AttoHeight = bind { AttoHeight(scalar("fromHeight").orDefault("1").toULong()) }

    private fun AttoStreamRequestTarget.toHeight(): AttoHeight? =
        bind { scalar("toHeight")?.takeIf { it.isNotEmpty() }?.let { AttoHeight(it.toULong()) } }

    private fun String?.orDefault(default: String): String = this?.takeIf { it.isNotEmpty() } ?: default

    private inline fun <reified T> Flow<T>.encoded(): Flow<JsonElement> = map { json.encodeToJsonElement(it) }

    private inline fun <T> bind(block: () -> T): T =
        try {
            block()
        } catch (exception: SerializationException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid stream operation parameters", exception)
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid stream operation parameters", exception)
        }
}
