package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.BankReconciliationRepository
import com.theprodeogroup.fish.domain.ledger.BankReconciliationStatus
import com.theprodeogroup.fish.domain.ledger.CashBook
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.ledger.findOwnedBy
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate

/**
 * `GET /companies/{companyId}/cash-books/{accountId}?from=&to=` (docs/GL_Cash_And_Bank_Books_SRS.md,
 * FR-CB10 to CB13): the book of one cash or bank account. Read-only.
 *
 * An account that is not the Company's, or does not exist, is [Result.AccountNotFound] (never a distinct
 * "forbidden", so this never confirms another Company's account exists). An account of the Company that has
 * no cash/bank kind is [Result.NotACashBook]. With no range given it is this month to date. A range with more
 * than [ROW_LIMIT] rows is refused with the count so the caller can narrow it.
 *
 * [Result.Success.canUndo] says, per row, whether an Undo (reversal) can work: the entry is POSTED, is not
 * itself a reversal, has not been reversed already, and its Period still allows posting. GL decides this so
 * a screen does not have to guess.
 */
class ComputeCashBookUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val periodRepository: PeriodRepository,
    private val bankReconciliationRepository: BankReconciliationRepository
) {
    sealed class Result {
        /** [reconciledEntryIds] is null for a cash book (it is not reconcilable) and, for a bank account, every entry matched in a COMPLETED reconciliation. */
        data class Success(val book: CashBook, val canUndo: Map<JournalEntryId, Boolean>, val reconciledEntryIds: Set<JournalEntryId>?) : Result()
        data object CompanyNotFound : Result()
        data object AccountNotFound : Result()
        data object NotACashBook : Result()
        data class RangeTooLarge(val rowCount: Int, val limit: Int) : Result()
        data object InvalidRange : Result()
    }

    fun execute(
        companyId: CompanyId,
        accountId: AccountId,
        from: LocalDate? = null,
        to: LocalDate? = null,
        today: LocalDate = LocalDate.now()
    ): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound
        val account = accountRepository.findById(accountId)?.takeIf { it.companyId == companyId } ?: return Result.AccountNotFound
        if (account.cashBookKind == null) return Result.NotACashBook

        val rangeFrom = from ?: today.withDayOfMonth(1)
        val rangeTo = to ?: today
        if (rangeTo.isBefore(rangeFrom)) return Result.InvalidRange

        val accountsById = accountRepository.findAllByCompany(companyId).associateBy { it.id }
        val entries = journalEntryRepository.findAllByAccount(account.id)
        val book = CashBook.of(account, entries, rangeFrom, rangeTo, company.baseCurrency, accountsById)
        if (book.rows.size > ROW_LIMIT) return Result.RangeTooLarge(book.rows.size, ROW_LIMIT)

        val periodOpen = mutableMapOf<com.theprodeogroup.fish.domain.ledger.PeriodId, Boolean>()
        val canUndo = book.rows.associate { row ->
            val open = periodOpen.getOrPut(row.periodId) { periodRepository.findOwnedBy(row.periodId, companyId)?.allowsPosting() == true }
            row.entryId to (
                row.status == PostingStatus.POSTED &&
                    row.reversalOfEntryId == null &&
                    // Only an entry that started in a cash or bank book is undone here; a sales collection, a supplier
                    // payment or a pay run is reversed in its own module, which would otherwise still show it paid.
                    row.source == JournalSource.CASH_BOOK &&
                    row.reversedByEntryId == null &&
                    open
                )
        }
        // Only a bank account is reconciled, and only a COMPLETED reconciliation clears an entry: one still open or
        // cancelled has not confirmed anything against the statement.
        val reconciled = if (account.cashBookKind == CashBookKind.BANK) {
            bankReconciliationRepository.findAllByCompany(companyId, emptyList(), account.id)
                .filter { it.status == BankReconciliationStatus.COMPLETED }
                .flatMap { it.currentMatches }
                .map { it.second }
                .toSet()
        } else null
        return Result.Success(book, canUndo, reconciled)
    }

    companion object {
        const val ROW_LIMIT = 5000
    }
}
