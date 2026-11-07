package cash.atto.node.election

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoSigner
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.account.AccountRepository
import cash.atto.node.account.AccountUpdated
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.network.guardian.ExpectedResponses
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.vote.weight.VoteWeightProperties
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.node.vote.weight.Weight
import cash.atto.node.vote.weight.WeightRepository
import cash.atto.node.vote.weight.WeightService
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Threads
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import reactor.core.publisher.Mono
import java.lang.reflect.Proxy
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@State(Scope.Benchmark)
class ElectionVoterBenchmark {
    private companion object {
        val EVENT_TIMESTAMP: Instant = Instant.EPOCH
    }

    private lateinit var electionVoter: ElectionVoter
    private lateinit var account: Account

    private lateinit var node: AttoNode
    private lateinit var signer: AttoSigner
    private lateinit var voteWeighter: VoteWeighter
    private val accountRepository: AccountRepository = NoOpAccountRepository()
    private val nextHeight = AtomicInteger(1)

    @Setup(Level.Trial)
    fun setUp() {
        val algorithm = AttoAlgorithm.V1
        val publicKey = AttoPublicKey(ByteArray(32) { index -> index.toByte() })

        node =
            AttoNode(
                network = AttoNetwork.LOCAL,
                protocolVersion = 1U,
                algorithm = algorithm,
                publicKey = publicKey,
                publicUri = URI("ws://127.0.0.1:7070"),
                features = setOf(NodeFeature.VOTING),
            )
        signer = BenchmarkSigner(algorithm, publicKey)
        voteWeighter = createVoteWeighter(node)

        val springPublisher = NoOpSpringPublisher()
        electionVoter =
            ElectionVoter(
                thisNode = node,
                signer = signer,
                voteWeighter = voteWeighter,
                eventPublisher = EventPublisher(springPublisher),
                messagePublisher = NetworkMessagePublisher(springPublisher, ExpectedResponses(Clock.systemUTC())),
                accountRepository = accountRepository,
            )
        account = createAccount(node)
    }

    // Measures event handling and scheduling; signing completes asynchronously.
    @Benchmark
    @Threads(100)
    fun processElectionFlow() =
        runBlocking {
            val transaction = createTransaction(node, nextHeight.getAndIncrement().toUInt())

            electionVoter.process(ElectionStarted(account, transaction, EVENT_TIMESTAMP))
            electionVoter.process(
                ElectionConsensusChanged(
                    account = account,
                    transaction = transaction,
                    timestamp = EVENT_TIMESTAMP.plusNanos(1),
                ),
            )
            electionVoter.process(
                ElectionConsensusReached(
                    account = account,
                    transaction = transaction,
                    votes = emptySet(),
                    timestamp = EVENT_TIMESTAMP.plusNanos(2),
                ),
            )
            electionVoter.process(
                AccountUpdated(
                    source = TransactionSource.ELECTION,
                    previousAccount = account,
                    updatedAccount = account,
                    transaction = transaction,
                    timestamp = EVENT_TIMESTAMP,
                ),
            )
        }

    @TearDown(Level.Trial)
    fun tearDownTrial() {
        electionVoter.close()
    }
}

private class BenchmarkSigner(
    override val algorithm: AttoAlgorithm,
    override val publicKey: AttoPublicKey,
) : AttoSigner {
    override val address: AttoAddress = AttoAddress(algorithm, publicKey)

    private val signature = AttoSignature(ByteArray(64) { 1 })

    override suspend fun sign(hash: AttoHash): AttoSignature {
        delay(100.milliseconds)
        return signature
    }
}

private class NoOpSpringPublisher : ApplicationEventPublisher {
    override fun publishEvent(event: ApplicationEvent) {
        // no-op for benchmarking
    }

    override fun publishEvent(event: Any) {
        // no-op for benchmarking
    }
}

private class NoOpAccountRepository : AccountRepository {
    override fun saveAll(entities: List<Account>): Flow<Account> = emptyFlow()

    override suspend fun findById(id: AttoPublicKey): Account? = null

    override fun findAllById(ids: Iterable<AttoPublicKey>): Flow<Account> = emptyFlow()

    override suspend fun deleteAll() {}
}

private fun createAccount(node: AttoNode): Account =
    Account(
        publicKey = node.publicKey,
        network = node.network,
        version = 0U.toAttoVersion(),
        algorithm = node.algorithm,
        height = 0,
        balance = AttoAmount.MIN,
        lastTransactionTimestamp = Instant.EPOCH,
        lastTransactionHash = AttoHash(ByteArray(32)),
        representativeAlgorithm = node.algorithm,
        representativePublicKey = node.publicKey,
    )

private fun createTransaction(
    node: AttoNode,
    height: UInt,
): Transaction {
    val seed = height.toInt()
    val block =
        AttoReceiveBlock(
            version = 0U.toAttoVersion(),
            network = node.network,
            algorithm = node.algorithm,
            publicKey = node.publicKey,
            height = height.toAttoHeight(),
            balance = AttoAmount.MAX,
            timestamp = AttoInstant.now(),
            previous = AttoHash(ByteArray(32) { index -> (seed + index).toByte() }),
            sendHashAlgorithm = node.algorithm,
            sendHash = AttoHash(ByteArray(32) { index -> (seed * 17 + index).toByte() }),
        )

    return Transaction(
        block = block,
        signature = AttoSignature(ByteArray(64) { index -> (seed + index).toByte() }),
        work = AttoWork(ByteArray(8) { index -> (seed * 3 + index).toByte() }),
    )
}

private fun createVoteWeighter(node: AttoNode): VoteWeighter {
    val properties =
        VoteWeightProperties().apply {
            minimalConfirmationWeight = "1"
            confirmationThreshold = 1
            minimalRebroadcastWeight = "1"
            samplePeriodInDays = 1
        }
    val weight = Weight(node.publicKey, AttoAmount.MAX, Instant.now())
    return VoteWeighter(
        thisNode = node,
        properties = properties,
        weightService = WeightService(weightRepository(weight), BenchmarkTransactionManager()),
        genesisTransaction = createTransaction(node, 1U),
    ).also { it.init() }
}

private fun weightRepository(weight: Weight): WeightRepository =
    Proxy
        .newProxyInstance(
            WeightRepository::class.java.classLoader,
            arrayOf(WeightRepository::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "findCalculatedWeights" -> flowOf(weight)
                "upsert", "deleteAllExcept" -> Unit
                "toString" -> "BenchmarkWeightRepository"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                else -> error("Unexpected benchmark repository call: ${method.name}")
            }
        } as WeightRepository

private class BenchmarkTransactionManager : ReactiveTransactionManager {
    override fun getReactiveTransaction(definition: TransactionDefinition?): Mono<ReactiveTransaction> =
        Mono.just(object : ReactiveTransaction {})

    override fun commit(transaction: ReactiveTransaction): Mono<Void> = Mono.empty()

    override fun rollback(transaction: ReactiveTransaction): Mono<Void> = error("Unexpected benchmark initialization rollback")
}
