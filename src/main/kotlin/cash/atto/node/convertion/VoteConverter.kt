package cash.atto.node.convertion

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoVersion
import cash.atto.node.ApplicationProperties
import cash.atto.node.toULong
import cash.atto.node.vote.Vote
import io.r2dbc.spi.Row
import org.springframework.data.r2dbc.mapping.OutboundRow
import org.springframework.r2dbc.core.Parameter
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.time.Instant

@Component
class VoteSerializerDBConverter(
    val properties: ApplicationProperties,
) : DBConverter<Vote, OutboundRow> {
    override fun convert(vote: Vote): OutboundRow {
        val row = OutboundRow()
        with(row) {
            put("hash", Parameter.from(vote.hash))
            put("version", Parameter.from(vote.version))
            put("algorithm", Parameter.from(vote.algorithm))
            put("public_key", Parameter.from(vote.publicKey))
            put("block_algorithm", Parameter.from(vote.blockAlgorithm))
            put("block_hash", Parameter.from(vote.blockHash))
            put("timestamp", Parameter.from(vote.timestamp.toEpochMilli()))
            put("signature", Parameter.from(vote.signature))
            put("received_at", Parameter.from(vote.receivedAt))
            put(
                "persisted_at",
                Parameter.fromOrEmpty(vote.persistedAt, Instant::class.java),
            )
        }

        return row
    }
}

@Component
class VoteDeserializerDBConverter(
    val properties: ApplicationProperties,
) : DBConverter<Row, Vote> {
    override fun convert(row: Row): Vote =
        Vote(
            hash = AttoHash(row.get("hash", ByteArray::class.java)!!),
            version = AttoVersion(row.get("version", Short::class.java)!!.toUShort()),
            algorithm = AttoAlgorithm.valueOf(row.get("algorithm", String::class.java)!!),
            publicKey = AttoPublicKey(row.get("public_key", ByteArray::class.java)!!),
            blockAlgorithm = AttoAlgorithm.valueOf(row.get("block_algorithm", String::class.java)!!),
            blockHash = AttoHash(row.get("block_hash", ByteArray::class.java)!!),
            timestamp = Instant.ofEpochMilli(row.get("timestamp", Long::class.java)!!),
            signature = AttoSignature(row.get("signature", ByteArray::class.java)!!),
            weight = AttoAmount(row.get("weight", BigInteger::class.java)!!.toULong()),
            receivedAt = row.get("received_at", Instant::class.java)!!,
            persistedAt = row.get("persisted_at", Instant::class.java)!!,
        )
}
