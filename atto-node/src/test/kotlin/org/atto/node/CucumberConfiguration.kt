package org.atto.node

import io.cucumber.java.Before
import io.cucumber.spring.CucumberContextConfiguration
import kotlinx.coroutines.runBlocking
import org.atto.commons.AttoPrivateKey
import org.atto.node.network.peer.PeerProperties
import org.atto.node.node.Neighbour
import org.atto.node.transaction.TransactionConfiguration
import org.atto.node.transaction.TransactionGenesisInitializer
import org.atto.protocol.AttoNode
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@CucumberContextConfiguration
class CucumberConfiguration(
    val context: ConfigurableApplicationContext,
    val thisNode: AttoNode,
    val peerProperties: PeerProperties,
    val privateKey: AttoPrivateKey,
    val genesisInitializer: TransactionGenesisInitializer,
    val caches: List<CacheSupport>,
    val repositories: List<AttoRepository>
) {
    @Before
    fun setUp() = runBlocking {
        repositories.forEach { it.deleteAll() }

        genesisInitializer.init()

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
        PropertyHolder.add("THIS", Neighbour(8313U, 8080U))

        NodeHolder.clear(context)
        NodeHolder.add(context)
    }

}