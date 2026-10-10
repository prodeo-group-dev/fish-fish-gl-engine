package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.PeriodStatus
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.ledger.StatementOfCashFlows
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate

/**
 * The GL page's "Cash flow" report sub-page (2026-08-29, "the GL should
 * also have a reports subpage") - a thin read wrapping
 * [StatementOfCashFlows.of], same "Compute*UseCase, sealed Result" shape
 * as the other two report use cases. By default it covers the Company's
 * currently open Period; with [execute]'s `from` and `to` (UAT v2.2,
 * month-end and year-end packs) it covers exactly that inclusive range and
 * needs no open Period. The "which Account is cash" question is resolved the
 * same way [OnboardTenantUseCase]/[AddCompanyToTenantUseCase] already do it -
 * `code == ChartOfAccountsTemplate.CASH_CODE` - rather than inventing a
 * second convention. Because the report has that one cash account, a
 * transfer between two cash/bank accounts (a current-asset counter line)
 * would be inferred as Operating; fine now, to be revisited if a second
 * cash account is ever added.
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

    /** Both [from] and [to], or neither (the route enforces that); neither means the open Period. */
    fun execute(companyId: CompanyId, from: LocalDate? = null, to: LocalDate? = null): Result {
        require((from == null) == (to == null)) { "from and to must be given together" }
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound

        val startDate: LocalDate
        val endDate: LocalDate
        if (from != null && to != null) {
            startDate = from
            endDate = to
        } else {
            val openPeriod = periodRepository.findAllByCompany(companyId)
                .firstOrNull { it.status == PeriodStatus.OPEN }
                ?: return Result.NoOpenPeriod
            startDate = openPeriod.startDate
            endDate = openPeriod.endDate
        }

        val accounts = accountRepository.findAllByCompany(companyId)
        // IAS 7: cash and cash equivalents are one pool - every cash and bank account (docs/GL_Cash_And_Bank_Books_SRS.md,
        // FR-CB40). Account 1000 is always in the pool, even before its kind is set, so a Company that has not been
        // backfilled yet reads exactly as it did when 1000 was the only cash account.
        val cashAccounts = accounts.filter { account ->
            account.type == AccountType.ASSET &&
                (account.cashBookKind != null || account.code == ChartOfAccountsTemplate.CASH_CODE)
        }
        if (cashAccounts.isEmpty()) return Result.NoCashAccount
        val entries = journalEntryRepository.findAllByCompany(companyId)

        return Result.Success(
            StatementOfCashFlows.of(cashAccounts, entries, startDate, endDate, company.baseCurrency, accounts)
        )
    }
}
