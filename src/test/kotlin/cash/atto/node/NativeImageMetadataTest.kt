package cash.atto.node

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalListener
import com.github.benmanes.caffeine.cache.Scheduler
import com.github.benmanes.caffeine.cache.Weigher
import org.flywaydb.core.internal.exception.FlywaySqlException
import org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlNoDriversForInteractiveAuthException
import org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlNoIntegratedAuthException
import org.flywaydb.core.internal.exception.sqlExceptions.FlywaySqlServerUntrustedCertificateSqlException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Duration
import javax.sql.DataSource

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
}
