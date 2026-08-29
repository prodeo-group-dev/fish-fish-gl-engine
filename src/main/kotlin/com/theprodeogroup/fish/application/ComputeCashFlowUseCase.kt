package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.PeriodStatus
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.ledger.StatementOfCashFlows
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository

/**
 * The GL page's "Cash flow" report sub-page (2026-08-29, "the GL should
 * also have a reports subpage") - a thin read wrapping
 * [StatementOfCashFlows.of] over the Company's currently open Period,
 * same "Compute*UseCase, sealed Result" shape as the other two report
 * use cases. The "which Account is cash" question is resolved the same
 * way [OnboardTenantUseCase]/[AddCompanyToTenantUseCase] already do it -
 * `code == ChartOfAccountsTemplate.CASH_CODE` - rather than inventing a
 * second convention.
 */
class ComputeCashFlowUseCase(
    private val companyRepository: CompanyRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    sealed class Result {
        data class Success(val statementOfCashFlows: StatementOfCashFlows) : Result()
        data object CompanyNotFound : Result()
        data object NoOpenPeriod : Result()
        data object NoCashAccount : Result()
    }

    fun execute(companyId: CompanyId): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound

        val openPeriod = periodRepository.findAllByCompany(companyId)
            .firstOrNull { it.status == PeriodStatus.OPEN }
            ?: return Result.NoOpenPeriod

        val accounts = accountRepository.findAllByCompany(companyId)
        val cashAccount = accounts.find { it.code == ChartOfAccountsTemplate.CASH_CODE } ?: return Result.NoCashAccount
        val entries = journalEntryRepository.findAllByCompany(companyId)

        return Result.Success(
            StatementOfCashFlows.of(cashAccount, entries, openPeriod.startDate, openPeriod.endDate, company.baseCurrency)
        )
    }
}
