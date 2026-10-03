package cash.atto.node

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.toHex
import cash.atto.commons.toPublicKeyBlocking
import cash.atto.commons.toSignerBlocking
import cash.atto.node.network.NetworkProperties
import cash.atto.node.transaction.Transaction
import io.cucumber.java.en.Given
import io.r2dbc.spi.Option
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.r2dbc.autoconfigure.R2dbcConnectionDetails
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.r2dbc.core.DatabaseClient
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.PullPolicy
import org.testcontainers.utility.DockerImageName
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.net.URLClassLoader
import java.util.concurrent.FutureTask

class NodeStepDefinition(
    private val networkProperties: NetworkProperties,
    private val transaction: Transaction,
    private val connectionDetails: R2dbcConnectionDetails,
    private val databaseClient: DatabaseClient,
    @Value("\${atto.test.neighbour-mode:classpath}") private val neighbourMode: String,
) {
    @Given("^the neighbour node (\\w+)$")
    fun startNeighbour(shortId: String) {
        val nodeName = "Node $shortId"
        val ports = randomPorts()
        val websocketPort = ports[0]
        val httpPort = ports[1]

        val sql = "DROP DATABASE IF EXISTS $shortId; CREATE DATABASE $shortId"
        databaseClient
            .sql(sql)
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

        val args =
            arrayOf(
                "--spring.application.name=neighbour-atto-node-$shortId",
                "--server.port=$httpPort",
                "--NODE_NAME=$nodeName",
                "--management.server.port=",
                "--atto.test.mysql-container.enabled=false",
                "--spring.r2dbc.url=r2dbc:$driver://$host:$port/$shortId",
                "--spring.r2dbc.username=$user",
                "--spring.r2dbc.password=$password",
                "--spring.flyway.url=jdbc:$driver://$host:$port/$shortId",
                "--spring.flyway.user=$user",
                "--spring.flyway.password=$password",
                "--atto.node.public-uri=ws://localhost:$websocketPort",
                "--websocket.port=$websocketPort",
                "--atto.signer.key=${privateKey.value.toHex()}",
                "--atto.transaction.genesis=${transaction.toAttoTransaction().toBuffer().toHex()}",
            )

        when (neighbourMode) {
            CLASSPATH_MODE -> startClasspathNode(nodeName, args)
            LIVE_MODE -> startLiveNode(args)
            else -> error("Unsupported neighbour mode: $neighbourMode")
        }

        PropertyHolder.add(shortId, privateKey.toSignerBlocking())
        PropertyHolder.add(shortId, privateKey.toPublicKeyBlocking())
        PropertyHolder.add(shortId, AttoAlgorithm.V1)
        PropertyHolder.add(shortId, Neighbour(websocketPort, httpPort))
    }

    @Given("is a default node")
    fun setAsDefaultNode() {
        val neighbour = PropertyHolder[Neighbour::class.java]
        networkProperties.defaultNodes.add("ws://localhost:${neighbour.websocketPort}")
    }

    private fun startClasspathNode(
        nodeName: String,
        args: Array<String>,
    ) {
        val classLoader = createClassLoader()
        val starter =
            Runnable {
                Thread.currentThread().contextClassLoader = classLoader
                val context =
                    SpringApplicationBuilder(Application::class.java)
                        .resourceLoader(DefaultResourceLoader(classLoader))
                        .run(*args) as Closeable

                NodeHolder.add(context, classLoader)
            }

        val futureTask = FutureTask(starter, null)
        val neighbourThread = Thread(futureTask)
        neighbourThread.contextClassLoader = classLoader
        neighbourThread.name = nodeName
        neighbourThread.start()
        futureTask.get()
    }

    private fun startLiveNode(args: Array<String>) {
        val container = GenericContainer<Nothing>(DockerImageName.parse(LIVE_NODE_IMAGE))
        container.withImagePullPolicy(PullPolicy.alwaysPull())
        container.withNetworkMode("host")
        container.withEnv("SPRING_PROFILES_ACTIVE", "default,json")
        container.withCommand(*args)
        container.start()
        NodeHolder.add(Closeable { container.stop() }, null)
    }

    private fun randomPorts(count: Int = 2): List<UShort> {
        val sockets = (0 until count).map { ServerSocket(0) }
        val ports = sockets.map { it.localPort.toUShort() }
        sockets.forEach { it.close() }
        return ports
    }

    private fun createClassLoader(): URLClassLoader {
        val urlList =
            System
                .getProperty("java.class.path")
                .split(File.pathSeparator)
                .asSequence()
                .map { if (it.endsWith(".jar")) it else "$it/" }
                .map { if (it.startsWith("/")) it else "/$it" }
                .map { File(it).toURI().toURL() }
                .toList()
        val urlArray = Array(urlList.size) { urlList[it] }
        return URLClassLoader(urlArray, ClassLoader.getSystemClassLoader())
    }

    private companion object {
        const val CLASSPATH_MODE = "classpath"
        const val LIVE_MODE = "live"
        const val LIVE_NODE_IMAGE = "ghcr.io/attocash/node:live"
    }
}
