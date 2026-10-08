package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.tax.RateStructure
import com.theprodeogroup.fish.domain.tax.TaxComputationInputs
import com.theprodeogroup.fish.domain.tax.TaxType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * UAT v2.2 W-H1: no `tax_rules` row existed in any environment, so "Compute tax" answered
 * `no_tax_rule` for every Company. `V31` seeds the reference rules; this test decodes the exact
 * strings the migration inserts (the same decoder the repository uses) and checks the figures
 * against docs/UK/UK_Tax_And_Currency_Settings.md and docs/SL/SL_Tax_And_Currency_Settings.md,
 * so a typo in the migration cannot reach a database unnoticed.
 */
class SeededTaxRulesTest {

    private val migration = checkNotNull(javaClass.classLoader.getResource("db/migration/V31__reference_tax_rules.sql")) {
        "V31__reference_tax_rules.sql is missing"
    }.readText()

    private fun seeded(jurisdiction: String): RateStructure {
        val match = Regex("""\('$jurisdiction', '${TaxType.CORPORATE_INCOME_TAX.name}', '([^']+)'\)""").find(migration)
        return decodeRateStructure(checkNotNull(match) { "No seeded rule for $jurisdiction" }.groupValues[1])
    }

    private fun due(structure: RateStructure, profit: String) = structure.computeTaxDue(BigDecimal(profit)).setScale(2, java.math.RoundingMode.HALF_EVEN)

    @Test
    fun `the UK rule is 19 percent to 50k, 25 percent from 250k, with marginal relief between`() {
        val uk = seeded("UK")

        due(uk, "40000") shouldBe BigDecimal("7600.00")      // small profits rate: 19%
        due(uk, "50000") shouldBe BigDecimal("9500.00")      // 19%, and the relief formula agrees at the lower limit
        due(uk, "100000") shouldBe BigDecimal("22750.00")    // 25% less (250000 - 100000) * 3/200
        due(uk, "250000") shouldBe BigDecimal("62500.00")    // 25%, and the relief formula agrees at the upper limit
        due(uk, "300000") shouldBe BigDecimal("75000.00")    // main rate: 25%
    }

    @Test
    fun `the Ireland rule is 12 and a half percent on trading and 25 percent on passive income`() {
        val ie = seeded("IE")

        ie.computeTaxDue(BigDecimal("100000"), TaxComputationInputs(category = "TRADING")).setScale(2) shouldBe BigDecimal("12500.00")
        ie.computeTaxDue(BigDecimal("100000"), TaxComputationInputs(category = "PASSIVE")).setScale(2) shouldBe BigDecimal("25000.00")
    }

    @Test
    fun `the Sierra Leone rule is a flat 30 percent`() {
        val sl = seeded("SL")

        (sl is RateStructure.Flat) shouldBe true
        due(sl, "100000") shouldBe BigDecimal("30000.00")
    }

    @Test
    fun `the seed never overwrites a rule that is already there`() {
        // A rule someone configured by hand must survive the migration.
        Regex("ON CONFLICT \\(jurisdiction, tax_type\\) DO NOTHING").containsMatchIn(migration) shouldBe true
    }
}
