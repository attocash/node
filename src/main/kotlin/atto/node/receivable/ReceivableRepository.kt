package atto.node.receivable

import atto.node.AttoRepository
import cash.atto.commons.AttoHash
import org.springframework.data.repository.kotlin.CoroutineCrudRepository


interface ReceivableRepository : CoroutineCrudRepository<Receivable, AttoHash>, AttoRepository {

}