package cash.atto.node.account

import cash.atto.commons.node.HeightSearch
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/** Validate the complete search before any member starts or changes its live subscription. */
internal fun HeightSearch.validateRanges() {
    if (search.any { it.fromHeight.value == 0UL }) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "fromHeight can't be zero")
    }
    if (search.any { it.fromHeight > it.toHeight }) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "toHeight must be greater or equal to fromHeight")
    }
}
