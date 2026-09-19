package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseConfig
import com.theprodeogroup.fish.infrastructure.persistence.DatabaseMigrator
import com.theprodeogroup.fish.infrastructure.persistence.ExposedAccountRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedCompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedJournalEntryRepository
import com.theprodeogroup.fish.infrastructure.persistence.ExposedPeriodRepository
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")

/**
 * `AddCompanyToTenantUseCaseTest` (`src/test`) uses in-memory fakes that
 * enforce no constraints - they can't catch a real foreign-key ordering
 * mistake. This exercises the same use case against real Exposed
 * repositories and a live Postgres database (docs/DDD_Design.md Section
 * 10.6). Skips (not fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 *
 * No `Tenant` bootstrap here anymore - `Tenant` moved to EA
 * (`docs/Tenancy_Administration_Extraction_DDD_Design.md`, 2026-09-06)
 * and `AddCompanyToTenantUseCase` itself was already decoupled from it
 * (2026-09-05, the WEB->EA handoff): `Request.tenantId` is trusted as
 * already-authorized, not looked up locally - see the use case's own
 * KDoc.
 */
class AddCompanyToTenantUseCaseIntegrationTest {

    private val companyRepository = ExposedCompanyRepository()
    private val accountRepository = ExposedAccountRepository()
    private val periodRepository = ExposedPeriodRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
    private val useCase = AddCompanyToTenantUseCase(
        companyRepository, accountRepository, periodRepository, journalEntryRepository
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
    fun `given a Tenant id, when a Company is added against a real database, then it round-trips fully linked`() {
        val tenantId = TenantId.generate()

        val result = useCase.execute(
            AddCompanyToTenantUseCase.Request(
                tenantId = tenantId, companyName = "Sierra Leone Entity",
                clientType = ClientType.NON_PROFIT, jurisdiction = Jurisdiction.SL, companyBaseCurrency = Currency.getInstance("SLE"),
                fiscalYearStartMonth = 4,
                openingCashBalance = BigDecimal("750.00")
            )
        )

        val reloadedCompany = requireNotNull(companyRepository.findById(result.company.id))
        reloadedCompany.tenantId shouldBe tenantId
        reloadedCompany.jurisdiction shouldBe Jurisdiction.SL

        val reloadedAccounts = accountRepository.findAllByCompany(result.company.id)
        reloadedAccounts.size shouldBe result.chartOfAccounts.size

        val reloadedPeriod = requireNotNull(periodRepository.findById(result.openingPeriod.id))
        reloadedPeriod.companyId shouldBe result.company.id
        reloadedPeriod.allowsPosting() shouldBe true

        val entry = requireNotNull(result.openingBalanceEntry)
        val reloadedEntry = requireNotNull(journalEntryRepository.findById(entry.id))
        reloadedEntry.lines.size shouldBe 2
        // `hasPostedActivity` is internal (no friend-module access from this
        // custom source set) - already covered by the fake-backed unit
        // tests in `src/test`, which do have that access.
    }
}
