package cash.atto.node.account

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoChangeBlock
import cash.atto.commons.AttoOpenBlock
import cash.atto.commons.AttoPublicKey
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
import cash.atto.protocol.AttoNode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import kotlinx.datetime.toJavaInstant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccountService(
    private val thisNode: AttoNode,
    private val accountRepository: AccountRepository,
    private val accountEntryService: AccountEntryService,
    private val transactionService: TransactionService,
    private val receivableRepository: ReceivableRepository,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    private suspend fun List<Transaction>.getAccountMap(): Map<AttoPublicKey, AccountTransaction> {
        val openAccounts =
            this
                .filter { it.block is AttoOpenBlock }
                .associate { tx ->
                    val block = tx.block as AttoOpenBlock
                    tx.publicKey to
                        AccountTransaction(
                            account =
                                Account(
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
                                ),
                            transaction = tx,
                        )
                }

        val existingPublicKeys =
            this
                .filterNot { it.block is AttoOpenBlock }
                .map { it.publicKey }
                .distinct()

        val existingAccounts =
            accountRepository
                .findAllById(existingPublicKeys)
                .toList()
                .associateBy { it.publicKey }

        val existingAccountTransactions =
            this
                .filterNot { it.block is AttoOpenBlock }
                .mapNotNull { tx ->
                    existingAccounts[tx.publicKey]?.let { account ->
                        tx.publicKey to AccountTransaction(account, tx)
                    }
                }.toMap()

        return existingAccountTransactions + openAccounts
    }

    private fun Account.updateWith(transaction: Transaction): Account {
        val block = transaction.block

        return this.copy(
            version = block.version,
            balance = block.balance,
            lastTransactionHash = block.hash,
            lastTransactionTimestamp = block.timestamp.toJavaInstant(),
            representativeAlgorithm =
                if (block is RepresentativeSupport) {
                    block.representativeAlgorithm
                } else {
                    this.representativeAlgorithm
                },
            representativePublicKey =
                if (block is RepresentativeSupport) {
                    block.representativePublicKey
                } else {
                    this.representativePublicKey
                },
        )
    }

    private suspend fun Map<AttoPublicKey, AccountTransaction>.toEntries(): List<AccountEntry> {
        val neededReceivables =
            this.values
                .mapNotNull {
                    val block = it.transaction.block
                    if (block is AttoReceiveBlock || block is AttoOpenBlock) {
                        (block as ReceiveSupport).sendHash
                    } else {
                        null
                    }
                }.distinct()

        val receivableMap =
            receivableRepository
                .findAllById(neededReceivables)
                .toList()
                .associateBy { it.hash }

        return this.values.map { (account, transaction) ->
            val block = transaction.block
            val (subjectAlgorithm, subjectPublicKey) =
                when (block) {
                    is AttoChangeBlock -> {
                        block.representativeAlgorithm to block.representativePublicKey
                    }

                    is AttoSendBlock -> {
                        block.receiverAlgorithm to block.receiverPublicKey
                    }

                    is AttoReceiveBlock, is AttoOpenBlock -> {
                        block as ReceiveSupport
                        val receivable = receivableMap[block.sendHash]!!
                        receivable.algorithm to receivable.publicKey
                    }
                }

            AccountEntry(
                hash = transaction.hash,
                algorithm = transaction.algorithm,
                publicKey = transaction.publicKey,
                height = transaction.height,
                blockType = block.type,
                subjectAlgorithm = subjectAlgorithm,
                subjectPublicKey = subjectPublicKey,
                previousBalance = account.balance,
                balance = block.balance,
                timestamp = block.timestamp.toJavaInstant(),
            )
        }
    }

    @Transactional
    suspend fun add(
        source: TransactionSource,
        transactions: List<Transaction>,
    ): List<Account> {
        val accountTransactionMap = transactions.getAccountMap()
        val updatedAccounts = accountTransactionMap.values.map { it.account.updateWith(it.transaction) }

        accountRepository.saveAll(updatedAccounts).collect { updatedAccount ->
            val (account, transaction) = accountTransactionMap[updatedAccount.publicKey]!!
            require(
                updatedAccount.height.toULong() == transaction.height.value,
            ) { "Sanity check for account height failed. $updatedAccount $transaction" }

            logger.debug { "Saved $updatedAccount" }

            eventPublisher.publishAfterCommit(AccountUpdated(source, account, updatedAccount, transaction))
        }

        if (thisNode.isHistorical()) {
            val entries = accountTransactionMap.toEntries()
            accountEntryService.saveAll(entries)
        }

        transactionService.saveAll(transactions)

        return updatedAccounts
    }

    private data class AccountTransaction(
        val account: Account,
        val transaction: Transaction,
    )
}
