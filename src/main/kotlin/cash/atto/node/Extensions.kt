package cash.atto.node

import cash.atto.commons.HeightSupport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.math.BigInteger
import java.util.*

fun ULong.toBigInteger(): BigInteger = BigInteger(this.toString())

fun BigInteger.toULong(): ULong = this.toString().toULong()

/**
 * Sort by height while avoid duplications. No height should be skipped otherwise this function will accumulate indefinitely.
 */
fun <T : HeightSupport> Flow<T>.sortByHeight(initialHeight: ULong): Flow<T> =
    flow {
        var currentHeight = initialHeight
        val sortedSet = TreeSet<T>(Comparator.comparing { it.height })
        collect {
            if (currentHeight <= it.height) {
                sortedSet.add(it)
            }
            while (sortedSet.isNotEmpty() && sortedSet.first().height == currentHeight) {
                val currentAccount = sortedSet.pollFirst()!!
                currentHeight = currentAccount.height + 1U
                emit(currentAccount)
            }
        }
    }

/**
 * Just emits when previous height was before the current height
 */
fun <T : HeightSupport> Flow<T>.forwardHeight(): Flow<T> =
    flow {
        var lastHeight = 0UL
        collect {
            if (lastHeight < it.height) {
                emit(it)
                lastHeight = it.height
            }
        }
    }
