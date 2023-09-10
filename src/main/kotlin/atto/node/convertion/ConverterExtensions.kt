package atto.node.convertion

import atto.node.DB
import atto.node.toBigInteger
import atto.node.toULong
import io.r2dbc.spi.Row
import java.math.BigInteger

fun ULong.toDB(db: DB): Any {
    return when (db) {
        DB.MYSQL -> {
            this.toBigInteger()
        }

        else -> {
            this.toLong()
        }
    }
}

fun Row.toULong(db: DB, name: String): ULong {
    return when (db) {
        DB.MYSQL -> {
            this.get(name, BigInteger::class.javaObjectType)!!.toULong()
        }

        else -> {
            this.get(name, Long::class.javaObjectType)!!.toULong()
        }
    }
}