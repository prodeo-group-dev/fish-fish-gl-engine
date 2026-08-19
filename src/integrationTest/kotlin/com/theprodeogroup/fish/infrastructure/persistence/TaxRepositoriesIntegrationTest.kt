package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tax.TaxComputation
import com.theprodeogroup.fish.domain.tax.TaxRule
import com.theprodeogroup.fish.domain.tax.TaxType
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantSegment
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
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
 * NUMERIC(19,4) always returns scale-4 BigDecimals on read, regardless
 * of the scale a value was written with (`BigDecimal("0.25")` comes back
 * `0.2500`) - the same reason `Money` needed its own scale-independent
 * `equals()`, and the same fix already used in
 * `EcosystemRepositoriesIntegrationTest`. `TaxRule.rate` is a raw
 * `BigDecimal`, not wrapped in `Money`, so it needs this helper instead
 * of `shouldBe`.
 */
private infix fun BigDecimal.shouldEqualNumerically(other: BigDecimal) {
    (this.compareTo(other) == 0) shouldBe true
}

/**
 * Verifies the Tax repositories genuinely round-trip through a real
 * Postgres database (docs/DDD_Design.md Section 10.11) - saving an
 * aggregate and reloading it by ID reconstructs an object identical to
 * what was saved. Skips (not fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD`
 * aren't set, matching every other `integrationTest` class.
 */
class TaxRepositoriesIntegrationTest {

    private val taxRuleRepository = ExposedTaxRuleRepository()
    private val taxComputationRepository = ExposedTaxComputationRepository()
    private val tenantRepository = ExposedTenantRepository()
    private val companyRepository = ExposedCompanyRepository()
    private val periodRepository = ExposedPeriodRepository()

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
    fun `given a TaxRule, when saved and reloaded by id, then every field round-trips`() {
        val jurisdiction = "Liberia ${UUID.randomUUID()}"
        val taxRule = TaxRule.create(jurisdiction, TaxType.CORPORATE_INCOME_TAX, BigDecimal("0.25"))

        taxRuleRepository.save(taxRule)
        val reloaded = requireNotNull(taxRuleRepository.findById(taxRule.id))

        reloaded.id shouldBe taxRule.id
        reloaded.jurisdiction shouldBe jurisdiction
        reloaded.taxType shouldBe TaxType.CORPORATE_INCOME_TAX
        reloaded.rate shouldEqualNumerically BigDecimal("0.25")
    }

    @Test
    fun `given a TaxRule, when found by jurisdiction and tax type, then it is returned`() {
        val jurisdiction = "Guinea ${UUID.randomUUID()}"
        val taxRule = TaxRule.create(jurisdiction, TaxType.CORPORATE_INCOME_TAX, BigDecimal("0.35"))
        taxRuleRepository.save(taxRule)

        val found = taxRuleRepository.findByJurisdictionAndTaxType(jurisdiction, TaxType.CORPORATE_INCOME_TAX)

        found?.id shouldBe taxRule.id
    }

    @Test
    fun `given no TaxRule for a jurisdiction, when looked up, then it returns null`() {
        val found = taxRuleRepository.findByJurisdictionAndTaxType("Nowhere ${UUID.randomUUID()}", TaxType.CORPORATE_INCOME_TAX)

        found shouldBe null
    }

    /** A real Tenant + Company + Period, needed since `tax_computations` carries real FKs to `companies`/`periods`/`tax_rules`. */
    private fun realCompanyAndPeriod(): Pair<CompanyId, PeriodId> {
        val tenant = Tenant.onboard("Tax Test Tenant ${UUID.randomUUID()}", TenantSegment.INTERNAL_VENTURE, GBP)
        tenantRepository.save(tenant)
        val company = Company.create(tenant.id, "Tax Test Co", ClientType.NON_PROFIT, "GB", GBP)
        companyRepository.save(company)
        val period = Period.create(company.id, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        return company.id to period.id
    }

    @Test
    fun `given a TaxComputation, when saved and reloaded by id, then every field round-trips`() {
        val (companyId, periodId) = realCompanyAndPeriod()
        val taxRule = TaxRule.create("Cote d'Ivoire ${UUID.randomUUID()}", TaxType.CORPORATE_INCOME_TAX, BigDecimal("0.25"))
        taxRuleRepository.save(taxRule)
        val computation = TaxComputation.of(
            taxRule,
            listOf(Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales")),
            emptyList(),
            periodId,
            GBP
        )

        taxComputationRepository.save(computation)
        val reloaded = requireNotNull(taxComputationRepository.findById(computation.id))

        reloaded.id shouldBe computation.id
        reloaded.companyId shouldBe companyId
        reloaded.periodId shouldBe periodId
        reloaded.taxRuleId shouldBe taxRule.id
        reloaded.taxableProfit shouldBe computation.taxableProfit
        reloaded.taxDue shouldBe computation.taxDue
    }

    @Test
    fun `given two TaxComputations for one Company, when found by company, then both are returned`() {
        val (companyId, periodId) = realCompanyAndPeriod()
        val taxRule = TaxRule.create("Sierra Leone ${UUID.randomUUID()}", TaxType.CORPORATE_INCOME_TAX, BigDecimal("0.30"))
        taxRuleRepository.save(taxRule)
        val revenue = Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales")

        val computationA = TaxComputation.of(taxRule, listOf(revenue), emptyList(), periodId, GBP)
        val computationB = TaxComputation.of(taxRule, listOf(revenue), emptyList(), periodId, GBP)
        taxComputationRepository.save(computationA)
        taxComputationRepository.save(computationB)

        val found = taxComputationRepository.findAllByCompany(companyId)

        found.map { it.id } shouldContain computationA.id
        found.map { it.id } shouldContain computationB.id
    }
}
