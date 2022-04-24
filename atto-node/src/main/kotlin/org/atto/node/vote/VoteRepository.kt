package org.atto.node.vote

import org.atto.commons.AttoSignature
import org.atto.node.AttoRepository
import org.springframework.data.repository.Repository


interface VoteRepository : Repository<AttoSignature, Vote>, AttoRepository {

    suspend fun save(vote: Vote)

    suspend fun saveAll(votes: Collection<Vote>)

    suspend fun findLatest(): List<Vote>

}