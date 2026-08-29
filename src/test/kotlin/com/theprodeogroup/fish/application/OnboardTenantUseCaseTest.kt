package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.tenancy.ManagedModule
import com.theprodeogroup.fish.domain.tenancy.ModuleManagementPreference
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.TenantOnboarded
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.domain.tenancy.TenantStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")

/** Fakes shared across this package's use-case tests live in `TenancyRepositoryFakes.kt`. */
class OnboardTenantUseCaseTest {

    private val tenantRepository = FakeTenantRepository()
    private val companyRepository = FakeCompanyRepository()
    private val userRepository = FakeUserRepository()
    private val membershipRepository = FakeMembershipRepository()
    private val accountRepository = FakeAccountRepository()
    private val periodRepository = FakePeriodRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = OnboardTenantUseCase(
        tenantRepository, companyRepository, userRepository, membershipRepository, accountRepository, periodRepository, journalEntryRepository
    )

    private fun validRequest(segment: TenantSegment = TenantSegment.INTERNAL_VENTURE) = OnboardTenantUseCase.Request(
        tenantName = "Purse",
        tenantSegment = segment,
        tenantBaseCurrency = GBP,
        companyName = "Purse UK",
        clientType = ClientType.NON_PROFIT,
        jurisdiction = "GB",
        companyBaseCurrency = GBP,
        adminEmail = "founder@purse.example",
        adminName = "Founding Admin"
    )

    @Test
    fun `given a valid request, when executed, then Tenant Company User and Membership are all created and linked`() {
        val result = useCase.execute(validRequest())

        result.tenant.name shouldBe "Purse"
        result.company.tenantId shouldBe result.tenant.id
        result.adminMembership.userId shouldBe result.adminUser.id
        result.adminMembership.tenantId shouldBe result.tenant.id
        result.adminMembership.role shouldBe Role.OWNER_ADMIN
    }

    @Test
    fun `given a valid request, when executed, then the Tenant is Active with both referenced ID sets populated`() {
        val result = useCase.execute(validRequest())

        result.tenant.status shouldBe TenantStatus.ACTIVE
        result.tenant.companyIds shouldBe setOf(result.company.id)
        result.tenant.adminMembershipIds shouldBe setOf(result.adminMembership.id)
    }

    @Test
    fun `given moduleManagementPreferences in the request, when executed, then the Company stores them`() {
        val preferences = listOf(
            ModuleManagementPreference.selfManaged(ManagedModule.GL),
            ModuleManagementPreference.delegatedTo(ManagedModule.HR, "Jane Doe", "jane@example.com")
        )

        val result = useCase.execute(validRequest().copy(moduleManagementPreferences = preferences))

        result.company.moduleManagementPreferences shouldBe preferences
        companyRepository.findById(result.company.id)?.moduleManagementPreferences shouldBe preferences
    }

    @Test
    fun `given a valid request, when executed, then every aggregate is persisted via its repository`() {
        val result = useCase.execute(validRequest())

        tenantRepository.findById(result.tenant.id) shouldBe result.tenant
        companyRepository.findById(result.company.id) shouldBe result.company
        userRepository.findById(result.adminUser.id) shouldBe result.adminUser
        membershipRepository.findById(result.adminMembership.id) shouldBe result.adminMembership
    }

    @Test
    fun `given a valid request, when executed, then Tenant is saved twice - once bare, once fully linked`() {
        useCase.execute(validRequest())

        tenantRepository.saveCalls.size shouldBe 2
        tenantRepository.saveCalls[0].companyIds shouldBe emptySet()
        tenantRepository.saveCalls[0].status shouldBe TenantStatus.DRAFT
        tenantRepository.saveCalls[1].companyIds.size shouldBe 1
        tenantRepository.saveCalls[1].status shouldBe TenantStatus.ACTIVE
    }

    @Test
    fun `given a valid request, when executed, then TenantOnboarded is among the returned domain events`() {
        val result = useCase.execute(validRequest())

        result.events.map { it::class } shouldContain TenantOnboarded::class
    }

