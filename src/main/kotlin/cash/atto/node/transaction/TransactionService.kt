package cash.atto.node.transaction

import cash.atto.commons.AttoOpenBlock
import cash.atto.commons.AttoSendBlock
import cash.atto.commons.ReceiveSupport
import cash.atto.commons.RepresentativeSupport
import cash.atto.commons.toReceivable
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.account.AccountRepository
import cash.atto.node.account.AccountService
import cash.atto.node.receivable.ReceivableService
import cash.atto.node.receivable.toReceivable
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.toJavaInstant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TransactionService(
    private val accountRepository: AccountRepository,
    private val accountService: AccountService,
    private val receivableService: ReceivableService,
    private val transactionRepository: TransactionRepository,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    @Transactional
    suspend fun save(
        source: TransactionSaveSource,
        transaction: Transaction,
    ): SaveTransactionResponse {
        val block = transaction.block

        val previousAccount = getAccount(transaction)
        val updatedAccount =
            previousAccount.copy(
                version = block.version,
                height = block.height,
                balance = block.balance,
                lastTransactionHash = block.hash,
                lastTransactionTimestamp = block.timestamp.toJavaInstant(),
                representativeAlgorithm =
                    if (block is RepresentativeSupport) {
                        block.representativeAlgorithm
                    } else {
                        previousAccount
                            .representativeAlgorithm
                    },
                representativePublicKey =
                    if (block is RepresentativeSupport) {
                        block.representativePublicKey
                    } else {
                        previousAccount
                            .representativePublicKey
                    },
            )

        accountService.save(updatedAccount)
        transactionRepository.save(transaction)
        logger.debug { "Saved $transaction" }

        if (block is AttoSendBlock) {
            val receivable = block.toReceivable().toReceivable()
            receivableService.save(receivable)
        } else if (block is ReceiveSupport) {
            receivableService.delete(block.sendHash)
        }

        eventPublisher.publishAfterCommit(TransactionSaved(source, previousAccount, updatedAccount, transaction))

        return SaveTransactionResponse(previousAccount, updatedAccount, transaction)
    }

    private suspend fun getAccount(transaction: Transaction): Account {
        val block = transaction.block
        if (block is AttoOpenBlock) {
            return Account(
                publicKey = block.publicKey,
                network = block.network,
                version = block.version,
                algorithm = block.algorithm,
                height = block.height,
                balance = block.balance,
                lastTransactionHash = block.hash,
                lastTransactionTimestamp = block.timestamp.toJavaInstant(),
                representativeAlgorithm = block.representativeAlgorithm,
                representativePublicKey = block.representativePublicKey,
            )
        }
        return accountRepository.findById(transaction.publicKey)!!
    }
}

data class SaveTransactionResponse(
    val previousAccount: Account,
    val updatedAccount: Account,
    val transaction: Transaction,
)
