package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.PeriodStatus
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.ledger.ProfitAndLoss
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

/**
 * The GL page's "Profit and loss" report sub-page (2026-08-29, "the GL
 * should also have a reports subpage") - a thin read wrapping
 * [ProfitAndLoss.of] for the Company's currently open Period, same
 * "Compute*UseCase, sealed Result" shape as [ComputeMoneyVelocityUseCase]
 * (which already computes the same [ProfitAndLoss] internally, then
 * re-derives a velocity figure from it - this use case instead exposes
 * the report itself, revenue/expense breakdown included).
 */
class ComputeProfitAndLossUseCase(
    private val companyRepository: CompanyRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    sealed class Result {
        data class Success(val profitAndLoss: ProfitAndLoss) : Result()
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

        return Result.Success(ProfitAndLoss.of(accounts, entries, openPeriod.id, company.baseCurrency))
    }
}
