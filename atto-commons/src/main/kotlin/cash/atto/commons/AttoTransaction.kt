package cash.atto.commons

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


        if (block is PreviousSupport && !work.isValid(network, block.timestamp, block.previous)) {
            return false
        }

        if (block is AttoOpenBlock && !work.isValid(network, block.timestamp, block.publicKey)) {
            return false
        }


        if (!signature.isValid(block.publicKey, block.hash)) {
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

    override fun toString(): String {
        return "AttoTransaction(hash=$hash, block=$block, signature=$signature, work=$work, byteBuffer=$byteBuffer, hash=$hash)"
    }


}