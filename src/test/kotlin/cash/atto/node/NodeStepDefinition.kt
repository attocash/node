package cash.atto.node

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.toHex
import cash.atto.commons.toPublicKeyBlocking
import cash.atto.commons.toSignerBlocking
import cash.atto.node.network.NetworkProperties
import cash.atto.node.transaction.Transaction
import io.cucumber.java.en.Given
import io.github.oshai.kotlinlogging.KotlinLogging
import io.r2dbc.spi.Option
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.r2dbc.autoconfigure.R2dbcConnectionDetails
import org.springframework.r2dbc.core.DatabaseClient
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.PullPolicy
import org.testcontainers.images.RemoteDockerImage
import org.testcontainers.utility.DockerImageName
import java.io.Closeable
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.util.concurrent.FutureTask

class NodeStepDefinition(
    private val networkProperties: NetworkProperties,
    private val transaction: Transaction,
    private val connectionDetails: R2dbcConnectionDetails,
    private val databaseClient: DatabaseClient,
    @Value("\${atto.test.neighbour-mode:classpath}") private val neighbourMode: String,
) {
    private val logger = KotlinLogging.logger {}

    @Given("^the neighbour node (\\w+)$")
    fun startNeighbour(shortId: String) = startNeighbour(shortId, emptyList())

    fun startNeighbour(
        shortId: String,
        defaultNodes: List<String>,
    ) {
        require(neighbourMode == CLASSPATH_MODE || neighbourMode == LIVE_MODE) { "Unsupported neighbour mode: $neighbourMode" }
        logger.info { "Starting neighbour $shortId with backend $neighbourMode" }
        val nodeName = "Node $shortId"
        val ports = randomPorts(3)
        val websocketPort = ports[0]
        val httpPort = ports[1]
        val managementPort = ports[2]

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
                "--management.server.port=$managementPort",
                "--management.endpoints.web.base-path=/",
                "--management.endpoints.web.exposure.include=health,info,metrics,prometheus",
                "--management.endpoint.health.probes.enabled=true",
                "--atto.test.mysql-container.enabled=false",
                "--spring.r2dbc.url=r2dbc:$driver://$host:$port/$shortId",
                "--spring.r2dbc.username=$user",
                "--spring.r2dbc.password=$password",
                "--spring.flyway.url=jdbc:$driver://$host:$port/$shortId",
                "--spring.flyway.user=$user",
                "--spring.flyway.password=$password",
                "--atto.node.public-uri=ws://localhost:$websocketPort",
                "--atto.node.network=LOCAL",
                "--atto.node.force-voter=true",
                "--atto.node.force-historical=true",
                "--atto.network.loopback-blocked=false",
                "--atto.vote.weight.minimal-confirmation-weight=9000000000000000000",
                "--websocket.port=$websocketPort",
                "--atto.signer.key=${privateKey.value.toHex()}",
                "--atto.transaction.genesis=${transaction.toAttoTransaction().toBuffer().toHex()}",
            ) + defaultNodes.mapIndexed { index, uri -> "--atto.network.default-nodes[$index]=$uri" }

        when (neighbourMode) {
            CLASSPATH_MODE -> startClasspathNode(nodeName, args)
            LIVE_MODE -> startLiveNode(shortId, managementPort, args)
            else -> error("Unsupported neighbour mode: $neighbourMode")
        }

        PropertyHolder.add(shortId, privateKey.toSignerBlocking())
        PropertyHolder.add(shortId, privateKey.toPublicKeyBlocking())
        PropertyHolder.add(shortId, AttoAlgorithm.V1)
        PropertyHolder.add(
            shortId,
            Neighbour(websocketPort, httpPort, released = neighbourMode == LIVE_MODE, managementPort = managementPort),
        )
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
        val starter =
            Runnable {
                val context = SpringApplicationBuilder(Application::class.java).run(*args) as Closeable
                NodeHolder.add(context, null)
            }

        val futureTask = FutureTask(starter, null)
        val neighbourThread = Thread(futureTask)
        neighbourThread.name = nodeName
        neighbourThread.start()
        futureTask.get()
    }

    private fun startLiveNode(
        shortId: String,
        managementPort: UShort,
        args: Array<String>,
    ) {
        val image = releasedImage
        val logFile = File("build/reports/released-neighbours/$shortId-${System.nanoTime()}.log")
        logFile.parentFile.mkdirs()
        logFile.writeText("${image.description}\n")
        logger.info { "Starting released neighbour $shortId: ${image.description}; logs=${logFile.absolutePath}" }

        val container = GenericContainer<Nothing>(image.name)
        container.withNetworkMode("host")
        container.withEnv("SPRING_PROFILES_ACTIVE", "default,json")
        container.withEnv("MANAGEMENT_SERVER_PORT", managementPort.toString())
        container.withCommand(*args)
        container.withLogConsumer { frame -> logFile.appendText(frame.utf8String) }
        val cleanup = Closeable { container.stop() }
        NodeHolder.add(cleanup, null)
        try {
            container.start()
            waitForReadiness(container, managementPort)
        } catch (exception: Exception) {
            runCatching { cleanup.close() }.onFailure { exception.addSuppressed(it) }
            throw IllegalStateException(
                "Released neighbour $shortId failed to start (${image.description}); logs=${logFile.absolutePath}",
                exception,
            )
        }
    }

    private fun waitForReadiness(
        container: GenericContainer<*>,
        managementPort: UShort,
    ) {
        val deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos()
        val endpoint = URI("http://localhost:$managementPort/health/readiness").toURL()
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            check(container.isRunning) { "Released neighbour exited before becoming ready" }
            try {
                val connection = endpoint.openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 1_000
                    connection.readTimeout = 1_000
                    if (connection.responseCode == 200) {
                        return
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (exception: Exception) {
                lastFailure = exception
            }
            Thread.sleep(100)
        }
        throw IllegalStateException("Released neighbour did not become ready at $endpoint within 180s", lastFailure)
    }

    private fun randomPorts(count: Int = 2): List<UShort> {
        val sockets = (0 until count).map { ServerSocket(0) }
        val ports = sockets.map { it.localPort.toUShort() }
        sockets.forEach { it.close() }
        return ports
    }

    private data class ReleasedImage(
        val name: DockerImageName,
        val description: String,
    )

    private companion object {
        const val CLASSPATH_MODE = "classpath"
        const val LIVE_MODE = "live"
        const val LIVE_NODE_IMAGE = "ghcr.io/attocash/node:live"

        val releasedImage: ReleasedImage by lazy {
            val reference =
                RemoteDockerImage(DockerImageName.parse(LIVE_NODE_IMAGE))
                    .withImagePullPolicy(PullPolicy.alwaysPull())
                    .get()
            val image =
                DockerClientFactory
                    .instance()
                    .client()
                    .inspectImageCmd(reference)
                    .exec()
            val digest =
                image.repoDigests.orEmpty().firstOrNull { it.startsWith("ghcr.io/attocash/node@") }
                    ?: error("Pulled $LIVE_NODE_IMAGE has no repository digest")
            val version =
                image.config
                    ?.env
                    .orEmpty()
                    .firstOrNull { it.startsWith("APPLICATION_VERSION=") }
            val labels = image.config?.labels.orEmpty()
            ReleasedImage(
                DockerImageName.parse(digest),
                "digest=$digest imageId=${image.id} $version " +
                    "version=${labels["org.opencontainers.image.version"]} revision=${labels["org.opencontainers.image.revision"]}",
            )
        }
    }
}
