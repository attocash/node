package org.atto.node.vote.election

import io.swagger.v3.oas.annotations.Operation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@ExperimentalCoroutinesApi
@RestController
@RequestMapping("/transactions/elections")
class ElectionController(val election: Election) {

    @PostMapping
    @Operation(description = "Process staling transactions")
    suspend fun publish() {
        election.processStaling()
    }

}