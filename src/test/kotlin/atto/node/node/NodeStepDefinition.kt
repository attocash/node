package atto.node.node

import atto.node.NodeHolder
import atto.node.PropertyHolder
import atto.node.network.peer.PeerProperties
import atto.node.transaction.Transaction
import cash.atto.commons.*
import io.cucumber.java.en.Given
import io.r2dbc.spi.Option
import org.springframework.boot.autoconfigure.r2dbc.R2dbcConnectionDetails
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.r2dbc.core.DatabaseClient
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.net.URLClassLoader
import java.util.*
import java.util.concurrent.FutureTask


class NodeStepDefinition(
    private val peerProperties: PeerProperties,
    private val transaction: Transaction,
    private val connectionDetails: R2dbcConnectionDetails,
    private val databaseClient: DatabaseClient
) {

    @Given("^the neighbour node (\\w+)$")
    fun startNeighbour(shortId: String) {
        val nodeName = "Node $shortId"
        val starter = Runnable {
            val tcpPort = randomPort()
            val httpPort = randomPort()

            val classLoader = Thread.currentThread().contextClassLoader
            val applicationClass = arrayOf(classLoader.loadClass(atto.node.Application::class.java.canonicalName))
            val springApplicationBuilder = classLoader.loadClass(SpringApplicationBuilder::class.java.canonicalName)

            val builderInstance = springApplicationBuilder.getConstructor(applicationClass::class.java)
                .newInstance(applicationClass)

            val sql = "DROP DATABASE IF EXISTS $shortId; CREATE DATABASE $shortId"
            databaseClient.sql(sql)
                .fetch()
                .rowsUpdated()
                .block()

            val options = connectionDetails.connectionFactoryOptions
            val driver = options.getRequiredValue(Option.valueOf<String>("driver")) as String
            val host = options.getRequiredValue(Option.valueOf<String>("host")) as String
            val port = options.getRequiredValue(Option.valueOf<Int>("port")) as Int
            val user = options.getRequiredValue(Option.valueOf<String>("user")) as String
            val password = options.getRequiredValue(Option.valueOf<String>("password")) as String

            val privateKey = AttoPrivateKey.generate()

            val args = arrayOf(
                "--spring.application.name=neighbour-atto-node-$shortId",
                "--server.port=$httpPort",
                "--NODE_NAME=$nodeName",
                "--management.server.port=",
                "--atto.test.mysql-container.enabled=false",
                "--spring.r2dbc.url=r2dbc:${driver}://${host}:${port}/${shortId}",
                "--spring.r2dbc.username=${user}",
                "--spring.r2dbc.password=${password}",
                "--atto.node.publicAddress=localhost:${tcpPort}",
                "--server.tcp.port=${tcpPort}",
                "--atto.node.private-key=${privateKey.value.toHex()}",
                "--atto.transaction.genesis=${transaction.toAttoTransaction().toByteBuffer().toHex()}",
            )
            val context = springApplicationBuilder
                .getMethod("run", Array<String>::class.java)
                .invoke(builderInstance, args) as Closeable

            NodeHolder.add(context)

            PropertyHolder.add(shortId, context)
            PropertyHolder.add(shortId, privateKey)
            PropertyHolder.add(shortId, privateKey.toPublicKey())
            PropertyHolder.add(shortId, AttoAlgorithm.V1)
            PropertyHolder.add(shortId, Neighbour(tcpPort, httpPort))
        }

        val futureTask = FutureTask(starter, null)
        val neighbourThread = Thread(futureTask)

        neighbourThread.contextClassLoader = createClassLoader()
        neighbourThread.name = nodeName
        neighbourThread.start()

        futureTask.get()
    }

    @Given("is a default node")
    fun setAsDefaultNode() {
        val neighbour = PropertyHolder[Neighbour::class.java]
        peerProperties.defaultNodes.add("localhost:${neighbour.tcpPort}")
    }

    private fun randomPort(): UShort {
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port.toUShort()
    }

    private fun createClassLoader(): URLClassLoader {
        val urlList = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .asSequence()
            .map { if (it.endsWith(".jar")) it else "$it/" }
            .map { if (it.startsWith("/")) it else "/$it" }
            .map { File(it).toURI().toURL() }
            .toList()
        val urlArray = Array(urlList.size) { urlList[it] }
        return URLClassLoader(urlArray, ClassLoader.getSystemClassLoader().parent)
    }
}