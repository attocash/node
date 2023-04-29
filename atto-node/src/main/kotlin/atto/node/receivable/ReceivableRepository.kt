package atto.node.receivable

import atto.commons.AttoHash
import atto.node.AttoRepository
import org.springframework.data.repository.kotlin.CoroutineCrudRepository


interface ReceivableRepository : CoroutineCrudRepository<Receivable, AttoHash>, AttoRepository {

}