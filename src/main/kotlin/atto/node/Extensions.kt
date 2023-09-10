package atto.node

import java.math.BigInteger

fun ULong.toBigInteger(): BigInteger {
    return BigInteger(this.toString())
}


fun BigInteger.toULong(): ULong {
    return this.toString().toULong()
}