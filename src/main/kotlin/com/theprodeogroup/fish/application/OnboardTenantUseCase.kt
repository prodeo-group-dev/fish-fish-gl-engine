package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.DomainEvent
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
import com.theprodeogroup.fish.domain.tenancy.Membership
import com.theprodeogroup.fish.domain.tenancy.MembershipRepository
import com.theprodeogroup.fish.domain.tenancy.ModuleManagementPreference
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantRepository
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.domain.tenancy.User
import com.theprodeogroup.fish.domain.tenancy.UserRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * Application service for docs/DDD_Design.md Section 9.2 - the first use
 * case in the repo's new `application` package (Section 5). Orchestrates
 * four separate aggregates (`Tenant`, `Company`, `User`, `Membership`)
 * through their existing repository interfaces; deliberately depends on
 * nothing from `infrastructure` - only `domain` types - keeping this
 * layer framework-agnostic, per Section 5's split.
 *
 * Covers steps 1-5, 7, and 8 of Section 9.2 in one call: capture identity
 * (1), create the Tenant (2), create the first Company (3), create the
 * first admin User + Membership (4), leave KYB/KYC at their default
 * Pending (5 - FiSH doesn't perform screening itself, so there's nothing
 * to record yet at onboarding time), activate (7), and return the
 * `TenantActivated`/`TenantOnboarded` events `Tenant.activate()` already
 * raises (8). Step 6 (issuing an API key) is confirmed optional/deferrable
 * and not built here. `AddCompanyToTenantUseCase` (Section 9.1, the
 * lighter "add another jurisdiction to an existing Tenant" flow) is a
 * separate, not-yet-built use case - out of scope for this one.
 *
 * **Also seeds the new Company's actual General Ledger** - a real gap
 * discovered 2026-08-26 doing the first end-to-end click-test of
 * onboarding: this use case created a Tenant/Company/User/Membership but
 * no `Account`s and no `Period`, so a freshly onboarded Company could
 * never post anything at all. [ChartOfAccountsTemplate] (already-spec'd,
 * previously unbuilt - docs/FiSH_GL_Engine_Spec.md Section 7.1) seeds the
 * default Chart of Accounts for [Request.clientType], and a first
 * [Period] ([PeriodType.MONTH], starting [openingPeriodStartDate]) is
 * created and opened immediately - a General Ledger that exists but
 * can't be posted to yet isn't meaningfully "created."
 *
 * **Also posts an opening cash/bank balance, if given one.** Most users
 * onboarding are expected to have incomplete records, not a full opening
 * trial balance - the one anchor value this flow actually asks for and
 * posts is the starting cash/bank figure as of the opening Period's own
 * start date. This is deliberately distinct from docs/DDD_Design.md
 * Section 9.8's separate "anchor date" concept for an *established*
 * Company migrating existing books into FiSH (itemized AR/AP, etc.) -
 * that flow stays unbuilt and out of scope here; this is just the normal
 * starting balance every fresh set of books needs. [Request.openingCashBalance]
 * is optional and nullable - a brand-new Company genuinely starting at
 * zero shouldn't be forced through a balancing entry it doesn't need
 * (progressive/forgiving data entry, not up-front precision). When given,
 * it's posted as one balanced `JournalEntry` - debit
 * [ChartOfAccountsTemplate.CASH_CODE], credit
 * [ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE] - using the same
 * `JournalEntry.create()`/`.post()` mechanics [PostJournalEntryUseCase]
 * already established, inlined here since the Account/Period this entry
 * targets were both just created in-memory, not yet worth a repository
 * round-trip to look back up.
 *
 * **No cross-repository atomicity** - each repository's `save()` commits
 * independently (a deliberate, discussed tradeoff, not an oversight). A
 * failure partway through this flow leaves whatever was already saved in
 * place; worst case is a Draft-status `Tenant` with no `Company`/`Membership`
 * linked yet, or a `Company`/`User`/`Membership` saved but not yet
 * reachable from the `Tenant`'s own join tables - both are inspectable,
 * valid states already representable in the domain model (a Tenant
 * legitimately sits in Draft mid-onboarding), not corrupt data. If a
 * later use case needs real atomicity across repositories, that's a
 * `UnitOfWork`-shaped abstraction to add then, not preemptively here.
 *
 * **Saves `Tenant` twice, not once - a real ordering constraint, not
 * redundancy.** `companies.tenant_id`/`memberships.tenant_id` are real
 * foreign keys to `tenants(id)` (Section 10.3), so the Tenant row must
 * exist before `Company`/`Membership` can be saved at all. But
 * `Tenant`'s own join tables (`tenant_companies`/`tenant_admin_memberships`,
 * also Section 10.3) carry real foreign keys the other direction, to
 * `companies(id)`/`memberships(id)` - so those rows can't be written
 * until `Company`/`Membership` already exist. Two saves resolve the
 * cycle: first a bare Tenant (Draft, empty ID sets) to satisfy the first
 * direction, then `Company`/`User`/`Membership`, then a second full
 * Tenant save (now Active, both ID sets populated) to satisfy the second.
 */
