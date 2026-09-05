package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.domain.tenancy.TenantId
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * Application service for docs/DDD_Design.md Section 9.1's lighter
 * onboarding path - adding a legal entity under a Tenant, as opposed to
 * `OnboardTenantUseCase`'s heavier flow. Deliberately does **not** create
 * a new admin `User`/`Membership` - `Membership` is Tenant-scoped, not
 * Company-scoped (Section 3.2), so every admin who already has access to
 * the Tenant automatically has access to any Company added under it.
 *
 * **No longer validates the Tenant itself** (docs/Tenancy_Administration_Extraction_DDD_Design.md
 * WEB→EA+GL handoff, 2026-09-05) - `Tenant` now lives in EA, not GL, so
 * there is nothing local left to load or check. [Request.tenantId] is
 * trusted as-is: it's already been authorized by `authorizeTenantForWrite`
 * (`Auth.kt`), which resolves the caller's membership via EA and would
 * reject a request for a Tenant EA itself doesn't recognize. This is also
 * why `execute()` no longer returns nullable - there's no local
 * precondition left to fail before creating the Company. This same
 * change is what makes this use case (and its route,
 * `POST /tenants/{tenantId}/companies`) work identically whether
 * [Request.tenantId] is a brand-new Tenant that only exists in EA or one
 * GL happens to already have a stale local copy of - it never mattered
 * which, now that nothing here reads the local copy.
 *
 * **Seeds the new Company's own General Ledger** (docs/FiSH_GL_Engine_Spec.md
 * Section 7.1 `ChartOfAccountsTemplate`, plus an opened first `Period`,
 * plus an optional opening cash/bank balance). Each Company owns its own
 * books ([Account.companyId]/[Period.companyId]).
 */
class AddCompanyToTenantUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val periodRepository: PeriodRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val tenantId: TenantId,
        val companyName: String,
        val clientType: ClientType,
        val jurisdiction: String,
        val companyBaseCurrency: Currency,
        val fiscalYearStartMonth: Int,
        val openingCashBalance: BigDecimal? = null
    )

    data class Result(
        val company: Company,
        val chartOfAccounts: List<Account>,
        val openingPeriod: Period,
        val openingBalanceEntry: JournalEntry?
    )

    fun execute(request: Request, openingPeriodStartDate: LocalDate = LocalDate.now()): Result {
        val company = Company.create(
            request.tenantId, request.companyName, request.clientType, request.jurisdiction, request.companyBaseCurrency,
            fiscalYearStartMonth = request.fiscalYearStartMonth
        )
        companyRepository.save(company)

        val chartOfAccounts = ChartOfAccountsTemplate.accountsFor(request.clientType, company.id)
        chartOfAccounts.forEach { accountRepository.save(it) }

        val openingPeriod = Period.create(
            company.id, PeriodType.MONTH, openingPeriodStartDate, openingPeriodStartDate.plusMonths(1)
        )
        val openResult = openingPeriod.open()
        check(openResult.isValid) {
            "AddCompanyToTenantUseCase built an opening Period that failed to open: ${openResult.errors.joinToString()}"
        }
        periodRepository.save(openingPeriod)

        val openingBalanceEntry = postOpeningCashBalance(request, chartOfAccounts, openingPeriod, openingPeriodStartDate)

        return Result(company, chartOfAccounts, openingPeriod, openingBalanceEntry)
    }

    private fun postOpeningCashBalance(
        request: Request,
        chartOfAccounts: List<Account>,
        openingPeriod: Period,
        openingPeriodStartDate: LocalDate
    ): JournalEntry? {
        val balance = request.openingCashBalance ?: return null
        require(balance.signum() >= 0) { "openingCashBalance cannot be negative: $balance" }
        if (balance.signum() == 0) return null

        val cashAccount = chartOfAccounts.single { it.code == ChartOfAccountsTemplate.CASH_CODE }
        val openingBalanceEquityAccount = chartOfAccounts.single { it.code == ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE }
        val amount = Money(balance, request.companyBaseCurrency)

        val entry = JournalEntry.create(
            openingPeriod.id,
            openingPeriodStartDate,
            listOf(
                JournalLine(cashAccount.id, amount, TransactionSide.DEBIT),
                JournalLine(openingBalanceEquityAccount.id, amount, TransactionSide.CREDIT)
            ),
            JournalSource.SYSTEM,
            "Opening cash/bank balance"
        )
        val posting = entry.post()
        check(posting.isValid) {
            "AddCompanyToTenantUseCase built an opening-balance JournalEntry that failed to post: ${posting.errors.joinToString()}"
        }

        cashAccount.recordActivity()
        openingBalanceEquityAccount.recordActivity()
        accountRepository.save(cashAccount)
        accountRepository.save(openingBalanceEquityAccount)
        journalEntryRepository.save(entry)

        return entry
    }
}