    @Test
    fun `given an external B2B segment, when executed, then it onboards through the same flow`() {
        val result = useCase.execute(validRequest(segment = TenantSegment.EXTERNAL_B2B))

        result.tenant.segment shouldBe TenantSegment.EXTERNAL_B2B
        result.tenant.status shouldBe TenantStatus.ACTIVE
    }

    @Test
    fun `given a valid request, when executed, then a Chart of Accounts is seeded for the new Company and persisted`() {
        val result = useCase.execute(validRequest())

        result.chartOfAccounts.isNotEmpty() shouldBe true
        result.chartOfAccounts.all { it.companyId == result.company.id } shouldBe true
        result.chartOfAccounts.map { accountRepository.findById(it.id) } shouldBe result.chartOfAccounts
    }

    @Test
    fun `given a NON_PROFIT clientType, when executed, then the seeded Chart of Accounts uses net-assets equity accounts`() {
        val result = useCase.execute(validRequest())

        result.chartOfAccounts.filter { it.type == AccountType.EQUITY }.map { it.name } shouldBe
            listOf("Unrestricted Net Assets", "Restricted Net Assets", "Opening Balance Equity")
    }

    @Test
    fun `given a start date, when executed, then a one-month opening Period is created open and persisted`() {
        val start = LocalDate.of(2026, 1, 1)
        val result = useCase.execute(validRequest(), openingPeriodStartDate = start)

        result.openingPeriod.companyId shouldBe result.company.id
        result.openingPeriod.startDate shouldBe start
        result.openingPeriod.endDate shouldBe start.plusMonths(1)
        result.openingPeriod.allowsPosting() shouldBe true
        periodRepository.findById(result.openingPeriod.id) shouldBe result.openingPeriod
    }

    @Test
    fun `given no opening cash balance, when executed, then no opening-balance JournalEntry is posted`() {
        val result = useCase.execute(validRequest())

        result.openingBalanceEntry shouldBe null
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given an opening cash balance of exactly zero, when executed, then no opening-balance JournalEntry is posted`() {
        val result = useCase.execute(validRequest().copy(openingCashBalance = BigDecimal.ZERO))

        result.openingBalanceEntry shouldBe null
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a positive opening cash balance, when executed, then a balanced JournalEntry debits Cash and credits Opening Balance Equity`() {
        val result = useCase.execute(validRequest().copy(openingCashBalance = BigDecimal("500.00")))

        val entry = requireNotNull(result.openingBalanceEntry)
        val cashAccount = result.chartOfAccounts.single { it.code == ChartOfAccountsTemplate.CASH_CODE }
        val openingBalanceEquityAccount = result.chartOfAccounts.single { it.code == ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE }

        entry.lines.size shouldBe 2
        val debitLine = entry.lines.single { it.side == TransactionSide.DEBIT }
        val creditLine = entry.lines.single { it.side == TransactionSide.CREDIT }
        debitLine.accountId shouldBe cashAccount.id
        debitLine.amount shouldBe Money(BigDecimal("500.00"), GBP)
        creditLine.accountId shouldBe openingBalanceEquityAccount.id
        creditLine.amount shouldBe Money(BigDecimal("500.00"), GBP)
        entry.status shouldBe PostingStatus.POSTED
        journalEntryRepository.findById(entry.id) shouldBe entry
    }

    @Test
    fun `given a positive opening cash balance, when executed, then both referenced Accounts are marked as having posted activity`() {
        val result = useCase.execute(validRequest().copy(openingCashBalance = BigDecimal("500.00")))

        val cashAccount = requireNotNull(accountRepository.findById(result.chartOfAccounts.single { it.code == ChartOfAccountsTemplate.CASH_CODE }.id))
        val openingBalanceEquityAccount = requireNotNull(
            accountRepository.findById(result.chartOfAccounts.single { it.code == ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE }.id)
        )
        cashAccount.hasPostedActivity shouldBe true
        openingBalanceEquityAccount.hasPostedActivity shouldBe true
    }

    @Test
    fun `given a negative opening cash balance, when executed, then it throws rather than posting an invalid entry`() {
        shouldThrow<IllegalArgumentException> {
            useCase.execute(validRequest().copy(openingCashBalance = BigDecimal("-1.00")))
        }
    }
}
