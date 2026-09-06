package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")

/**
 * Verifies `ExposedCompanyRepository` genuinely round-trips through a real
 * Postgres database (docs/DDD_Design.md Section 10.3), same discipline as
 * `CoreLedgerRepositoriesIntegrationTest`. Skips (not fails) if
 * `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 *
 * `Tenant`/`User`/`Membership` moved to EA
 * (`docs/Tenancy_Administration_Extraction_DDD_Design.md`, 2026-09-06) -
 * their own persistence round-trip coverage now lives in EA's test suite,
 * not here. `Company` is the one Tenancy aggregate that stays in GL, so
 * it's the only one still covered by this file.
 */
class TenancyRepositoriesIntegrationTest {

    private val companyRepository = ExposedCompanyRepository()

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
    fun `given a Company with a non-default going-concern status, when saved and reloaded, then every field round-trips`() {
        val tenantId = TenantId.generate()
        val company = Company.create(tenantId, "Purse UK", ClientType.NON_PROFIT, "GB", GBP)
        company.flagSubstantialDoubt()

        companyRepository.save(company)
        val reloaded = requireNotNull(companyRepository.findById(company.id))

        reloaded.id shouldBe company.id
        reloaded.tenantId shouldBe tenantId
        reloaded.name shouldBe "Purse UK"
        reloaded.clientType shouldBe ClientType.NON_PROFIT
        reloaded.jurisdiction shouldBe "GB"
        reloaded.baseCurrency shouldBe GBP
        reloaded.goingConcernStatus shouldBe company.goingConcernStatus
    }

    @Test
    fun `given two Companies under one Tenant, when found by tenant, then both are returned`() {
        val tenantId = TenantId.generate()
        val companyA = Company.create(tenantId, "Scrip Treasury", ClientType.NON_PROFIT, "GB", GBP)
        val companyB = Company.create(tenantId, "Scrip Investments", ClientType.NON_PROFIT, "GB", GBP)
        companyRepository.save(companyA)
        companyRepository.save(companyB)

        val found = companyRepository.findAllByTenant(tenantId)

        found.map { it.id }.toSet() shouldBe setOf(companyA.id, companyB.id)
    }
}
