package cash.atto.node

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoSigner
import cash.atto.node.network.ChallengeStore
import cash.atto.node.network.NetworkProperties
import cash.atto.node.network.NodeConnectionManager
import cash.atto.node.signature.MeteredAttoSigner
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionConfiguration
import cash.atto.protocol.AttoNode
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.spring.CucumberContextConfiguration
import kotlinx.coroutines.runBlocking
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ConfigurableApplicationContext

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0"],
)
@CucumberContextConfiguration
class CucumberConfiguration(
    val context: ConfigurableApplicationContext,
    val genesisTransaction: Transaction,
    val thisNode: AttoNode,
    val networkProperties: NetworkProperties,
    val signer: AttoSigner,
    val transactionConfiguration: TransactionConfiguration,
    val caches: List<CacheSupport>,
    val repositories: List<AttoRepository>,
    val connectionManager: NodeConnectionManager,
) {
    @LocalServerPort
    var httpPort: Int = 0

    @LocalManagementPort
    var managementPort: Int = 0

    init {
//        DebugProbes.install()
    }

    @Before
    fun before() =
        runBlocking {
            networkProperties.defaultNodes = mutableSetOf()

            caches.forEach {
                it.clear()
            }

            NodeHolder.clear(except = context)
            NodeHolder.add(context)

            Waiter.waitUntilTrue { connectionManager.connectionCount == 0 }

            repositories.forEach { it.deleteAll() }

            transactionConfiguration.initializeDatabase(genesisTransaction, thisNode)

            caches.forEach {
                it.clear()
                it.init()
            }

            PropertyHolder.clear()
            PropertyHolder.add("THIS", context)
            PropertyHolder.add("THIS", thisNode)
            PropertyHolder.add("THIS", (signer as? MeteredAttoSigner)?.delegate ?: signer)
            PropertyHolder.add("THIS", AttoAlgorithm.V1)
            PropertyHolder.add("THIS", signer.publicKey)
            PropertyHolder.add(
                "THIS",
                Neighbour(thisNode.publicUri.port.toUShort(), httpPort.toUShort(), managementPort = managementPort.toUShort()),
            )

            ChallengeStore.clear()
        }

    @After
    fun after() {
        networkProperties.defaultNodes.clear()
        NodeHolder.clear(except = context)
    }
}
