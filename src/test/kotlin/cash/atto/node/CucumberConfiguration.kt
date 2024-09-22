package cash.atto.node

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoPrivateKey
import cash.atto.node.network.NetworkProperties
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionConfiguration
import cash.atto.protocol.AttoNode
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.spring.CucumberContextConfiguration
import kotlinx.coroutines.runBlocking
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@CucumberContextConfiguration
class CucumberConfiguration(
    val context: ConfigurableApplicationContext,
    val genesisTransaction: Transaction,
    val thisNode: AttoNode,
    val networkProperties: NetworkProperties,
    val privateKey: AttoPrivateKey,
    val transactionConfiguration: TransactionConfiguration,
    val caches: List<CacheSupport>,
    val repositories: List<AttoRepository>,
) {
    init {
//        DebugProbes.install()
    }

    @Before
    fun before() =
        runBlocking {
            repositories.forEach { it.deleteAll() }

            transactionConfiguration.initializeDatabase(genesisTransaction, thisNode)

            caches.forEach {
                it.clear()
                it.init()
            }

            networkProperties.defaultNodes = mutableSetOf()

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
