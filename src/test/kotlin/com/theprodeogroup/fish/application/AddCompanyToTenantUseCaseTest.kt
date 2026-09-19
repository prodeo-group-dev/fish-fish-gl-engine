package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val NAIRA: Currency = Currency.getInstance("NGN")

/**
 * Fakes shared with `OnboardTenantUseCaseTest` live in `TenancyRepositoryFakes.kt`.
 * Unlike `OnboardTenantUseCase`, this flow adds a Company under a Tenant
 * (docs/DDD_Design.md Section 9.1) - no new admin User/Membership.
 *
 * **No `TenantRepository` here at all** (docs/Tenancy_Administration_Extraction_DDD_Design.md
 * WEB→EA+GL handoff, 2026-09-05) - `Tenant` now lives in EA, so this use
 * case trusts [Request.tenantId] as-is rather than loading/validating a
 * local `Tenant`. Every test below uses a bare generated `TenantId`, not
 * a `Tenant` domain object - there's deliberately nothing to build one
 * against anymore.
 */
class AddCompanyToTenantUseCaseTest {

    private val companyRepository = FakeCompanyRepository()
    private val accountRepository = FakeAccountRepository()
    private val periodRepository = FakePeriodRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = AddCompanyToTenantUseCase(
        companyRepository, accountRepository, periodRepository, journalEntryRepository
    )

    private fun request(tenantId: TenantId = TenantId.generate(), currency: Currency = GBP) = AddCompanyToTenantUseCase.Request(
        tenantId = tenantId,
        companyName = "Purse Sierra Leone",
        clientType = ClientType.NON_PROFIT,
        jurisdiction = Jurisdiction.SL,
        companyBaseCurrency = currency,
        fiscalYearStartMonth = 1
    )

    @Test
    fun `given a tenantId, when a Company is added, then it references that tenantId`() {
        val tenantId = TenantId.generate()

        val result = useCase.execute(request(tenantId, currency = NAIRA))

        result.company.tenantId shouldBe tenantId
        result.company.name shouldBe "Purse Sierra Leone"
        result.company.jurisdiction shouldBe Jurisdiction.SL
        result.company.baseCurrency shouldBe NAIRA
    }

    @Test
    fun `given a successful addition, then this new Company gets its own Chart of Accounts and open Period - not another Company's`() {
        val result = useCase.execute(request())

        result.chartOfAccounts.isNotEmpty() shouldBe true
        result.chartOfAccounts.all { it.companyId == result.company.id } shouldBe true
        result.openingPeriod.companyId shouldBe result.company.id
        result.openingPeriod.allowsPosting() shouldBe true
        periodRepository.findAllByCompany(result.company.id) shouldBe listOf(result.openingPeriod)
    }

    @Test
    fun `given no opening cash balance, when a Company is added, then no opening-balance JournalEntry is posted`() {
        val result = useCase.execute(request())

        result.openingBalanceEntry shouldBe null
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a positive opening cash balance, when a Company is added, then a balanced JournalEntry debits Cash and credits Opening Balance Equity`() {
        val result = useCase.execute(request().copy(openingCashBalance = BigDecimal("250.00")))

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
        shouldThrow<IllegalArgumentException> {
            useCase.execute(request().copy(openingCashBalance = BigDecimal("-1.00")))
        }
    }
}
