package cash.atto.node.account

import cash.atto.commons.AttoOpenBlock
import cash.atto.commons.RepresentativeSupport
import cash.atto.node.EventPublisher
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionService
import cash.atto.node.transaction.TransactionSource
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.toJavaInstant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccountService(
    private val accountRepository: AccountRepository,
    private val transactionService: TransactionService,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    private suspend fun getAccount(transaction: Transaction): Account {
        val block = transaction.block
        if (block is AttoOpenBlock) {
            return Account(
                publicKey = block.publicKey,
                network = block.network,
                version = block.version,
                algorithm = block.algorithm,
                height = block.height - 1U,
                balance = block.balance,
                lastTransactionHash = block.hash,
                lastTransactionTimestamp = block.timestamp.toJavaInstant(),
                representativeAlgorithm = block.representativeAlgorithm,
                representativePublicKey = block.representativePublicKey,
            )
        }
        return accountRepository.findById(transaction.publicKey)!!
    }

    private suspend fun update(
        account: Account,
        transaction: Transaction,
    ): Account {
        val block = transaction.block

        val newAccount =
            account.copy(
                version = block.version,
                balance = block.balance,
                lastTransactionHash = block.hash,
                lastTransactionTimestamp = block.timestamp.toJavaInstant(),
                representativeAlgorithm =
                    if (block is RepresentativeSupport) {
                        block.representativeAlgorithm
                    } else {
                        account
                            .representativeAlgorithm
                    },
                representativePublicKey =
                    if (block is RepresentativeSupport) {
                        block.representativePublicKey
                    } else {
                        account.representativePublicKey
                    },
            )
        return accountRepository.save(newAccount).also {
            logger.debug { "Saved $account" }
        }
    }

    @Transactional
    suspend fun add(
        source: TransactionSource,
        transaction: Transaction,
    ): Account {
        val account = getAccount(transaction)
        val updatedAccount = update(account, transaction)

        require(updatedAccount.height == transaction.height) { "Sanity check for account height failed. $account $transaction" }

        transactionService.save(source, transaction)

        eventPublisher.publishAfterCommit(AccountUpdated(source, account, updatedAccount, transaction))

        return updatedAccount
    }
}
