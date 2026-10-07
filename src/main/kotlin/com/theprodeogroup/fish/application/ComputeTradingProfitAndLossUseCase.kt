package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.PeriodStatus
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.ManufacturingTradingProfitAndLossAccount
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

/**
 * `GET /companies/{companyId}/reports/trading-profit-and-loss` (2026-10-07,
 * EA/Femi: the Owner Admin's gross margin, operating margin, ROCE and interest
 * cover). Exposes the trading and profit-and-loss split
 * ([ManufacturingTradingProfitAndLossAccount]) that existed in the domain with
 * no route: revenue, cost of sales, gross profit, operating expenses,
 * operating profit, interest, profit before tax, income tax, net profit.
 *
 * Same basis as the existing profit-and-loss route: the Company's currently
 * open Period. No work-in-progress adjustment (a trader or services Company has
 * no WIP account, and a manufacturer's WIP timing is not applied here).
 * `costOfSalesConfigured` / `interestConfigured` on the report say whether any
 * account is tagged, so a caller can tell "nothing to report" from "nobody
 * tagged the account".
 */
class ComputeTradingProfitAndLossUseCase(
    private val companyRepository: CompanyRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    sealed class Result {
        data class Success(val period: Period, val report: ManufacturingTradingProfitAndLossAccount) : Result()
        data object CompanyNotFound : Result()
        data object NoOpenPeriod : Result()
        data object NoAccountsForCompany : Result()
    }

    fun execute(companyId: CompanyId): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound

        val openPeriod = periodRepository.findAllByCompany(companyId)
            .firstOrNull { it.status == PeriodStatus.OPEN }
            ?: return Result.NoOpenPeriod

        val accounts = accountRepository.findAllByCompany(companyId)
        if (accounts.isEmpty()) return Result.NoAccountsForCompany
        val entries = journalEntryRepository.findAllByPeriod(openPeriod.id)

        return Result.Success(
            openPeriod,
            ManufacturingTradingProfitAndLossAccount.of(accounts, entries, null, openPeriod, company.baseCurrency)
        )
    }
}
