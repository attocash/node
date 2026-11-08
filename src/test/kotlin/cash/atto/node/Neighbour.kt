package cash.atto.node

data class Neighbour(
    val websocketPort: UShort,
    val httpPort: UShort,
    val released: Boolean = false,
    val managementPort: UShort = httpPort,
)
