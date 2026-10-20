package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.ledger.findOwnedBy
import com.theprodeogroup.fish.domain.ledger.hasHistoricalEffect
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * `GET /companies/{companyId}/accounts/{accountId}/postings` - the ledger postings on one account in a date range, with the
 * net (debits minus credits) before the range, so a sub-ledger such as Cash and Bank Books can build the book of an account
 * and a bank reconciliation without GL holding any of that state (docs/Cash_And_Bank_Books_Extraction_Assessment.md, Femi
 * 2026-10-19: CBB becomes its own service). Works for any account of the Company. It writes nothing.
 *
 * Every entry with a historical effect (posted or reversed) that has a line on the account is a posting; a reversal is its own
 * posting with [JournalEntry.reversalOfEntryId] set, and the original names the entry that reversed it
 * ([Posting.reversedByEntryId], the same rule the cash book has always used). Each posting carries all of the entry's lines so
 * the caller can name the counter-accounts, and whether the entry's period still accepts postings so the caller can offer
 * Undo only where it can work.
 */
class ComputeAccountPostingsUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val periodRepository: PeriodRepository
) {
    data class Posting(val entry: JournalEntry, val reversedByEntryId: JournalEntryId?, val periodOpen: Boolean)

    sealed class Result {
        data class Success(
            val account: Account,
            val currency: Currency,
            val from: LocalDate,
            val to: LocalDate,
            /** Debits minus credits on the account before [from]. */
            val openingNet: Money,
            val postings: List<Posting>,
            /** Every account named by a posting's lines, by id. */
            val accountsById: Map<AccountId, Account>
        ) : Result()
        data object CompanyNotFound : Result()
        data object AccountNotFound : Result()
        data object InvalidRange : Result()
        data class RangeTooLarge(val rowCount: Int, val limit: Int) : Result()
    }

    fun execute(companyId: CompanyId, accountId: AccountId, from: LocalDate?, to: LocalDate?, today: LocalDate = LocalDate.now()): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound
        val account = accountRepository.findById(accountId)?.takeIf { it.companyId == companyId } ?: return Result.AccountNotFound
        val rangeFrom = from ?: today.withDayOfMonth(1)
        val rangeTo = to ?: today
        if (rangeTo.isBefore(rangeFrom)) return Result.InvalidRange

        val currency = company.baseCurrency
        val zero = Money(BigDecimal.ZERO, currency)
        fun net(entry: JournalEntry): Money = entry.lines.filter { it.accountId == account.id }.fold(zero) { sum, line ->
            if (line.side == TransactionSide.DEBIT) sum + line.amount else sum - line.amount
        }

        val touching = journalEntryRepository.findAllByAccount(account.id)
            .filter { it.status.hasHistoricalEffect() && it.lines.any { line -> line.accountId == account.id } }
        val opening = touching.filter { it.date.isBefore(rangeFrom) }.fold(zero) { sum, entry -> sum + net(entry) }
        val inRange = touching.filter { !it.date.isBefore(rangeFrom) && !it.date.isAfter(rangeTo) }
            .sortedWith(compareBy<JournalEntry> { it.date }.thenBy { it.recordedSeq ?: Long.MAX_VALUE }.thenBy { it.id.value })
        if (inRange.size > ROW_LIMIT) return Result.RangeTooLarge(inRange.size, ROW_LIMIT)

        val periodOpen = mutableMapOf<PeriodId, Boolean>()
        val postings = inRange.map { entry ->
            Posting(
                entry = entry,
                reversedByEntryId = touching.firstOrNull { it.reversalOfEntryId == entry.id }?.id,
                periodOpen = periodOpen.getOrPut(entry.periodId) { periodRepository.findOwnedBy(entry.periodId, companyId)?.allowsPosting() == true }
            )
        }
        val accountsById = accountRepository.findAllByCompany(companyId).associateBy { it.id }
            .filterKeys { id -> inRange.any { entry -> entry.lines.any { it.accountId == id } } }
        return Result.Success(account, currency, rangeFrom, rangeTo, opening, postings, accountsById)
    }

    companion object {
        const val ROW_LIMIT = 5000
    }
}

/**
 * `GET /companies/{companyId}/accounts/{accountId}/balance?asOf=` - the balance of one account as at a date: the net of every
 * entry with a historical effect dated on or before it, as a positive amount and the side it falls on (a zero balance reports the
 * account type's normal side). Wanted by the reconciliation tie-out (CBB) and by Payroll's sub-ledger check. Writes nothing.
 */
class ComputeAccountBalanceUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    sealed class Result {
        data class Success(val account: Account, val currency: Currency, val asOf: LocalDate, val balance: Money, val side: TransactionSide) : Result()
        data object CompanyNotFound : Result()
        data object AccountNotFound : Result()
    }

    fun execute(companyId: CompanyId, accountId: AccountId, asOf: LocalDate?, today: LocalDate = LocalDate.now()): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound
        val account = accountRepository.findById(accountId)?.takeIf { it.companyId == companyId } ?: return Result.AccountNotFound
        val date = asOf ?: today
        val zero = Money(BigDecimal.ZERO, company.baseCurrency)

        val net = journalEntryRepository.findAllByAccount(account.id)
            .filter { it.status.hasHistoricalEffect() && !it.date.isAfter(date) }
            .flatMap { it.lines }.filter { it.accountId == account.id }
            .fold(zero) { sum, line -> if (line.side == TransactionSide.DEBIT) sum + line.amount else sum - line.amount }

        val side = when {
            net.amount.signum() > 0 -> TransactionSide.DEBIT
            net.amount.signum() < 0 -> TransactionSide.CREDIT
            account.type == AccountType.ASSET || account.type == AccountType.EXPENSE -> TransactionSide.DEBIT
            else -> TransactionSide.CREDIT
        }
        val balance = if (net.amount.signum() < 0) Money(net.amount.negate(), net.currency) else net
        return Result.Success(account, company.baseCurrency, date, balance, side)
    }
}
