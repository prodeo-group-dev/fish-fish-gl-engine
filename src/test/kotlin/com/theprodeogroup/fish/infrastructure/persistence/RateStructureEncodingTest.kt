package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.tax.ExemptionTest
import com.theprodeogroup.fish.domain.tax.MarginalRelief
import com.theprodeogroup.fish.domain.tax.RateStructure
import com.theprodeogroup.fish.domain.tax.Tier
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Pure encode/decode round-trip coverage for `rate_structure_encoding.kt`,
 * with no database involved - `TaxRepositoriesIntegrationTest`'s own
 * round-trip test proves the same thing against a real Postgres TEXT
 * column, but that one only runs when `FISH_DB_USER`/`FISH_DB_PASSWORD`
 * are set. This covers the encoding logic itself unconditionally,
 * matching how `dimension_encoding.kt`'s equivalent has historically only
 * had integration coverage - closing that same gap here since the
 * recursive length-prefix scheme for `ThresholdExemption` is new and
 * genuinely worth a direct test.
 */
class RateStructureEncodingTest {

    @Test
    fun `given a Flat structure, when encoded and decoded, then it round-trips`() {
        val structure = RateStructure.Flat(BigDecimal("0.30"))

        decodeRateStructure(encodeRateStructure(structure)) shouldBe structure
    }

    @Test
    fun `given a Tiered structure with marginal relief, when encoded and decoded, then it round-trips`() {
        val structure = RateStructure.Tiered(
            tiers = listOf(
                Tier(BigDecimal("50000"), BigDecimal("0.19")),
                Tier(null, BigDecimal("0.25"))
            ),
            marginalRelief = MarginalRelief(BigDecimal("50000"), BigDecimal("250000"), BigDecimal("0.015"))
        )

        decodeRateStructure(encodeRateStructure(structure)) shouldBe structure
    }

    @Test
    fun `given a Tiered structure without marginal relief, when encoded and decoded, then it round-trips`() {
        val structure = RateStructure.Tiered(
            tiers = listOf(
                Tier(BigDecimal("1000"), BigDecimal("0.10")),
                Tier(null, BigDecimal("0.20"))
            )
        )

        decodeRateStructure(encodeRateStructure(structure)) shouldBe structure
    }

    @Test
    fun `given a CategorySplit structure, when encoded and decoded, then it round-trips`() {
        val structure = RateStructure.CategorySplit(
            mapOf("resident" to BigDecimal("0.25"), "non-resident" to BigDecimal("0.35"))
        )

        decodeRateStructure(encodeRateStructure(structure)) shouldBe structure
    }

    @Test
    fun `given a ThresholdExemption wrapping a Flat structure, when encoded and decoded, then it round-trips including the nested length-prefixed part`() {
        val structure = RateStructure.ThresholdExemption(
            exemptionTest = ExemptionTest(maxTurnover = BigDecimal("100000000"), maxFixedAssets = BigDecimal("250000000")),
            otherwise = RateStructure.Flat(BigDecimal("0.30"))
        )

        decodeRateStructure(encodeRateStructure(structure)) shouldBe structure
    }

    @Test
    fun `given a ThresholdExemption wrapping a Tiered structure with marginal relief, when encoded and decoded, then the nested tags do not collide`() {
        // Both THRESHOLD and the nested TIERED's ;RELIEF: segment contain
        // ";" and ":" - the exact case the length-prefix scheme exists for.
        val structure = RateStructure.ThresholdExemption(
            exemptionTest = ExemptionTest(maxTurnover = BigDecimal("50000")),
            otherwise = RateStructure.Tiered(
                tiers = listOf(
                    Tier(BigDecimal("50000"), BigDecimal("0.19")),
                    Tier(null, BigDecimal("0.25"))
                ),
                marginalRelief = MarginalRelief(BigDecimal("50000"), BigDecimal("250000"), BigDecimal("0.015"))
            )
        )

        decodeRateStructure(encodeRateStructure(structure)) shouldBe structure
    }

    @Test
    fun `given an unknown tag, when decoded, then it fails clearly`() {
        try {
            decodeRateStructure("BOGUS:1")
            throw AssertionError("expected decodeRateStructure to throw")
        } catch (e: IllegalStateException) {
            e.message shouldBe "Unknown RateStructure tag 'BOGUS' in encoded value 'BOGUS:1'"
        }
    }
}
