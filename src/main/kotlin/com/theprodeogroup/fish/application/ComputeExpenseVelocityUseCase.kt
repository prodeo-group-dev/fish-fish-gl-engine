package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.PeriodStatus
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.OperatingExpenses
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * "The rate at which money is being expensed - not direct purchases for
 * resale" (2026-08-27) - [ComputeMoneyVelocityUseCase]'s paired KPI, per
 * the dashboard's "sales and expense are like blood pressure" framing:
 * two related vital-sign rates read side by side, not one folded into
 * the other. Same period-to-date, clamped-to-one-day shape as its
 * sibling, substituting [OperatingExpenses] for [com.theprodeogroup.fish.domain.ledger.ProfitAndLoss] -
 * see that class's own KDoc for exactly what it excludes and why.
 */
class ComputeExpenseVelocityUseCase(
    private val companyRepository: CompanyRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    sealed class Result {
        data class Success(
            val period: Period,
            val operatingExpense: Money,
            val dailyRate: Money,
            val daysElapsed: Long
        ) : Result()
        data object CompanyNotFound : Result()
        data object NoOpenPeriod : Result()
        data object NoAccountsForCompany : Result()
    }

    fun execute(companyId: CompanyId, asOf: LocalDate = LocalDate.now()): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound

        val openPeriod = periodRepository.findAllByCompany(companyId)
            .firstOrNull { it.status == PeriodStatus.OPEN }
            ?: return Result.NoOpenPeriod

        val accounts = accountRepository.findAllByCompany(companyId)
        if (accounts.isEmpty()) return Result.NoAccountsForCompany
        val entries = journalEntryRepository.findAllByPeriod(openPeriod.id)
        val operatingExpenses = OperatingExpenses.of(accounts, entries, openPeriod.id, company.baseCurrency)

        val daysElapsed = maxOf(1L, ChronoUnit.DAYS.between(openPeriod.startDate, asOf))
        val dailyRate = operatingExpenses.total / BigDecimal(daysElapsed)

        return Result.Success(openPeriod, operatingExpenses.total, dailyRate, daysElapsed)
    }
}
