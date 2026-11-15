package cash.atto.node.vote.weight

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoInstant
import cash.atto.node.LegacyAmountSerializer
import cash.atto.node.LegacyInstantSerializer
import io.swagger.v3.oas.annotations.media.Schema
import kotlinx.serialization.Serializable

@Serializable
data class LegacyVoterWeightResponse(
    val address: AttoAddress,
    @get:Schema(type = "integer", format = "uint64", implementation = java.math.BigInteger::class)
    @Serializable(with = LegacyAmountSerializer::class)
    val weight: AttoAmount,
    @get:Schema(type = "integer", format = "int64", implementation = Long::class)
    @Serializable(with = LegacyInstantSerializer::class)
    val lastVotedAt: AttoInstant,
)
