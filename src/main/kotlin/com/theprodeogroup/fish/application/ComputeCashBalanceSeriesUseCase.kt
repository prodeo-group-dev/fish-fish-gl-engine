package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.CashBalanceSeries
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate

/**
 * `GET /companies/{companyId}/cash-balance-series?days=30`: the combined end-of-day balance of the Company's cash and
 * bank books over the last N days, for the Business Owner dashboard's cash trend. The books are exactly the ones
 * `ListCashBooksUseCase` lists (active ASSET accounts carrying a cash or bank kind), so the last point agrees with the
 * sum of their balances today. Read-only.
 */
class ComputeCashBalanceSeriesUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    sealed class Result {
        data class Success(val series: CashBalanceSeries) : Result()
        data object CompanyNotFound : Result()
        data object InvalidDays : Result()
    }

    fun execute(companyId: CompanyId, days: Int, today: LocalDate = LocalDate.now(java.time.ZoneOffset.UTC)): Result {
        if (days !in CashBalanceSeries.MIN_DAYS..CashBalanceSeries.MAX_DAYS) return Result.InvalidDays
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound

        val books = accountRepository.findAllByCompany(companyId).filter { it.active && it.cashBookKind != null }
        val entries = books.flatMap { journalEntryRepository.findAllByAccount(it.id) }
        return Result.Success(CashBalanceSeries.of(books, entries, today, days, company.baseCurrency))
    }
}