class OnboardTenantUseCase(
    private val tenantRepository: TenantRepository,
    private val companyRepository: CompanyRepository,
    private val userRepository: UserRepository,
    private val membershipRepository: MembershipRepository,
    private val accountRepository: AccountRepository,
    private val periodRepository: PeriodRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val tenantName: String,
        val tenantSegment: TenantSegment,
        val tenantBaseCurrency: Currency,
        val companyName: String,
        val clientType: ClientType,
        val jurisdiction: String,
        val companyBaseCurrency: Currency,
        val fiscalYearStartMonth: Int,
        val adminEmail: String,
        val adminName: String,
        val openingCashBalance: BigDecimal? = null,
        val moduleManagementPreferences: List<ModuleManagementPreference> = emptyList()
    )

    data class Result(
        val tenant: Tenant,
        val company: Company,
        val adminUser: User,
        val adminMembership: Membership,
        val chartOfAccounts: List<Account>,
        val openingPeriod: Period,
        val openingBalanceEntry: JournalEntry?,
        val events: List<DomainEvent>
    )

    fun execute(request: Request, openingPeriodStartDate: LocalDate = LocalDate.now()): Result {
        val tenant = Tenant.onboard(request.tenantName, request.tenantSegment, request.tenantBaseCurrency)
        tenantRepository.save(tenant)

        val company = Company.create(
            tenant.id, request.companyName, request.clientType, request.jurisdiction, request.companyBaseCurrency,
            fiscalYearStartMonth = request.fiscalYearStartMonth,
            moduleManagementPreferences = request.moduleManagementPreferences
        )
        companyRepository.save(company)

        val adminUser = User.create(request.adminEmail, request.adminName)
        userRepository.save(adminUser)

        val adminMembership = Membership.grant(adminUser.id, tenant.id, Role.OWNER_ADMIN)
        membershipRepository.save(adminMembership)

        val chartOfAccounts = ChartOfAccountsTemplate.accountsFor(request.clientType, company.id)
        chartOfAccounts.forEach { accountRepository.save(it) }

        val openingPeriod = Period.create(
            company.id, PeriodType.MONTH, openingPeriodStartDate, openingPeriodStartDate.plusMonths(1)
        )
        val openResult = openingPeriod.open()
        check(openResult.isValid) {
            "OnboardTenantUseCase built an opening Period that failed to open: ${openResult.errors.joinToString()}"
        }
        periodRepository.save(openingPeriod)

        val openingBalanceEntry = postOpeningCashBalance(
            request, company, chartOfAccounts, openingPeriod, openingPeriodStartDate
        )

        tenant.addCompany(company.id)
        tenant.addAdminMembership(adminMembership.id)
        val activation = tenant.activate()
        check(activation.isValid) {
            "OnboardTenantUseCase built a Tenant that failed its own activation preconditions: " +
                activation.errors.joinToString()
        }

        tenantRepository.save(tenant)

        return Result(
            tenant, company, adminUser, adminMembership, chartOfAccounts, openingPeriod, openingBalanceEntry, tenant.pullDomainEvents()
        )
    }

    private fun postOpeningCashBalance(
        request: Request,
        company: Company,
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
            "OnboardTenantUseCase built an opening-balance JournalEntry that failed to post: ${posting.errors.joinToString()}"
        }

        cashAccount.recordActivity()
        openingBalanceEquityAccount.recordActivity()
        accountRepository.save(cashAccount)
        accountRepository.save(openingBalanceEquityAccount)
        journalEntryRepository.save(entry)

        return entry
    }
}
