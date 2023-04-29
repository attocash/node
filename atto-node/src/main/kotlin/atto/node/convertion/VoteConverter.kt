package atto.node.convertion

import io.r2dbc.spi.Row
import atto.commons.AttoAmount
import atto.commons.AttoHash
import atto.commons.AttoPublicKey
import atto.commons.AttoSignature
import atto.node.vote.Vote
import org.springframework.data.r2dbc.mapping.OutboundRow
import org.springframework.r2dbc.core.Parameter
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class VoteSerializerDBConverter : DBConverter<Vote, OutboundRow> {

    override fun convert(vote: Vote): OutboundRow {
        val row = OutboundRow()
        with(row) {
            put("hash", Parameter.from(vote.hash))
            put("public_key", Parameter.from(vote.publicKey))
            put("timestamp", Parameter.from(vote.timestamp.toLocalDateTime()))
            put("signature", Parameter.from(vote.signature))
            put("weight", Parameter.from(vote.weight.raw.toLong()))
            put("received_at", Parameter.from(vote.receivedAt.toLocalDateTime()))
            put(
                "persisted_at",
                Parameter.fromOrEmpty(vote.persistedAt?.toLocalDateTime(), LocalDateTime::class.java)
            )
        }

        return row
    }

}

@Component
class VoteDeserializerDBConverter : DBConverter<Row, Vote> {
    override fun convert(row: Row): Vote {
        return Vote(
            hash = AttoHash(row.get("hash", ByteArray::class.java)!!),
            publicKey = AttoPublicKey(row.get("public_key", ByteArray::class.java)!!),
            timestamp = row.get("timestamp", LocalDateTime::class.java)!!.toInstant(),
            signature = AttoSignature(row.get("signature", ByteArray::class.java)!!),
            weight = AttoAmount(row.get("weight", Long::class.javaObjectType)!!.toULong()),
            receivedAt = row.get("received_at", LocalDateTime::class.java)!!.toInstant(),
            persistedAt = row.get("persisted_at", LocalDateTime::class.java)!!.toInstant(),
        )
    }

}