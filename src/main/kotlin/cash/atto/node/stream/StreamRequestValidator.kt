package cash.atto.node.stream

import cash.atto.commons.node.AccountHeightSearch
import cash.atto.commons.node.AccountSearch
import cash.atto.commons.node.HeightSearch
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

@Component
class StreamRequestValidator(
    private val properties: StreamProperties,
) {
    fun validate(search: AccountSearch) {
        val addresses = search.addresses
        reject(addresses.isEmpty(), "At least one address is required")
        reject(addresses.size > properties.maxAddressesPerRequest, "Too many addresses")
        reject(addresses.toSet().size != addresses.size, "Duplicate addresses are not allowed")
    }

    fun validate(search: HeightSearch) {
        val ranges = search.search.toList()
        reject(ranges.isEmpty(), "At least one height range is required")
        reject(ranges.size > properties.maxRangesPerRequest, "Too many height ranges")

        ranges.forEach { range ->
            reject(range.fromHeight.value == 0UL, "fromHeight can't be zero")
            reject(range.fromHeight > range.toHeight, "toHeight must be greater or equal to fromHeight")
        }

        ranges.forEachIndexed { index, range ->
            ranges.drop(index + 1).forEach { other ->
                reject(rangesOverlap(range, other), "Duplicate or overlapping height ranges are not allowed")
            }
        }

        val limit = properties.maxRequestedEntries.toULong()
        var requestedEntries = 0UL
        ranges.forEach { range ->
            val span = range.toHeight.value - range.fromHeight.value + 1UL
            reject(span > limit || requestedEntries > limit - span, "Too many requested entries")
            requestedEntries += span
        }
    }

    private fun rangesOverlap(
        first: AccountHeightSearch,
        second: AccountHeightSearch,
    ): Boolean =
        first.address.publicKey == second.address.publicKey &&
            first.fromHeight <= second.toHeight &&
            second.fromHeight <= first.toHeight

    private fun reject(
        rejected: Boolean,
        reason: String,
    ) {
        if (rejected) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, reason)
        }
    }
}
