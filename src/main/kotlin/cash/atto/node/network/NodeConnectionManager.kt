package cash.atto.node.network

import cash.atto.commons.AttoPublicKey
import cash.atto.commons.toHex
import cash.atto.node.CacheSupport
import cash.atto.node.EventPublisher
import cash.atto.protocol.AttoKeepAlive
import cash.atto.protocol.AttoMessage
import cash.atto.protocol.AttoNode
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import com.github.benmanes.caffeine.cache.Ticker
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Component
class NodeConnectionManager(
    private val thisNode: AttoNode,
    private val messagePublisher: NetworkMessagePublisher,
    private val eventPublisher: EventPublisher,
    private val networkProperties: NetworkProperties,
    meterRegistry: MeterRegistry,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}
    private val admissionLock = Any()
    private val reservationId = AtomicLong()
    private val pendingReservations = HashMap<Long, PeerSessionReservation>()
    private val trustedPublicUris =
        networkProperties.defaultNodes.mapNotNullTo(HashSet()) { value ->
            runCatching { URI(value) }
                .getOrNull()
                ?.takeIf { it.host != null && (it.scheme == "ws" || it.scheme == "wss") && it != thisNode.publicUri }
        }
    private val rejectionCounters =
        PeerSessionAdmissionRejection.entries.associateWith { rejection ->
            Counter
                .builder("network.peers.admission.rejections")
                .description("Count of rejected peer session admissions")
                .tag("reason", rejection.metricTag)
                .register(meterRegistry)
        }

    init {
        require(networkProperties.maxPeerSessionsPerAddress <= networkProperties.maxPeerSessionsPerPrefix) {
            "maxPeerSessionsPerAddress must not exceed maxPeerSessionsPerPrefix"
        }
        require(networkProperties.maxPeerSessionsPerPrefix <= networkProperties.maxActivePeerSessions) {
            "maxPeerSessionsPerPrefix must not exceed maxActivePeerSessions"
        }
        require(networkProperties.maxPeerSessionsPerPublicKey <= networkProperties.maxActivePeerSessions) {
            "maxPeerSessionsPerPublicKey must not exceed maxActivePeerSessions"
        }
        require(trustedPublicUris.size <= networkProperties.maxActivePeerSessions) {
            "The number of default nodes must not exceed maxActivePeerSessions"
        }
    }

    private var inactivityTimeout = Duration.ofSeconds(60)
    private var cacheTicker = Ticker.systemTicker()

    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher() + supervisorJob)

    private val connectionCacheDelegate =
        lazy {
            Caffeine
                .newBuilder()
                .executor(Runnable::run)
                .scheduler(Scheduler.systemScheduler())
                .ticker(cacheTicker)
                .expireAfterWrite(inactivityTimeout)
                .removalListener { _: URI?, connection: NodeConnection?, cause ->
                    if (connection != null) {
                        logger.trace { "Removing connection to ${connection.node.publicUri} because of $cause" }
                        try {
                            eventPublisher.publish(NodeDisconnected(connection.connectionInetSocketAddress, connection.node))
                        } finally {
                            scope.launch { connection.disconnect() }
                        }
                    }
                }.build<URI, NodeConnection>()
        }

    private val connectionCache
        get() = connectionCacheDelegate.value

    private val connectionMap
        get() = connectionCache.asMap()

    internal fun configureExpirationForTesting(
        inactivityTimeout: Duration,
        cacheTicker: Ticker,
    ) {
        check(!connectionCacheDelegate.isInitialized())
        this.inactivityTimeout = inactivityTimeout
        this.cacheTicker = cacheTicker
    }

    val connectionCount: Int
        get() = connectionMap.size

    val pendingConnectionCount: Int
        get() = synchronized(admissionLock) { pendingReservations.size }

    internal val admittedSessionCount: Int
        get() = synchronized(admissionLock) { connectionMap.size + pendingReservations.size }

    val connectedPublicKeys: Set<AttoPublicKey>
        get() =
            connectionMap
                .values
                .mapTo(HashSet()) { it.node.publicKey }

    override fun clear() {
        synchronized(admissionLock) {
            pendingReservations.clear()
            connectionMap.clear()
        }
        runBlocking {
            supervisorJob.children.toList().joinAll()
        }
    }

    @PreDestroy
    fun stop() {
        logger.info { "Node Connection Manager is stopping..." }
        clear()
        scope.cancel()
    }

    fun isConnected(publicUri: URI): Boolean = connectionMap.containsKey(publicUri)

    internal fun reserveInbound(
        publicUri: URI,
        address: InetAddress,
    ): PeerSessionReservationResult = reserve(publicUri, trusted = false, address.toPeerAddressIdentity())

    internal fun reserveOutbound(publicUri: URI): PeerSessionReservationResult =
        reserve(publicUri, trusted = publicUri in trustedPublicUris, addressIdentity = null)

    internal fun release(reservation: PeerSessionReservation) {
        synchronized(admissionLock) {
            pendingReservations.remove(reservation.id, reservation)
        }
    }

    internal suspend fun manage(
        reservation: PeerSessionReservation,
        node: AttoNode,
        connectionSocketAddress: InetSocketAddress,
        session: WebSocketSession,
    ) = manage(reservation, node, connectionSocketAddress, KtorPeerWebSocketSession(session))

    internal suspend fun manage(
        reservation: PeerSessionReservation,
        node: AttoNode,
        connectionSocketAddress: InetSocketAddress,
        session: PeerWebSocketSession,
    ) {
        val publicUri = node.publicUri
        val address = connectionSocketAddress.address
        val addressIdentity = address?.toPeerAddressIdentity()
        val connection = NodeConnection(node, connectionSocketAddress, session, addressIdentity, reservation.trusted)

        val rejectionReason =
            synchronized(admissionLock) {
                when {
                    pendingReservations.remove(reservation.id, reservation).not() -> {
                        PeerSessionAdmissionRejection.INVALID_RESERVATION
                    }

                    reservation.publicUri != publicUri -> {
                        PeerSessionAdmissionRejection.PUBLIC_URI_MISMATCH
                    }

                    addressIdentity == null -> {
                        PeerSessionAdmissionRejection.UNRESOLVED_ADDRESS
                    }

                    connectionMap.containsKey(publicUri) -> {
                        PeerSessionAdmissionRejection.DUPLICATE_PUBLIC_URI
                    }

                    activePublicKeyCount(node.publicKey) >= networkProperties.maxPeerSessionsPerPublicKey -> {
                        PeerSessionAdmissionRejection.PUBLIC_KEY_CAPACITY
                    }

                    activeAddressCount(addressIdentity) >= networkProperties.maxPeerSessionsPerAddress -> {
                        PeerSessionAdmissionRejection.ADDRESS_CAPACITY
                    }

                    activePrefixCount(addressIdentity) >= networkProperties.maxPeerSessionsPerPrefix -> {
                        PeerSessionAdmissionRejection.PREFIX_CAPACITY
                    }

                    else -> {
                        connectionMap[publicUri] = connection
                        null
                    }
                }
            }
        if (rejectionReason != null) {
            reject(rejectionReason)
            logger.debug { "Rejecting connection to $publicUri: ${rejectionReason.closeReason.message}" }
            connection.disconnect(rejectionReason.closeReason)
            return
        }

        try {
            eventPublisher.publish(NodeConnected(connectionSocketAddress, node))
        } catch (exception: Exception) {
            remove(publicUri, connection)
            throw exception
        }

        try {
            connection
                .incomingFlow()
                .onCompletion {
                    val cause = it?.takeUnless { it is CancellationException }
                    logger.trace(cause) { "Inbound message stream from ${node.publicUri} completed" }
                    remove(publicUri, connection)
                }.collect {
                    val message = NetworkSerializer.deserialize(it, thisNode.network)

                    if (message == null) {
                        logger.debug { "Received invalid message from $publicUri ${it.toHex()}" }
                        remove(publicUri, connection)
                        return@collect
                    }

                    if (message is AttoKeepAlive) {
                        connectionMap.replace(publicUri, connection, connection)
                    }

                    logger.trace { "Received from $publicUri $message ${it.toHex()}" }

                    val networkMessage =
                        InboundNetworkMessage(
                            MessageSource.WEBSOCKET,
                            publicUri,
                            connectionSocketAddress,
                            message,
                        )

                    messagePublisher.publish(networkMessage)
                }
        } catch (e: Exception) {
            remove(publicUri, connection)
            throw e
        } finally {
            connection.disconnect()
        }
    }

    suspend fun send(
        publicUri: URI,
        message: AttoMessage,
    ) {
        val serialized = NetworkSerializer.serialize(message)

        logger.trace { "Sending to $publicUri $message ${serialized.toHex()}" }
        val connection = connectionMap[publicUri] ?: return
        try {
            connection.send(serialized)
        } catch (e: Exception) {
            logger.debug(e) { "Exception during sending to $publicUri $message ${serialized.toHex()}" }
            remove(publicUri, connection)
        }
    }

    @EventListener
    suspend fun send(networkMessage: DirectNetworkMessage<*>) {
        val publicUri = networkMessage.publicUri
        val message = networkMessage.payload

        send(publicUri, message)
    }

    @EventListener
    suspend fun send(networkMessage: BroadcastNetworkMessage<*>) {
        val message = networkMessage.payload

        logger.trace { "Sending $networkMessage" }

        connectionMap.values
            .asSequence()
            .filter { networkMessage.accepts(it.node.publicUri, it.node) }
            .map { it.node.publicUri }
            .toList()
            .forEach { send(it, message) }
    }

    @EventListener
    fun ban(event: NodeBanned) {
        connectionMap
            .entries
            .filter { it.value.connectionInetSocketAddress.address == event.address }
            .forEach { (uri, connection) ->
                logger.info { "Disconnecting $uri due to ban of ${event.address}" }
                remove(uri, connection)
            }
    }

    @Scheduled(fixedRate = 10_000)
    suspend fun keepAlive() {
        val sample = connectionMap.toMap().values.randomOrNull()
        val message = AttoKeepAlive(sample?.node?.publicUri)
        send(BroadcastNetworkMessage(strategy = BroadcastStrategy.EVERYONE, payload = message))
    }

    private inner class NodeConnection(
        val node: AttoNode,
        val connectionInetSocketAddress: InetSocketAddress,
        val session: PeerWebSocketSession,
        val addressIdentity: PeerAddressIdentity?,
        val trusted: Boolean,
    ) {
        private val disconnected = AtomicBoolean()

        fun incomingFlow(): Flow<ByteArray> =
            session
                .incoming
                .onStart { logger.info { "Connected to ${node.publicUri} ${node.publicKey}" } }
                .onCompletion { cause ->
                    logger.info(cause?.takeUnless { it is CancellationException }) { "Disconnected from ${node.publicUri}" }
                }

        suspend fun disconnect(reason: CloseReason? = null) {
            if (!disconnected.compareAndSet(false, true)) {
                return
            }
            try {
                if (reason == null) {
                    session.close()
                } else {
                    session.close(reason)
                }
            } catch (e: Exception) {
                logger.trace(e) { "Exception during graceful close of ${node.publicUri}, cancelling session" }
                session.cancel()
            }
        }

        suspend fun send(message: ByteArray) {
            session.send(message)
        }
    }

    internal fun cleanUpExpiredConnections() {
        connectionCache.cleanUp()
    }

    private fun reserve(
        publicUri: URI,
        trusted: Boolean,
        addressIdentity: PeerAddressIdentity?,
    ): PeerSessionReservationResult =
        synchronized(admissionLock) {
            val rejection =
                when {
                    connectionMap.containsKey(publicUri) || pendingReservations.values.any { it.publicUri == publicUri } -> {
                        PeerSessionAdmissionRejection.DUPLICATE_PUBLIC_URI
                    }

                    connectionMap.size + pendingReservations.size >= networkProperties.maxActivePeerSessions -> {
                        PeerSessionAdmissionRejection.GLOBAL_CAPACITY
                    }

                    !trusted && untrustedSessionCount() >= untrustedCapacity -> {
                        PeerSessionAdmissionRejection.TRUSTED_CAPACITY_RESERVED
                    }

                    addressIdentity != null &&
                        sessionAddressCount(addressIdentity) >= networkProperties.maxPeerSessionsPerAddress -> {
                        PeerSessionAdmissionRejection.ADDRESS_CAPACITY
                    }

                    addressIdentity != null &&
                        sessionPrefixCount(addressIdentity) >= networkProperties.maxPeerSessionsPerPrefix -> {
                        PeerSessionAdmissionRejection.PREFIX_CAPACITY
                    }

                    else -> {
                        null
                    }
                }

            if (rejection != null) {
                reject(rejection)
                return@synchronized PeerSessionReservationResult.Rejected(rejection)
            }

            val reservation =
                PeerSessionReservation(
                    id = reservationId.incrementAndGet(),
                    publicUri = publicUri,
                    addressIdentity = addressIdentity,
                    trusted = trusted,
                )
            pendingReservations[reservation.id] = reservation
            PeerSessionReservationResult.Accepted(reservation)
        }

    private val untrustedCapacity: Int
        get() = networkProperties.maxActivePeerSessions - trustedPublicUris.size

    private fun untrustedSessionCount(): Int = connectionMap.values.count { !it.trusted } + pendingReservations.values.count { !it.trusted }

    private fun sessionAddressCount(addressIdentity: PeerAddressIdentity): Int =
        activeAddressCount(addressIdentity) + pendingReservations.values.count { it.addressIdentity?.address == addressIdentity.address }

    private fun activeAddressCount(addressIdentity: PeerAddressIdentity): Int =
        connectionMap.values.count { it.addressIdentity?.address == addressIdentity.address }

    private fun sessionPrefixCount(addressIdentity: PeerAddressIdentity): Int =
        activePrefixCount(addressIdentity) + pendingReservations.values.count { it.addressIdentity?.prefix == addressIdentity.prefix }

    private fun activePrefixCount(addressIdentity: PeerAddressIdentity): Int =
        connectionMap.values.count { it.addressIdentity?.prefix == addressIdentity.prefix }

    private fun activePublicKeyCount(publicKey: AttoPublicKey): Int = connectionMap.values.count { it.node.publicKey == publicKey }

    private fun reject(rejection: PeerSessionAdmissionRejection) {
        rejectionCounters.getValue(rejection).increment()
    }

    private fun remove(
        publicUri: URI,
        connection: NodeConnection,
    ) {
        synchronized(admissionLock) {
            connectionMap.remove(publicUri, connection)
        }
    }

    companion object {
        internal const val MAX_ACTIVE_PEER_SESSIONS = 1_000
        internal val CAPACITY_CLOSE_REASON =
            CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Active peer session capacity reached")
        internal val DUPLICATE_CLOSE_REASON =
            CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Public URI already connected")
    }
}

