package cash.atto.node.network

import cash.atto.commons.toHex
import com.github.benmanes.caffeine.cache.Caffeine
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

internal object ChallengeStore {
    const val CHALLENGE_SIZE = 128
    private val random = SecureRandom.getInstanceStrong()!!

    private val challenges =
        Caffeine
            .newBuilder()
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .maximumSize(100_000)
            .build<URI, String>()
            .asMap()

    fun remove(
        publicUri: URI,
        challenge: String,
    ): Boolean {
        return challenges.remove(publicUri, challenge)
    }

    fun generate(publicUri: URI): String {
        val challengePrefix = publicUri.toString().toByteArray()
        val challenge =
            ByteArray(CHALLENGE_SIZE).let {
                random.nextBytes(it)
                (challengePrefix + it).toHex()
            }

        challenges[publicUri] = challenge
        return challenge
    }

    fun clear() {
        challenges.clear()
    }
}
