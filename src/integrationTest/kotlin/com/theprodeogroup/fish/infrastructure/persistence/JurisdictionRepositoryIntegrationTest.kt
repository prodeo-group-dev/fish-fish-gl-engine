package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.JurisdictionEntry
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies [ExposedJurisdictionRepository] and `V29__jurisdictions.sql`
 * against a real Postgres, same discipline as the other integration
 * tests. Skips (not fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class JurisdictionRepositoryIntegrationTest {

    private val repository = ExposedJurisdictionRepository()

    @BeforeEach
    fun setUp() {
        assumeTrue(
            System.getenv("FISH_DB_USER") != null && System.getenv("FISH_DB_PASSWORD") != null,
            "FISH_DB_USER/FISH_DB_PASSWORD not set - skipping"
        )
        val dataSource = DatabaseConfig.dataSource()
        DatabaseMigrator.migrate(dataSource)
        DatabaseConfig.connectExposed(dataSource)
    }

    @Test
    fun `given a freshly migrated database, when enabled jurisdictions are listed, then the seven originals are there and UK includes Northern Ireland`() {
        val codes = repository.findAllEnabled().map { it.code.code }

        codes.containsAll(listOf("UK", "IE", "NG", "SL", "LR", "GN", "CI")) shouldBe true
        codes.contains("GB") shouldBe false
        repository.findEnabledByCode(Jurisdiction.UK)?.name shouldBe "United Kingdom (including Northern Ireland)"
        repository.findEnabledByCode(Jurisdiction.CI)?.name shouldBe "Côte d'Ivoire"
    }

    @Test
    fun `given a new jurisdiction is saved as data, when looked up, then it is found with no code change - and disabling it hides it again`() {
        val za = Jurisdiction("ZA")

        repository.save(JurisdictionEntry(za, "South Africa", enabled = true))
        repository.findEnabledByCode(za)?.name shouldBe "South Africa"
        repository.findAllEnabled().map { it.code }.contains(za) shouldBe true

        repository.save(JurisdictionEntry(za, "South Africa", enabled = false))
        repository.findEnabledByCode(za) shouldBe null
        repository.findAllEnabled().map { it.code }.contains(za) shouldBe false
    }

    @Test
    fun `given a code that was never registered, when looked up, then it is null`() {
        repository.findEnabledByCode(Jurisdiction("QQ")) shouldBe null
    }

    @Test
    fun `given a freshly migrated database, then each seeded jurisdiction carries its currency - Liberia, Guinea and Cote d Ivoire follow the SLE-only decision`() {
        val currencies = repository.findAllEnabled().associate { it.code.code to it.currency?.currencyCode }

        currencies["UK"] shouldBe "GBP"
        currencies["IE"] shouldBe "EUR"
        currencies["NG"] shouldBe "NGN"
        listOf("SL", "LR", "GN", "CI").forEach { currencies[it] shouldBe "SLE" }
    }

    @Test
    fun `given a jurisdiction saved with a currency, when it is read back, then the currency round-trips - and one saved without stays null`() {
        val withCurrency = Jurisdiction("ZB")
        val without = Jurisdiction("ZC")

        repository.save(JurisdictionEntry(withCurrency, "Zedland B", enabled = true, currency = java.util.Currency.getInstance("USD")))
        repository.save(JurisdictionEntry(without, "Zedland C", enabled = true))

        repository.findEnabledByCode(withCurrency)?.currency?.currencyCode shouldBe "USD"
        repository.findEnabledByCode(without)?.currency shouldBe null
    }
}
