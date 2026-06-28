package cash.atto.node

import cash.atto.node.network.ChallengeResponse
import cash.atto.node.network.CounterChallengeResponse
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalListener
import com.github.benmanes.caffeine.cache.Scheduler
import com.github.benmanes.caffeine.cache.Weigher
import kotlinx.serialization.serializer
import org.flywaydb.core.internal.exception.FlywaySqlException
import org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlNoDriversForInteractiveAuthException
import org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlNoIntegratedAuthException
import org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlServerUntrustedCertificateSqlException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.SQLException
import java.time.Duration
import java.util.jar.JarFile
import javax.sql.DataSource
import kotlin.reflect.typeOf

class NativeImageMetadataTest {
    @Test
    fun `exercise caffeine cache implementations used by native image`() {
        // given
        val caches =
            listOf(
                Caffeine
                    .newBuilder()
                    .expireAfterWrite(Duration.ofMinutes(1))
                    .build<String, String>(),
                Caffeine
                    .newBuilder()
                    .maximumSize(100)
                    .build<String, String>(),
                Caffeine
                    .newBuilder()
                    .maximumSize(100)
                    .expireAfterAccess(Duration.ofMinutes(1))
                    .build<String, String>(),
                Caffeine
                    .newBuilder()
                    .maximumSize(100)
                    .expireAfterWrite(Duration.ofMinutes(1))
                    .build<String, String>(),
                Caffeine
                    .newBuilder()
                    .maximumWeight(100)
                    .weigher(Weigher<String, String> { _, value -> value.length })
                    .build<String, String>(),
                Caffeine
                    .newBuilder()
                    .maximumWeight(100)
                    .weigher(Weigher<String, String> { _, value -> value.length })
                    .expireAfterWrite(Duration.ofMinutes(1))
                    .build<String, String>(),
                Caffeine
                    .newBuilder()
                    .scheduler(Scheduler.systemScheduler())
                    .removalListener(RemovalListener<String, String> { _, _, _ -> })
                    .expireAfterWrite(Duration.ofMinutes(1))
                    .build<String, String>(),
                Caffeine
                    .newBuilder()
                    .scheduler(Scheduler.systemScheduler())
                    .removalListener(RemovalListener<String, String> { _, _, _ -> })
                    .maximumSize(100)
                    .build<String, String>(),
                Caffeine
                    .newBuilder()
                    .scheduler(Scheduler.systemScheduler())
                    .removalListener(RemovalListener<String, String> { _, _, _ -> })
                    .maximumSize(100)
                    .expireAfterWrite(Duration.ofMinutes(1))
                    .build<String, String>(),
            )

        // when
        caches.forEachIndexed(::exerciseCache)

        // then
        assertEquals(9, caches.size)
    }

    @Test
    fun `exercise flyway sql exception reflection used by native image`() {
        // given
        val sqlException = SQLException("Connection refused", "08001")

        // when
        FlywaySqlException.throwFlywayExceptionIfPossible(sqlException, null)

        // then
        listOf(
            FlywaySqlServerUntrustedCertificateSqlException::class.java,
            FlywaySqlNoIntegratedAuthException::class.java,
            FlywaySqlNoDriversForInteractiveAuthException::class.java,
        ).forEach {
            assertNotNull(it.getMethod("isFlywaySpecificVersionOf", SQLException::class.java))
            assertNotNull(it.getDeclaredConstructor(SQLException::class.java, DataSource::class.java))
        }
    }

    @Test
    fun `exercise ktor style serializer lookup used by native image`() {
        // Ktor resolves request and response serializers from KType at runtime.
        val serializers =
            listOf(
                serializer(typeOf<CounterChallengeResponse>()),
                serializer(typeOf<ChallengeResponse>()),
            )

        assertEquals(
            listOf(
                "cash.atto.node.network.CounterChallengeResponse",
                "cash.atto.node.network.ChallengeResponse",
            ),
            serializers.map { it.descriptor.serialName },
        )
    }

    @Test
    fun `exercise atto serializer singleton reflection used by native image`() {
        val touchedSerializers =
            attoSerializerClassNames()
                .mapNotNull(::readSerializerSingleton)

        assertTrue(
            touchedSerializers.contains("cash.atto.node.network.CounterChallengeResponse\$\$serializer"),
            "CounterChallengeResponse serializer singleton should be covered by native metadata tests",
        )
    }

    private fun exerciseCache(
        index: Int,
        cache: Cache<String, String>,
    ) {
        val key = "key-$index"
        val value = "value-$index"

        cache.put(key, value)

        assertEquals(value, cache.getIfPresent(key))
        assertEquals(value, cache.asMap()[key])

        cache.invalidate(key)
        cache.cleanUp()
    }

    private fun attoSerializerClassNames(): List<String> =
        System
            .getProperty("java.class.path")
            .split(File.pathSeparator)
            .asSequence()
            .map(::File)
            .flatMap { it.attoSerializerClassNames().asSequence() }
            .distinct()
            .sorted()
            .toList()

    private fun File.attoSerializerClassNames(): List<String> =
        when {
            isDirectory -> directoryAttoSerializerClassNames()
            isFile && extension == "jar" -> jarAttoSerializerClassNames()
            else -> emptyList()
        }

    private fun File.directoryAttoSerializerClassNames(): List<String> =
        walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(this).path.replace(File.separatorChar, '/') }
            .filter(::isAttoSerializerClassFile)
            .map(::toClassName)
            .toList()

    private fun File.jarAttoSerializerClassNames(): List<String> =
        JarFile(this).use { jar ->
            jar
                .entries()
                .asSequence()
                .map { it.name }
                .filter(::isAttoSerializerClassFile)
                .map(::toClassName)
                .toList()
        }

    private fun isAttoSerializerClassFile(path: String): Boolean = path.startsWith("cash/atto/") && path.endsWith("\$\$serializer.class")

    private fun toClassName(path: String): String = path.removeSuffix(".class").replace('/', '.')

    private fun readSerializerSingleton(className: String): String? {
        val instanceField =
            runCatching {
                Class.forName(className).getField("INSTANCE")
            }.getOrNull() ?: return null

        assertNotNull(instanceField.get(null), className)
        return className
    }
}
