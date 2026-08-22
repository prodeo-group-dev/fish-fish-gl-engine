package com.theprodeogroup.fish.domain.tax

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * `BigDecimal.equals()` is scale-sensitive ("300.00" != "300.0") - the
 * same reason `Money` needed its own scale-independent `equals()` and
 * `TaxRepositoriesIntegrationTest` needed this exact helper. `computeTaxDue`
 * returns a raw `BigDecimal`, not `Money`, so every assertion here needs
 * it instead of `shouldBe`.
 */
private infix fun BigDecimal.shouldEqualNumerically(other: BigDecimal) {
    (this.compareTo(other) == 0) shouldBe true
}

/**
 * TDD proof that each shape docs/UK, IE, NG (2026-08-22) flagged
 * `TaxRule`'s original flat `rate` as unable to represent actually
 * computes correctly - real figures from those docs, not made-up
 * examples, wherever a concrete real-world case exists.
 */
class RateStructureTest {

    // --- Flat -----------------------------------------------------------

    @Test
    fun `given a Flat structure, when computed, then it multiplies the amount by the rate`() {
        val structure = RateStructure.Flat(BigDecimal("0.30"))

        structure.computeTaxDue(BigDecimal("1000")) shouldEqualNumerically BigDecimal("300")
    }

    @Test
    fun `given a negative Flat rate, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            RateStructure.Flat(BigDecimal("-0.01"))
        }
    }

    // --- Tiered with marginal relief (UK Corporation Tax, 2026/27) ------

    private val ukCorporationTax = RateStructure.Tiered(
        tiers = listOf(
            Tier(upperBound = BigDecimal("50000"), rate = BigDecimal("0.19")),
            Tier(upperBound = null, rate = BigDecimal("0.25"))
        ),
        marginalRelief = MarginalRelief(
            lowerLimit = BigDecimal("50000"),
            upperLimit = BigDecimal("250000"),
            fraction = BigDecimal("0.015")
        )
    )

    @Test
    fun `given profit below the UK small-profits threshold, when computed, then it is taxed at the small-profits rate`() {
        ukCorporationTax.computeTaxDue(BigDecimal("30000")) shouldEqualNumerically BigDecimal("5700")
    }

    @Test
    fun `given profit exactly at the UK marginal-relief lower limit, when computed, then it is taxed at the small-profits rate`() {
        ukCorporationTax.computeTaxDue(BigDecimal("50000")) shouldEqualNumerically BigDecimal("9500")
    }

    @Test
    fun `given profit inside the UK marginal-relief band, when computed, then the main rate is relieved not portioned`() {
        // 100,000 x 0.25 - (250,000 - 100,000) x 0.015 = 25,000 - 2,250 = 22,750
        ukCorporationTax.computeTaxDue(BigDecimal("100000")) shouldEqualNumerically BigDecimal("22750")
    }

    @Test
    fun `given profit exactly at the UK marginal-relief upper limit, when computed, then it is taxed at the main rate with zero relief`() {
        ukCorporationTax.computeTaxDue(BigDecimal("250000")) shouldEqualNumerically BigDecimal("62500")
    }

    @Test
    fun `given profit above the UK marginal-relief upper limit, when computed, then it is taxed at the main rate`() {
        ukCorporationTax.computeTaxDue(BigDecimal("300000")) shouldEqualNumerically BigDecimal("75000")
    }

    @Test
    fun `given marginalRelief set with more than two tiers, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            RateStructure.Tiered(
                tiers = listOf(
                    Tier(BigDecimal("50000"), BigDecimal("0.19")),
                    Tier(BigDecimal("250000"), BigDecimal("0.22")),
                    Tier(null, BigDecimal("0.25"))
                ),
                marginalRelief = MarginalRelief(BigDecimal("50000"), BigDecimal("250000"), BigDecimal("0.015"))
            )
        }
    }

    // --- Tiered without marginal relief (plain progressive bands) -------

    private val plainProgressive = RateStructure.Tiered(
        tiers = listOf(
            Tier(upperBound = BigDecimal("1000"), rate = BigDecimal("0.10")),
            Tier(upperBound = null, rate = BigDecimal("0.20"))
        )
    )

    @Test
    fun `given an amount within the first tier, when computed portion-based, then only the first tier's rate applies`() {
        plainProgressive.computeTaxDue(BigDecimal("500")) shouldEqualNumerically BigDecimal("50")
    }

    @Test
    fun `given an amount spanning two tiers, when computed portion-based, then each portion is taxed at its own tier's rate`() {
        // first 1,000 @ 10% = 100, remaining 500 @ 20% = 100 -> 200
        plainProgressive.computeTaxDue(BigDecimal("1500")) shouldEqualNumerically BigDecimal("200")
    }

    @Test
    fun `given a non-last tier with a null upperBound, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            RateStructure.Tiered(
                tiers = listOf(
                    Tier(null, BigDecimal("0.10")),
                    Tier(null, BigDecimal("0.20"))
                )
            )
        }
    }

    // --- CategorySplit (Ireland trading/passive, 2026) -------------------

    private val irishCorporationTax = RateStructure.CategorySplit(
        mapOf("trading" to BigDecimal("0.125"), "passive" to BigDecimal("0.25"))
    )

    @Test
    fun `given a trading category, when computed, then the trading rate applies`() {
        irishCorporationTax.computeTaxDue(BigDecimal("100000"), TaxComputationInputs(category = "trading")) shouldEqualNumerically BigDecimal("12500")
    }

    @Test
    fun `given a passive category, when computed, then the passive rate applies`() {
        irishCorporationTax.computeTaxDue(BigDecimal("100000"), TaxComputationInputs(category = "passive")) shouldEqualNumerically BigDecimal("25000")
    }

    @Test
    fun `given no category supplied, when computed, then it fails with the known categories listed`() {
        shouldThrow<IllegalArgumentException> {
            irishCorporationTax.computeTaxDue(BigDecimal("100000"))
        }
    }

    @Test
    fun `given an unknown category, when computed, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            irishCorporationTax.computeTaxDue(BigDecimal("100000"), TaxComputationInputs(category = "unknown"))
        }
    }

    @Test
    fun `given an empty category map, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            RateStructure.CategorySplit(emptyMap())
        }
    }

    // --- ThresholdExemption (Nigeria small-company exemption, 2026) -----

    private val nigerianCit = RateStructure.ThresholdExemption(
        exemptionTest = ExemptionTest(maxTurnover = BigDecimal("100000000"), maxFixedAssets = BigDecimal("250000000")),
        otherwise = RateStructure.Flat(BigDecimal("0.30"))
    )

    @Test
    fun `given turnover and fixed assets both under the caps, when computed, then it is fully exempt`() {
        val inputs = TaxComputationInputs(turnover = BigDecimal("80000000"), fixedAssets = BigDecimal("200000000"))

        nigerianCit.computeTaxDue(BigDecimal("50000000"), inputs) shouldEqualNumerically BigDecimal.ZERO
    }

    @Test
    fun `given turnover over the cap, when computed, then the standard rate applies`() {
        val inputs = TaxComputationInputs(turnover = BigDecimal("150000000"), fixedAssets = BigDecimal("200000000"))

        nigerianCit.computeTaxDue(BigDecimal("50000000"), inputs) shouldEqualNumerically BigDecimal("15000000")
    }

    @Test
    fun `given fixed assets over the cap even with turnover under it, when computed, then the standard rate applies`() {
        val inputs = TaxComputationInputs(turnover = BigDecimal("50000000"), fixedAssets = BigDecimal("300000000"))

        nigerianCit.computeTaxDue(BigDecimal("50000000"), inputs) shouldEqualNumerically BigDecimal("15000000")
    }

    @Test
    fun `given no turnover or fixed assets supplied, when computed, then it is not exempt rather than silently passing`() {
        nigerianCit.computeTaxDue(BigDecimal("50000000")) shouldEqualNumerically BigDecimal("15000000")
    }

    @Test
    fun `given neither maxTurnover nor maxFixedAssets set, when created, then ExemptionTest fails`() {
        shouldThrow<IllegalArgumentException> {
            ExemptionTest(maxTurnover = null, maxFixedAssets = null)
        }
    }

    // --- MarginalRelief validation ---------------------------------------

    @Test
    fun `given lowerLimit not less than upperLimit, when creating MarginalRelief, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            MarginalRelief(BigDecimal("250000"), BigDecimal("50000"), BigDecimal("0.015"))
        }
    }
}
