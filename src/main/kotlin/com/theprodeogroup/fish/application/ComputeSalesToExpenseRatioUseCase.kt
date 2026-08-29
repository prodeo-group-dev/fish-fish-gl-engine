package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.PeriodStatus
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.OperatingExpenses
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.ledger.ProfitAndLoss
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.math.BigDecimal
import java.math.MathContext

/**
 * "How efficiently a company converts operating spending into sales"
 * (2026-08-27) - `Rate of Sales / Rate of Operating Expenses`, the third
 * dashboard KPI alongside [ComputeMoneyVelocityUseCase]/[ComputeExpenseVelocityUseCase].
 * The "rate" framing is what makes the ratio meaningful (sales *per day*
 * against spend *per day*), but arithmetically the shared day-count
 * cancels out - this is just [ProfitAndLoss.totalRevenue] over
 * [OperatingExpenses.total] for the Period to date, no `daysElapsed`
 * needed at all.
 *
 * A ratio above 1 means sales are outpacing operating spend (expanding
 * operating leverage); below 1 means the reverse. Deliberately excludes
 * Cost of Sales from the denominator, same as [ComputeExpenseVelocityUseCase] -
 * this isolates organisational/overhead efficiency from production
 * efficiency, which is the whole point of the ratio.
 */
class ComputeSalesToExpenseRatioUseCase(
    private val companyRepository: CompanyRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    sealed class Result {
        data class Success(
            val period: Period,
            val totalRevenue: Money,
            val operatingExpense: Money,
            val ratio: BigDecimal
        ) : Result()
        data object CompanyNotFound : Result()
        data object NoOpenPeriod : Result()
        data object NoAccountsForCompany : Result()
        /** No operating expense posted yet this Period - the ratio is undefined (division by zero), not infinite or zero. */
        data object NoOperatingExpenseYet : Result()
    }

    fun execute(companyId: CompanyId): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound

        val openPeriod = periodRepository.findAllByCompany(companyId)
            .firstOrNull { it.status == PeriodStatus.OPEN }
            ?: return Result.NoOpenPeriod

        val accounts = accountRepository.findAllByCompany(companyId)
        if (accounts.isEmpty()) return Result.NoAccountsForCompany
        val entries = journalEntryRepository.findAllByPeriod(openPeriod.id)

        val pnl = ProfitAndLoss.of(accounts, entries, openPeriod.id, company.baseCurrency)
        val operatingExpenses = OperatingExpenses.of(accounts, entries, openPeriod.id, company.baseCurrency)
        if (operatingExpenses.total.amount.signum() == 0) return Result.NoOperatingExpenseYet

        val ratio = pnl.totalRevenue.amount.divide(operatingExpenses.total.amount, MathContext(10))
        return Result.Success(openPeriod, pnl.totalRevenue, operatingExpenses.total, ratio)
    }
}
