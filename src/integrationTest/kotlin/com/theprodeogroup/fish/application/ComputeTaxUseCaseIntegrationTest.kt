package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tax.TaxRule
import com.theprodeogroup.fish.domain.tax.TaxType
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseConfig
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseMigrator
import com.theprodeogroup.fish.infrastructure.persistence.ExposedAccountRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedJournalEntryRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPeriodRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedTaxComputationRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedTaxRuleRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedTenantRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 20)

/**
 * `ComputeTaxUseCaseTest` (`src/test`) uses in-memory fakes - exercises
 * the same use case against real Exposed repositories and a live
 * Postgres database (docs/DDD_Design.md Section 10.10). Skips (not
 * fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class ComputeTaxUseCaseIntegrationTest {

    private val periodRepository = ExposedPeriodRepository()
    private val accountRepository = ExposedAccountRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
    private val taxComputationRepository = ExposedTaxComputationRepository()
    private val taxRuleRepository = ExposedTaxRuleRepository()
    private val tenantRepository = ExposedTenantRepository()
    private val companyRepository = ExposedCompanyRepository()
    private val useCase = ComputeTaxUseCase(periodRepository, accountRepository, journalEntryRepository, taxComputationRepository)

    @BeforeEach
    fun setUp() {
        assumeTrue(
            System.getenv("FISH_DB_USER") != null && System.getenv("FISH_DB_PASSWORD") != null,
            "FISH_DB_USER/FISH_DB_PASSWORD not set - skipping (see docs/DDD_Design.md Section 10 for local setup)"
        )
        val dataSource = DatabaseConfig.dataSource()
        DatabaseMigrator.migrate(dataSource)
        DatabaseConfig.connectExposed(dataSource)
    }

    @Test
    fun `given a profitable Period against a real database, when executed, then it computes the correct tax due`() {
        // A real, persisted Company - tax_computations.company_id now carries a
        // real FK, so a fabricated CompanyId that was never actually saved (this
        // test's original bug) fails loudly here, exactly what this integration
        // test exists to catch.
        val tenant = Tenant.onboard("Tax Test Tenant ${UUID.randomUUID()}", TenantSegment.INTERNAL_VENTURE, GBP)
        tenantRepository.save(tenant)
        val company = Company.create(tenant.id, "Tax Test Co", ClientType.NON_PROFIT, "GB", GBP)
        companyRepository.save(company)
        val companyId = company.id
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val cash = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash")
        val revenue = Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales")
        val expense = Account.create(companyId, AccountType.EXPENSE, null, "5000", "Overheads")
        accountRepository.save(cash)
        accountRepository.save(revenue)
        accountRepository.save(expense)

        val sale = JournalEntry.create(
            period.id, TODAY,
            listOf(
                JournalLine(cash.id, Money(BigDecimal("2000.00"), GBP), TransactionSide.DEBIT),
                JournalLine(revenue.id, Money(BigDecimal("2000.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        sale.post()
        journalEntryRepository.save(sale)
        val overhead = JournalEntry.create(
            period.id, TODAY,
            listOf(
                JournalLine(expense.id, Money(BigDecimal("800.00"), GBP), TransactionSide.DEBIT),
                JournalLine(cash.id, Money(BigDecimal("800.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        overhead.post()
        journalEntryRepository.save(overhead)

        // A real, non-ephemeral database - jurisdiction+taxType has a UNIQUE
        // constraint, so a fixed literal would collide with a previous run's
        // leftover row. Same fix as TenancyRepositoriesIntegrationTest's
        // uniqueEmail() helper.
        val taxRule = TaxRule.create("Sierra Leone ${UUID.randomUUID()}", TaxType.CORPORATE_INCOME_TAX, BigDecimal("0.30"))
        taxRuleRepository.save(taxRule)
        val result = useCase.execute(ComputeTaxUseCase.Request(companyId, period.id, taxRule, GBP))

        val success = result.shouldBeInstanceOf<ComputeTaxResult.Success>()
        success.computation.taxableProfit shouldBe Money(BigDecimal("1200.00"), GBP)
        success.computation.taxDue shouldBe Money(BigDecimal("360.00"), GBP)

        val reloaded = requireNotNull(taxComputationRepository.findById(success.computation.id))
        reloaded.taxRuleId shouldBe taxRule.id
        reloaded.taxDue shouldBe Money(BigDecimal("360.00"), GBP)
    }
}
