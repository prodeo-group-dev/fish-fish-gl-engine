package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * Move money between two of the Company's own cash and bank books (docs/GL_Cash_And_Bank_Books_SRS.md, FR-CB24): cash
 * paid into the bank, a withdrawal, a transfer between two bank accounts. One balanced entry debits the receiving book
 * and credits the paying one, so it appears in both books, as money out of one and money in to the other. It is not a
 * cash flow (the statement of cash flows skips an entry that only moves money within the pool).
 *
 * Idempotent exactly as [RecordCashBookEntryUseCase] is: the caller derives [Request.entryId] from the Idempotency-Key,
 * and the entry is stored at most once under that id. A refused request stores nothing.
 *
 * Both books must belong to the Company, differ, and be cash or bank books in the Company's one currency. Taking the
 * paying book below zero still posts, with a warning.
 */
class RecordCashBookTransferUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val periodRepository: PeriodRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val postJournalEntryUseCase: PostJournalEntryUseCase
) {
    data class Request(
        val companyId: CompanyId,
        val fromAccountId: AccountId,
        val toAccountId: AccountId,
        val amount: BigDecimal,
        val date: LocalDate,
        val description: String?,
        val entryId: JournalEntryId
    )

    sealed class Result {
        data class Posted(val entry: JournalEntry, val fromBalanceAfter: Money, val toBalanceAfter: Money, val warnings: List<RecordCashBookEntryUseCase.Warning>) : Result()
        data class Replay(val entry: JournalEntry, val fromBalanceAfter: Money, val toBalanceAfter: Money) : Result()
        data object KeyReused : Result()
        data object CompanyNotFound : Result()

        /** Either book is unknown or belongs to another Company. */
        data object AccountNotFound : Result()

        /** Either account is not a cash or bank book. */
        data object NotACashBook : Result()
        data object SameAccount : Result()
        data object InvalidAmount : Result()
        data object NoOpenPeriod : Result()
    }

    fun execute(request: Request): Result {
        val company = companyRepository.findById(request.companyId) ?: return Result.CompanyNotFound
        val from = accountRepository.findById(request.fromAccountId)?.takeIf { it.companyId == request.companyId } ?: return Result.AccountNotFound
        val to = accountRepository.findById(request.toAccountId)?.takeIf { it.companyId == request.companyId } ?: return Result.AccountNotFound
        if (from.cashBookKind == null || to.cashBookKind == null) return Result.NotACashBook
        val currency = company.baseCurrency

        journalEntryRepository.findById(request.entryId)?.let { return replayOrReuse(it, request, from, to, currency) }

        if (from.id == to.id) return Result.SameAccount
        if (request.amount.signum() <= 0 || request.amount.stripTrailingZeros().scale() > currency.defaultFractionDigits) return Result.InvalidAmount
        val amount = Money(request.amount.setScale(currency.defaultFractionDigits), currency)
        val period = periodRepository.findAllByCompany(request.companyId).firstOrNull { it.allowsPosting() } ?: return Result.NoOpenPeriod

        val lines = listOf(
            JournalLine(to.id, amount, TransactionSide.DEBIT),
            JournalLine(from.id, amount, TransactionSide.CREDIT)
        )
        return when (
            val outcome = postJournalEntryUseCase.executeOnce(
                PostJournalEntryUseCase.Request(request.companyId, period.id, request.date, lines, JournalSource.CASH_BOOK, request.description),
                request.entryId
            )
        ) {
            is PostJournalEntryUseCase.OnceResult.Posted -> {
                val fromAfter = journalEntryRepository.balanceOfBook(from, currency)
                val warnings = if (fromAfter.amount.signum() < 0) {
                    listOf(RecordCashBookEntryUseCase.Warning(if (from.cashBookKind == CashBookKind.BANK) "bank_overdrawn" else "cash_below_zero", from.id, fromAfter))
                } else emptyList()
                Result.Posted(outcome.entry, fromAfter, journalEntryRepository.balanceOfBook(to, currency), warnings)
            }
            is PostJournalEntryUseCase.OnceResult.AlreadyExists -> replayOrReuse(outcome.entry, request, from, to, currency)
            is PostJournalEntryUseCase.OnceResult.Refused -> when (outcome.result) {
                is PostJournalEntryResult.PeriodNotFound, is PostJournalEntryResult.PeriodNotOpen -> Result.NoOpenPeriod
                is PostJournalEntryResult.AccountNotFound -> Result.AccountNotFound
                is PostJournalEntryResult.InvalidLines -> Result.InvalidAmount
                is PostJournalEntryResult.Success -> error("executeOnce never refuses with a Success")
            }
        }
    }

    private fun replayOrReuse(existing: JournalEntry, request: Request, from: Account, to: Account, currency: Currency): Result {
        val fromLine = existing.lines.singleOrNull { it.accountId == request.fromAccountId }
        val toLine = existing.lines.singleOrNull { it.accountId == request.toAccountId }
        val same = existing.lines.size == 2 && fromLine != null && toLine != null &&
            fromLine.side == TransactionSide.CREDIT && toLine.side == TransactionSide.DEBIT &&
            fromLine.amount.amount.compareTo(request.amount) == 0 && toLine.amount.amount.compareTo(request.amount) == 0 &&
            existing.date == request.date &&
            (existing.description?.takeIf { it.isNotBlank() }) == (request.description?.takeIf { it.isNotBlank() })
        return if (same) {
            Result.Replay(existing, journalEntryRepository.balanceOfBook(from, currency), journalEntryRepository.balanceOfBook(to, currency))
        } else Result.KeyReused
    }
}