internal class PeerSessionReservation internal constructor(
    val id: Long,
    val publicUri: URI,
    val addressIdentity: PeerAddressIdentity?,
    val trusted: Boolean,
)

internal sealed interface PeerSessionReservationResult {
    data class Accepted(
        val reservation: PeerSessionReservation,
    ) : PeerSessionReservationResult

    data class Rejected(
        val reason: PeerSessionAdmissionRejection,
    ) : PeerSessionReservationResult
}

internal enum class PeerSessionAdmissionRejection(
    val metricTag: String,
    val closeReason: CloseReason,
) {
    DUPLICATE_PUBLIC_URI("duplicate_public_uri", NodeConnectionManager.DUPLICATE_CLOSE_REASON),
    GLOBAL_CAPACITY("global_capacity", NodeConnectionManager.CAPACITY_CLOSE_REASON),
    TRUSTED_CAPACITY_RESERVED("trusted_capacity_reserved", NodeConnectionManager.CAPACITY_CLOSE_REASON),
    ADDRESS_CAPACITY("address_capacity", NodeConnectionManager.CAPACITY_CLOSE_REASON),
    PREFIX_CAPACITY("prefix_capacity", NodeConnectionManager.CAPACITY_CLOSE_REASON),
    PUBLIC_KEY_CAPACITY("public_key_capacity", NodeConnectionManager.CAPACITY_CLOSE_REASON),
    INVALID_RESERVATION("invalid_reservation", NodeConnectionManager.CAPACITY_CLOSE_REASON),
    PUBLIC_URI_MISMATCH("public_uri_mismatch", NodeConnectionManager.CAPACITY_CLOSE_REASON),
    UNRESOLVED_ADDRESS("unresolved_address", NodeConnectionManager.CAPACITY_CLOSE_REASON),
}

internal data class PeerAddressIdentity(
    val address: String,
    val prefix: String,
)

private fun InetAddress.toPeerAddressIdentity(): PeerAddressIdentity {
    val rawAddress = address
    val normalizedAddress =
        if (rawAddress.size == 16 && rawAddress.isIpv4MappedAddress()) {
            rawAddress.copyOfRange(12, 16)
        } else {
            rawAddress
        }
    val family = normalizedAddress.size
    require(family == 4 || family == 16) { "Unsupported IP address size: $family" }

    val prefix = normalizedAddress.copyOf()
    val prefixBytes = if (family == 4) 3 else 8
    prefix.fill(0, prefixBytes)
    val familyTag = if (family == 4) "ipv4" else "ipv6"
    return PeerAddressIdentity(
        address = "$familyTag:${normalizedAddress.toHex()}",
        prefix = "$familyTag:${prefix.toHex()}",
    )
}

private fun ByteArray.isIpv4MappedAddress(): Boolean =
    take(10).all { it == 0.toByte() } && this[10] == 0xff.toByte() && this[11] == 0xff.toByte()
