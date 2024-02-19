package atto.node

import atto.node.network.peer.PeerProperties
import atto.node.node.Neighbour
import atto.node.transaction.Transaction
import atto.node.transaction.TransactionConfiguration
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoPrivateKey
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.spring.CucumberContextConfiguration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext

@OptIn(ExperimentalCoroutinesApi::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@CucumberContextConfiguration
class CucumberConfiguration(
    val context: ConfigurableApplicationContext,
    val genesisTransaction: Transaction,
    val thisNode: atto.protocol.AttoNode,
    val peerProperties: PeerProperties,
    val privateKey: AttoPrivateKey,
    val transactionConfiguration: TransactionConfiguration,
    val caches: List<CacheSupport>,
    val repositories: List<AttoRepository>
) {

    init {
//        DebugProbes.install()
    }

    @Before
    fun before() = runBlocking {
        repositories.forEach { it.deleteAll() }

        transactionConfiguration.initializeDatabase(genesisTransaction, thisNode)

        caches.forEach {
            it.clear()
            it.init()
        }

        peerProperties.defaultNodes = mutableSetOf()

        PropertyHolder.clear()
        PropertyHolder.add("THIS", context)
        PropertyHolder.add("THIS", thisNode)
        PropertyHolder.add("THIS", privateKey)
        PropertyHolder.add("THIS", AttoAlgorithm.V1)
        PropertyHolder.add("THIS", privateKey.toPublicKey())
        PropertyHolder.add("THIS", Neighbour(8082U, 8080U))

        NodeHolder.clear(context)
        NodeHolder.add(context)
    }

    @After
    fun after() {
//        DebugProbes.dumpCoroutines()
    }

}