package org.atto.node.node

import io.cucumber.java.en.Given
import mu.KotlinLogging
import org.atto.commons.*
import org.atto.node.Application
import org.atto.node.NodeHolder
import org.atto.node.PropertyHolder
import org.atto.node.network.peer.PeerProperties
import org.atto.node.transaction.Transaction
import org.springframework.boot.builder.SpringApplicationBuilder
import java.io.Closeable
import java.net.ServerSocket
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.FutureTask


class NodeStepDefinition(
    private val peerProperties: PeerProperties,
    private val transaction: Transaction
) {
    private val logger = KotlinLogging.logger {}

    @Given("^the neighbour node (\\w+)$")
    fun startNeighbour(shortId: String) {
        val starter = Runnable {
            val tcpPort = randomPort()
            val httpPort = randomPort()

            val classLoader = Thread.currentThread().contextClassLoader
            val applicationClass = arrayOf(classLoader.loadClass(Application::class.java.canonicalName))
            val springApplicationBuilder = classLoader.loadClass(SpringApplicationBuilder::class.java.canonicalName)

            val builderInstance = springApplicationBuilder.getConstructor(applicationClass::class.java)
                .newInstance(applicationClass)

            val privateKey = AttoPrivateKey.generate()

            val args = arrayOf(
                "--spring.application.name=neighbour-atto-node-$shortId",
                "--server.port=$httpPort",
                "--management.server.port=",
                "--atto.node.publicAddress=localhost:${tcpPort}",
                "--server.tcp.port=${tcpPort}",
                "--ATTO_DB_NAME=atto-neighbour${shortId}",
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
            PropertyHolder.add(shortId, Neighbour(tcpPort, httpPort))
        }

        val futureTask = FutureTask(starter, null)
        val neighbourThread = Thread(futureTask)

        neighbourThread.contextClassLoader = createClassLoader()
        neighbourThread.name = "Node $shortId"
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
            .split(System.getProperty("path.separator"))
            .asSequence()
            .map { if (it.endsWith(".jar")) it else "$it/" }
            .map { if (it.startsWith("/")) it else "/$it" }
            .map { URL("file:$it") }
            .toList()
        val urlArray = Array(urlList.size) { urlList[it] }
        return URLClassLoader(urlArray, ClassLoader.getSystemClassLoader().parent)
    }
}