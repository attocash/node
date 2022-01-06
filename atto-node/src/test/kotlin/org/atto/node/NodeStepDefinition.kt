package org.atto.node

import io.cucumber.java.en.Given
import org.atto.commons.*
import org.atto.node.network.peer.PeerProperties
import org.atto.protocol.transaction.Transaction
import org.springframework.boot.builder.SpringApplicationBuilder
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLClassLoader
import java.util.concurrent.CountDownLatch


class NodeStepDefinition(
    private val peerProperties: PeerProperties,
    private val transaction: Transaction
) {
    @Given("^the neighbour node (\\w+)$")
    fun startNeighbour(shortId: String) {
        val latch = CountDownLatch(1)
        val neighbourThread = Thread {
            try {
                val tcpPort = randomPort()
                val httpPort = randomPort()

                val classLoader = Thread.currentThread().contextClassLoader
                val applicationClass = arrayOf(classLoader.loadClass(Application::class.java.canonicalName))
                val springApplicationBuilder = classLoader.loadClass(SpringApplicationBuilder::class.java.canonicalName)

                val builderInstance = springApplicationBuilder.getConstructor(applicationClass::class.java)
                    .newInstance(applicationClass)

                val privateKey = AttoSeeds.generateSeed().toPrivateKey(0U)

                val args = arrayOf(
                    "--spring.application.name=neighbour-atto-node-$shortId",
                    "--server.port=$httpPort",
                    "--atto.node.publicAddress=localhost:${tcpPort}",
                    "--ATTO_DB_NAME=atto-neighbour${shortId}",
                    "--atto.node.private-key=${privateKey.value.toHex()}",
                    "--atto.transaction.genesis=${transaction.toByteArray().toHex()}",
                )
                val context = springApplicationBuilder
                    .getMethod("run", Array<String>::class.java)
                    .invoke(builderInstance, args) as Closeable

                NodeHolder.add(context)

                PropertyHolder.add(shortId, context)
                PropertyHolder.add(shortId, privateKey)
                PropertyHolder.add(shortId, privateKey.toPublicKey())
                PropertyHolder.add(shortId, InetSocketAddress("localhost", tcpPort))
            } finally {
                latch.countDown()
            }
        }
        neighbourThread.contextClassLoader = createClassLoader()
        neighbourThread.start()
        latch.await()
    }

    @Given("is a default node")
    fun setAsDefaultNode() {
        val neighbourSocketAddress = PropertyHolder[InetSocketAddress::class.java]
        peerProperties.defaultNodes.add("localhost:${neighbourSocketAddress.port}")
    }

    private fun randomPort(): Int {
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    private fun createClassLoader(): URLClassLoader {
        val path = System.getProperty("java.class.path").replace("\\", "/")
        val urlList = path.split(";").asSequence()
            .map { if (it.endsWith(".jar")) it else "$it/" }
            .map { URL("file:/$it") }
            .toList()
        val urlArray = Array(urlList.size) { urlList[it] }
        return URLClassLoader(urlArray, Thread.currentThread().contextClassLoader.parent)
    }
}