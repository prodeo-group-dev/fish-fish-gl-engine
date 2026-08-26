package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.MembershipId
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val NAIRA: Currency = Currency.getInstance("NGN")

/**
 * Fakes shared with `OnboardTenantUseCaseTest` live in `TenancyRepositoryFakes.kt`.
 * Unlike `OnboardTenantUseCase`, this flow adds a Company to an *existing*
 * Tenant (docs/DDD_Design.md Section 9.1) - no new admin User/Membership,
 * so only `TenantRepository`/`CompanyRepository` are involved.
 */
class AddCompanyToTenantUseCaseTest {

    private val tenantRepository = FakeTenantRepository()
    private val companyRepository = FakeCompanyRepository()
    private val accountRepository = FakeAccountRepository()
    private val periodRepository = FakePeriodRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = AddCompanyToTenantUseCase(
        tenantRepository, companyRepository, accountRepository, periodRepository, journalEntryRepository
    )

    private fun activeTenant(): Tenant {
        val tenant = Tenant.onboard("Purse", TenantSegment.INTERNAL_VENTURE, GBP)
        val bootstrapCompanyId = CompanyId.generate()
        val bootstrapMembershipId = MembershipId.generate()
        tenant.addCompany(bootstrapCompanyId)
        tenant.addAdminMembership(bootstrapMembershipId)
        tenant.activate()
        tenantRepository.save(tenant)
        return tenant
    }

    private fun request(tenantId: TenantId, currency: Currency = GBP) = AddCompanyToTenantUseCase.Request(
        tenantId = tenantId,
        companyName = "Purse Sierra Leone",
        clientType = ClientType.NON_PROFIT,
        jurisdiction = "SL",
        companyBaseCurrency = currency
    )

    @Test
    fun `given an existing Active Tenant, when a Company is added, then it references that Tenant`() {
        val tenant = activeTenant()

        val result = useCase.execute(request(tenant.id, currency = NAIRA))

        checkNotNull(result)
        result.company.tenantId shouldBe tenant.id
        result.company.name shouldBe "Purse Sierra Leone"
        result.company.jurisdiction shouldBe "SL"
        result.company.baseCurrency shouldBe NAIRA
    }

    @Test
    fun `given an existing Tenant with one Company already, when a second Company is added, then the Tenant references both`() {
        val tenant = activeTenant()
        val existingCompanyId = tenant.companyIds.first()

        val result = useCase.execute(request(tenant.id))

        checkNotNull(result)
        result.tenant.companyIds shouldBe setOf(existingCompanyId, result.company.id)
    }

    @Test
    fun `given a nonexistent Tenant id, when executed, then it returns null and saves nothing`() {
        val result = useCase.execute(request(TenantId.generate()))

        result shouldBe null
        companyRepository.saveCalls shouldBe emptyList()
        accountRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a Closed Tenant, when executed, then it returns null without persisting an orphaned Company`() {
        val tenant = activeTenant()
        tenant.close()
        tenantRepository.save(tenant)

        val result = useCase.execute(request(tenant.id))

        result shouldBe null
        companyRepository.saveCalls shouldBe emptyList()
        accountRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a successful addition, then the Tenant is saved only once - unlike OnboardTenantUseCase`() {
        val tenant = activeTenant()
        tenantRepository.saveCalls.clear()

        useCase.execute(request(tenant.id))

        tenantRepository.saveCalls.size shouldBe 1
    }

    @Test
    fun `given a successful addition, then this new Company gets its own Chart of Accounts and open Period - not the first Company's`() {
        val tenant = activeTenant()

        val result = useCase.execute(request(tenant.id))

        checkNotNull(result)
        result.chartOfAccounts.isNotEmpty() shouldBe true
        result.chartOfAccounts.all { it.companyId == result.company.id } shouldBe true
        result.openingPeriod.companyId shouldBe result.company.id
        result.openingPeriod.allowsPosting() shouldBe true
        periodRepository.findAllByCompany(result.company.id) shouldBe listOf(result.openingPeriod)
    }

    @Test
    fun `given no opening cash balance, when a Company is added, then no opening-balance JournalEntry is posted`() {
        val tenant = activeTenant()

        val result = useCase.execute(request(tenant.id))

        checkNotNull(result)
        result.openingBalanceEntry shouldBe null
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a positive opening cash balance, when a Company is added, then a balanced JournalEntry debits Cash and credits Opening Balance Equity`() {
        val tenant = activeTenant()

        val result = useCase.execute(request(tenant.id).copy(openingCashBalance = BigDecimal("250.00")))

        checkNotNull(result)
        val entry = requireNotNull(result.openingBalanceEntry)
        val cashAccount = result.chartOfAccounts.single { it.code == ChartOfAccountsTemplate.CASH_CODE }
        val openingBalanceEquityAccount = result.chartOfAccounts.single { it.code == ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE }

        val debitLine = entry.lines.single { it.side == TransactionSide.DEBIT }
        val creditLine = entry.lines.single { it.side == TransactionSide.CREDIT }
        debitLine.accountId shouldBe cashAccount.id
        debitLine.amount shouldBe Money(BigDecimal("250.00"), GBP)
        creditLine.accountId shouldBe openingBalanceEquityAccount.id
        journalEntryRepository.findById(entry.id) shouldBe entry
    }

    @Test
    fun `given a negative opening cash balance, when a Company is added, then it throws rather than posting an invalid entry`() {
        val tenant = activeTenant()

        shouldThrow<IllegalArgumentException> {
            useCase.execute(request(tenant.id).copy(openingCashBalance = BigDecimal("-1.00")))
        }
    }
}
