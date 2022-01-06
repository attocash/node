package org.atto.node

import io.cucumber.java.Before
import io.cucumber.spring.CucumberContextConfiguration
import kotlinx.coroutines.test.runTest
import org.atto.commons.AttoPrivateKey
import org.atto.node.network.peer.PeerProperties
import org.atto.node.transaction.TransactionRepository
import org.atto.protocol.Node
import org.atto.protocol.transaction.Transaction
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@CucumberContextConfiguration
class CucumberConfiguration(
    val context: ConfigurableApplicationContext,
    val thisNode: Node,
    val peerProperties: PeerProperties,
    val privateKey: AttoPrivateKey,
    val genesisTransaction: Transaction,
    val caches: List<CacheSupport>,
    val repositories: List<AttoRepository<*, *>>,
    val transactionRepository: TransactionRepository
) {
    @Before
    fun setUp() = runTest {
        repositories.forEach { it.deleteAll() }
        transactionRepository.save(genesisTransaction)

        caches.forEach {
            it.clear()
            it.init()
        }

        peerProperties.defaultNodes.clear()

        PropertyHolder.clear()
        PropertyHolder.add("THIS", context)
        PropertyHolder.add("THIS", thisNode)
        PropertyHolder.add("THIS", privateKey)
        PropertyHolder.add("THIS", privateKey.toPublicKey())
        PropertyHolder.add("THIS", genesisTransaction)

        NodeHolder.clear(context)
        NodeHolder.add(context)
    }
}