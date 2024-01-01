package atto.node.account

import atto.node.AttoRepository
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.math.BigInteger
import java.time.Instant

interface AccountRepository : CoroutineCrudRepository<Account, AttoPublicKey>, AttoRepository {

    suspend fun findByAlgorithmAndPublicKey(algorithm: AttoAlgorithm, publicKey: AttoPublicKey): Account?

    @Query("SELECT representative AS public_key, CAST(SUM(balance) AS UNSIGNED) AS weight FROM account GROUP BY representative")
    suspend fun findAllWeights(): List<WeightView>
}

/**
 * There's a weird bug when using default methods causing it to throw a ClassCastException
 *
 * Full error:
 * kotlinx.coroutines.CoroutinesInternalError: Fatal exception in coroutines machinery for DispatchedContinuation[Dispatchers.IO, Continuation at atto.node.bootstrap.discovery.LastDiscoverer$processPush$2.invokeSuspend(LastDiscoverer.kt:60)@10ac9cd7]. Please read KDoc to 'handleFatalException' method and report this incident to maintainers
 * 	at kotlinx.coroutines.DispatchedTask.handleFatalException$kotlinx_coroutines_core(DispatchedTask.kt:146)
 * 	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:117)
 * 	at kotlinx.coroutines.internal.LimitedDispatcher$Worker.run(LimitedDispatcher.kt:115)
 * 	at kotlinx.coroutines.scheduling.TaskImpl.run(Tasks.kt:103)
 * 	at kotlinx.coroutines.scheduling.CoroutineScheduler.runSafely(CoroutineScheduler.kt:584)
 * 	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.executeTask(CoroutineScheduler.kt:793)
 * 	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:697)
 * 	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:684)
 * 	at ✽.peer B finds 1 unchecked transactions(classpath:features/bootstrap.feature:49)
 * 	Suppressed: kotlinx.coroutines.internal.DiagnosticCoroutineContextException: [Context0{}, CoroutineId(14), "coroutine#14":DispatchedCoroutine{Completed}@422f74b7, Dispatchers.IO]
 * Caused by: java.lang.ClassCastException: class kotlin.coroutines.jvm.internal.CompletedContinuation cannot be cast to class kotlinx.coroutines.internal.DispatchedContinuation (kotlin.coroutines.jvm.internal.CompletedContinuation and kotlinx.coroutines.internal.DispatchedContinuation are in unnamed module of loader java.net.URLClassLoader @7427c8d4)
 * 	at kotlinx.coroutines.CoroutineDispatcher.releaseInterceptedContinuation(CoroutineDispatcher.kt:166)
 * 	at kotlin.coroutines.jvm.internal.ContinuationImpl.releaseIntercepted(ContinuationImpl.kt:118)
 * 	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:39)
 * 	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)
 * 	at kotlinx.coroutines.internal.LimitedDispatcher$Worker.run(LimitedDispatcher.kt:115)
 * 	at kotlinx.coroutines.scheduling.TaskImpl.run(Tasks.kt:103)
 * 	at kotlinx.coroutines.scheduling.CoroutineScheduler.runSafely(CoroutineScheduler.kt:584)
 * 	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.executeTask(CoroutineScheduler.kt:793)
 * 	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:697)
 * 	at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:684)
 */
suspend fun AccountRepository.getByAlgorithmAndPublicKey(algorithm: AttoAlgorithm, publicKey: AttoPublicKey): Account {
    val account = findByAlgorithmAndPublicKey(algorithm, publicKey)
    if (account != null) {
        return account
    }

    return Account(
        publicKey = publicKey,
        version = 0u,
        algorithm = algorithm,
        height = 0u,
        representative = AttoPublicKey(ByteArray(32)),
        balance = AttoAmount.MIN,
        lastTransactionHash = AttoHash(ByteArray(32)),
        lastTransactionTimestamp = Instant.MIN
    )
}

data class WeightView(
    val publicKey: AttoPublicKey,
    val weight: BigInteger // r2dbc doesn't seem to respect the DBConverter
)