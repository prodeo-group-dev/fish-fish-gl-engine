package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.payroll.PayRun
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseConfig
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseMigrator
import com.theprodeogroup.fish.infrastructure.persistence.ExposedAccountRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedJournalEntryRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPayRunRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPeriodRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedTenantRepository
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 24)

/**
 * `PostPayRunUseCaseTest` (`src/test`) uses in-memory fakes - exercises
 * the same use case against real Exposed repositories and a live
 * Postgres database (docs/DDD_Design.md Section 10.17). Skips (not
 * fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class PostPayRunUseCaseIntegrationTest {

    private val payRunRepository = ExposedPayRunRepository()
    private val periodRepository = ExposedPeriodRepository()
    private val accountRepository = ExposedAccountRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
    private val tenantRepository = ExposedTenantRepository()
    private val companyRepository = ExposedCompanyRepository()
    private val useCase = PostPayRunUseCase(
        payRunRepository, periodRepository, accountRepository, journalEntryRepository
    )

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
    fun `given a PayRun with both wages and salaries against a real database, when posted, then everything round-trips correctly`() {
        // A real, persisted Company - pay_runs.company_id carries a real
        // FK (see PostPurchaseOrderUseCaseIntegrationTest's own note -
        // the same class of bug this construction avoids).
        val tenant = Tenant.onboard("PayRun Test Tenant ${java.util.UUID.randomUUID()}", TenantSegment.INTERNAL_VENTURE, GBP)
        tenantRepository.save(tenant)
        val company = Company.create(tenant.id, "PayRun Test Co", ClientType.NON_PROFIT, "GB", GBP)
        companyRepository.save(company)
        val companyId = company.id
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
        val wages = Account.create(companyId, AccountType.EXPENSE, null, "5100", "Wages")
        val salaries = Account.create(companyId, AccountType.EXPENSE, null, "5200", "Salaries")
        val cash = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash")
        accountRepository.save(wages)
        accountRepository.save(salaries)
        accountRepository.save(cash)
        val run = PayRun.create(companyId, TODAY, Money(BigDecimal("1000.00"), GBP), Money(BigDecimal("2000.00"), GBP))
        payRunRepository.save(run)

        val result = useCase.execute(
            PostPayRunUseCase.Request(run.id, period.id, wages.id, salaries.id, cash.id)
        )

        val success = result.shouldBeInstanceOf<PostPayRunResult.Success>()

        val reloadedEntry = requireNotNull(journalEntryRepository.findById(success.journalEntry.id))
        reloadedEntry.status shouldBe PostingStatus.POSTED
        val cashLine = reloadedEntry.lines.single { it.accountId == cash.id }
        cashLine.amount shouldBe Money(BigDecimal("3000.00"), GBP)

        val reloadedWagesAccount = requireNotNull(accountRepository.findById(wages.id))
        reloadedWagesAccount.validateDeletion().isValid shouldBe false
    }
}
