package cash.atto.node.vote.weight

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoVoterWeight
import cash.atto.commons.toAtto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/vote-weights")
@Tag(name = "Vote Weights", description = "Expose current vote weights per representative")
class VoteWeightController(
    private val voteWeighter: VoteWeighter,
) {
    @GetMapping("/{address}")
    @Operation(
        summary = "Get voter weight",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = Map::class),
                    ),
                ],
            ),
        ],
    )
    fun get(
        @PathVariable address: AttoAddress,
    ): ResponseEntity<AttoVoterWeight> {
        val weight = voteWeighter.get(address.publicKey)
        val lastVotedAt = voteWeighter.getLastestVote(address.publicKey)?.receivedAt?.toAtto() ?: Instant.EPOCH.toAtto()
        val voterWeight = AttoVoterWeight(address, weight, lastVotedAt)
        return ResponseEntity.ok(voterWeight)
    }
}
