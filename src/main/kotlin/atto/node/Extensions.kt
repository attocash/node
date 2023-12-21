package atto.node

import cash.atto.commons.HeightSupport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.math.BigInteger
import java.util.*

fun ULong.toBigInteger(): BigInteger {
    return BigInteger(this.toString())
}


fun BigInteger.toULong(): ULong {
    return this.toString().toULong()
}

fun <T : HeightSupport> Flow<T>.sortByHeight(initialHeight: ULong): Flow<T> {
    return flow {
        var currentHeight = initialHeight
        val queue = PriorityQueue<T>(1, Comparator.comparing { it.height })
        collect {
            if (currentHeight >= it.height) {
                queue.add(it)
            }
            while (queue.isNotEmpty() && queue.peek().height == currentHeight.toLong().toULong()) {
                val currentAccount = queue.poll()
                currentHeight = currentAccount.height + 1U
                emit(currentAccount)
            }
        }
    }
}