package cash.atto.node.account

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoChangeBlock
import cash.atto.commons.AttoOpenBlock
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSendBlock
import cash.atto.commons.ReceiveSupport
import cash.atto.commons.RepresentativeSupport
import cash.atto.node.EventPublisher
import cash.atto.node.account.entry.AccountEntry
import cash.atto.node.account.entry.AccountEntryService
import cash.atto.node.receivable.ReceivableRepository
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
    private val accountEntryService: AccountEntryService,
    private val transactionService: TransactionService,
    private val receivableRepository: ReceivableRepository,
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
                height = block.height.value.toLong() - 1,
                balance = AttoAmount.MIN,
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

        require(
            updatedAccount.height.toULong() == transaction.height.value,
        ) { "Sanity check for account height failed. $account $transaction" }

        val block = transaction.block
        val (subjectAlgorithm, subjectPublicKey) =
            when (block) {
                is AttoChangeBlock -> block.representativeAlgorithm to block.representativePublicKey
                is AttoSendBlock -> block.receiverAlgorithm to block.receiverPublicKey
                is AttoReceiveBlock, is AttoOpenBlock -> {
                    block as ReceiveSupport
                    val receivable = receivableRepository.findById(block.sendHash)!!
                    receivable.algorithm to receivable.publicKey
                }
            }

        val entry =
            AccountEntry(
                hash = transaction.hash,
                algorithm = transaction.algorithm,
                publicKey = transaction.publicKey,
                height = transaction.height,
                blockType = block.type,
                subjectAlgorithm = subjectAlgorithm,
                subjectPublicKey = subjectPublicKey,
                previousBalance = account.balance,
                balance = updatedAccount.balance,
                timestamp = block.timestamp.toJavaInstant(),
            )

        accountEntryService.save(entry)

        transactionService.save(source, transaction)

        eventPublisher.publishAfterCommit(AccountUpdated(source, account, updatedAccount, transaction))

        return updatedAccount
    }
}
