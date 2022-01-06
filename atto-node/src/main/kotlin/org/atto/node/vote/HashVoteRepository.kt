package org.atto.node.vote

import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.reactor.awaitSingle
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.commons.AttoSignature
import org.atto.node.AttoRepository
import org.atto.protocol.vote.HashVote
import org.atto.protocol.vote.Vote
import org.springframework.context.annotation.DependsOn
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.function.BiFunction


@Repository
@DependsOn("flyway")
class HashVoteRepository(private val client: DatabaseClient) : AttoRepository<HashVote, Pair<AttoHash, AttoPublicKey>> {
    private val mappingFunction: BiFunction<Row, RowMetadata, HashVote> =
        BiFunction<Row, RowMetadata, HashVote> { row: Row, _: RowMetadata? ->
            val vote = Vote(
                publicKey = AttoPublicKey(row["publicKey", ByteArray::class.java]!!),
                timestamp = Instant.ofEpochMilli(row["timestamp", java.lang.Long::class.java]!!.toLong()),
                signature = AttoSignature(row["signature", ByteArray::class.java]!!),

                )
            HashVote(
                hash = AttoHash(row["hash", ByteArray::class.java]!!),
                vote = vote,
                receivedTimestamp = Instant.ofEpochMilli(row["receivedTimestamp", java.lang.Long::class.java]!!.toLong())
            )
        }

    private val insertStatement = """
            INSERT IGNORE INTO votes
                        (
                                    hash,
                                    publicKey,
                                    timestamp,
                                    signature,
                                    receivedTimestamp
                        )
                        VALUES
                        (
                                    :hash,
                                    :publicKey,
                                    :timestamp,
                                    :signature,
                                    :receivedTimestamp
                        )
    """.trimIndent()

    override suspend fun save(entity: HashVote): HashVote {
        val hashVote = entity
        client.sql(insertStatement)
            .bind("hash", hashVote.hash.value)
            .bind("publicKey", hashVote.vote.publicKey.value)
            .bind("timestamp", hashVote.vote.timestamp.toEpochMilli())
            .bind("signature", hashVote.vote.signature.value)
            .bind("receivedTimestamp", hashVote.receivedTimestamp.toEpochMilli())
            .fetch()
            .awaitRowsUpdated()
        return hashVote
    }

    suspend fun findByHash(hash: AttoHash): List<HashVote> {
        return client.sql("SELECT * FROM votes WHERE hash = $1")
            .bind(0, hash.value)
            .map(mappingFunction)
            .all()
            .collectList()
            .awaitSingle()
    }

    suspend fun findLatestVotes(): List<HashVote> {
        val sql = """
            |SELECT *
            |FROM votes v1
            |WHERE receivedTimestamp = (select MAX(receivedTimestamp) FROM votes v2 where v1.publicKey = v2.publicKey)
            |""".trimMargin()

        return client.sql(sql)
            .map(mappingFunction)
            .all()
            .collectList()
            .awaitSingle()
    }

    override suspend fun findById(id: Pair<AttoHash, AttoPublicKey>): HashVote? {
        return client.sql("SELECT * FROM votes WHERE hash = $1 and publicKey = $2")
            .bind(0, id.first.value)
            .bind(1, id.second.value)
            .map(mappingFunction)
            .awaitSingle()
    }

    override suspend fun deleteAll(): Int {
        return client.sql("DELETE FROM votes")
            .fetch()
            .awaitRowsUpdated()
    }
}