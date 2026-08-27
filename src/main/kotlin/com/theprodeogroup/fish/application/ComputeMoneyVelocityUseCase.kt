package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.PeriodStatus
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.ledger.ProfitAndLoss
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * "The rate at which money is being made" - net income for a Company's
 * currently open Period, expressed as a daily rate, for the tenant
 * dashboard's speedometer-style KPI (2026-08-27). A genuine figure
 * derived from real posted [ProfitAndLoss] data, not a fabricated or
 * estimated one - if the Company hasn't posted anything, the rate is
 * honestly zero, not hidden or guessed at.
 *
 * Deliberately period-to-date, not a trailing-N-days average - the
 * open Period's own [Period.startDate] is the only "since when" this
 * codebase already has a real, unambiguous answer for. A trailing
 * window would need deciding an arbitrary length (7 days? 30?) nobody
 * has asked for yet.
 *
 * [daysElapsed] is clamped to at least 1 so day one of a Period (where
 * `ChronoUnit.DAYS.between(startDate, today)` is 0) doesn't divide by
 * zero - the rate on day one is simply that day's entire net income,
 * which is the honest answer for "the rate so far."
 */
class ComputeMoneyVelocityUseCase(
    private val companyRepository: CompanyRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    sealed class Result {
        data class Success(
            val period: Period,
            val netIncome: Money,
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
        val pnl = ProfitAndLoss.of(accounts, entries, openPeriod.id, company.baseCurrency)

        val daysElapsed = maxOf(1L, ChronoUnit.DAYS.between(openPeriod.startDate, asOf))
        val dailyRate = pnl.netIncome / BigDecimal(daysElapsed)

        return Result.Success(openPeriod, pnl.netIncome, dailyRate, daysElapsed)
    }
}
