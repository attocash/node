package cash.atto.node.account

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAccountEntry
import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoBlockType
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceivable
import cash.atto.commons.AttoTransaction
import cash.atto.commons.fromHexToByteArray
import cash.atto.commons.toAttoVersion

internal object LegacyHttpFixtures {
    private val address = AttoAddress(AttoAlgorithm.V1, AttoPublicKey(ByteArray(32) { 1 }))
    private val otherAddress = AttoAddress(AttoAlgorithm.V1, AttoPublicKey(ByteArray(32) { 2 }))
    private val hash = AttoHash(ByteArray(32) { 3 })
    private val timestamp = AttoInstant.fromEpochMilliseconds(1234567890000)
    private val version = 0U.toAttoVersion()

    val account =
        AttoAccount(
            address = address,
            network = AttoNetwork.LOCAL,
            version = version,
            height = AttoHeight(2UL),
            balance = AttoAmount(10UL),
            lastTransactionHash = hash,
            lastTransactionTimestamp = timestamp,
            representativeAddress = otherAddress,
        )
    val entry =
        AttoAccountEntry(
            hash = hash,
            address = address,
            height = AttoHeight(2UL),
            blockType = AttoBlockType.SEND,
            subjectAddress = otherAddress,
            previousBalance = AttoAmount(13UL),
            balance = AttoAmount(10UL),
            timestamp = timestamp,
        )
    val receivable =
        AttoReceivable(
            network = AttoNetwork.LOCAL,
            hash = hash,
            version = version,
            address = address,
            timestamp = timestamp,
            receiverAddress = otherAddress,
            amount = AttoAmount(3UL),
        )

    fun transaction(name: String): AttoTransaction =
        AttoTransaction.fromByteArray(checkNotNull(javaClass.getResource("/legacy-http/$name.hex")).readText().trim().fromHexToByteArray())
}
