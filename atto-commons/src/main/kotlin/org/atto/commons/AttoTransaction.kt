package org.atto.commons

data class AttoTransaction(
    val block: AttoBlock,
    val signature: AttoSignature,
    val work: AttoWork
) {
    val byteBuffer = toByteBuffer()
    val hash = block.hash

    companion object {
        const val size = 72

        fun fromByteBuffer(network: AttoNetwork, byteBuffer: AttoByteBuffer): AttoTransaction? {
            if (size > byteBuffer.size) {
                return null
            }

            val block = AttoBlock.fromByteBuffer(byteBuffer) ?: return null

            val transaction = AttoTransaction(
                block = block,
                signature = byteBuffer.getSignature(),
                work = byteBuffer.getWork(),
            )

            if (!transaction.isValid(network)) {
                return null
            }

            return transaction
        }
    }

    /**
     * Minimal block validation. This method doesn't check this transaction against the ledger so further validations are required.
     */
    fun isValid(network: AttoNetwork): Boolean {
        if (!block.isValid()) {
            return false
        }

        if (!work.isValid(network, block.timestamp, block.getWorkHash())) {
            return false
        }


        if (!signature.isValid(block.publicKey, block.hash.value)) {
            return false
        }

        return true
    }

    fun toByteBuffer(): AttoByteBuffer {
        return AttoByteBuffer(size + block.type.size)
            .add(block.serialized)
            .add(signature)
            .add(work)
    }
}