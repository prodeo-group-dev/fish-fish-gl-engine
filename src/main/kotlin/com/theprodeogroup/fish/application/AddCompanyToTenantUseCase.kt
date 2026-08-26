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
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.domain.tenancy.TenantRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * Application service for docs/DDD_Design.md Section 9.1's lighter
 * onboarding path - adding another legal entity to an *existing* Tenant
 * (e.g. Purse adding its Sierra Leone entity once UK is already
 * onboarded), as opposed to [OnboardTenantUseCase]'s heavier "onboard a
 * brand-new Tenant" flow. Deliberately does **not** create a new admin
 * `User`/`Membership` - `Membership` is Tenant-scoped, not Company-scoped
 * (Section 3.2), so every admin who already has access to the Tenant
 * automatically has access to any Company added under it. There's
 * nothing to inherit from `Tenant` beyond its identity (`Tenant` itself
 * carries no `ClientType`/jurisdiction/currency fields to default from) -
 * `Request` still needs the same jurisdiction-specific fields
 * [Company.create] always requires.
 *
 * **Returns `null`, not a `ValidationResult`, on failure** - matches this
 * codebase's own established idiom for "operation didn't apply"
 * throughout the domain layer (`PurchaseOrder.send()`,
 * `SalesOrder.deliverLine()`, `Customer.receivePayment()`, etc.), rather
 * than inventing a new Result-wrapping type for the `application` layer.
 * Covers two distinct failure reasons: [Request.tenantId] doesn't resolve
 * to an existing Tenant, or the Tenant exists but rejects the addition
 * (`Tenant.addCompany()` fails once the Tenant is `Closed`).
 *
 * **Only one `Tenant` save, not two** - unlike [OnboardTenantUseCase].
 * There, the Tenant didn't exist yet, so a bare first save was needed
 * before `Company` could reference it via `companies.tenant_id`. Here the
 * Tenant is already persisted (loaded via [TenantRepository.findById]),
 * so `Company` can reference it immediately - no ordering cycle to
 * resolve.
 *
 * **Validates before persisting anything**, to avoid ever saving an
 * orphaned `Company`: [Tenant.addCompany] is checked *before*
 * [CompanyRepository.save] runs, since `addCompany()` is a pure in-memory
 * check against `Tenant.status` with no side effect on failure. A Closed
 * Tenant therefore never results in a `Company` row existing with nothing
 * pointing at it.
 *
 * **Same no-atomicity policy as [OnboardTenantUseCase] (docs/DDD_Design.md
 * Section 10.5), and the same residual risk**: if `companyRepository.save()`
 * succeeds but the following `tenantRepository.save()` fails, the new
 * `Company` row exists (with a correct `tenant_id`) but isn't yet
 * reflected in `Tenant.companyIds` - `CompanyRepository.findAllByTenant()`
 * would find it, `TenantRepository.findById().companyIds` wouldn't, until
 * whatever retry mechanism eventually completes the second save.
 *
 * **Also seeds the new Company's own General Ledger** - same gap and same
 * fix as [OnboardTenantUseCase] (docs/FiSH_GL_Engine_Spec.md Section 7.1
 * `ChartOfAccountsTemplate`, plus an opened first `Period`, plus an
 * optional opening cash/bank balance posted the same way
 * [OnboardTenantUseCase] does). Each Company owns its own books
 * ([Account.companyId]/[Period.companyId]), so a second Company added
 * under an already-active Tenant needs this exactly as much as the
 * Tenant's first one did - nothing about already having a Tenant gives
 * this Company Accounts, a Period, or an opening balance of its own.
 */
class AddCompanyToTenantUseCase(
    private val tenantRepository: TenantRepository,
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
        val openingCashBalance: BigDecimal? = null
    )

    data class Result(
        val tenant: Tenant,
        val company: Company,
        val chartOfAccounts: List<Account>,
        val openingPeriod: Period,
        val openingBalanceEntry: JournalEntry?
    )

    fun execute(request: Request, openingPeriodStartDate: LocalDate = LocalDate.now()): Result? {
        val tenant = tenantRepository.findById(request.tenantId) ?: return null

        val company = Company.create(
            tenant.id, request.companyName, request.clientType, request.jurisdiction, request.companyBaseCurrency
        )

        val additionResult = tenant.addCompany(company.id)
        if (!additionResult.isValid) return null

        companyRepository.save(company)
        tenantRepository.save(tenant)

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

        return Result(tenant, company, chartOfAccounts, openingPeriod, openingBalanceEntry)
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
