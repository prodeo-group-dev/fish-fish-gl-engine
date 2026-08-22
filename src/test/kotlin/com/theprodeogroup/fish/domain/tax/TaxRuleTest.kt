package com.theprodeogroup.fish.domain.tax

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TaxRuleTest {

    @Test
    fun `given a valid jurisdiction, tax type, and rate, when created, then it holds the values`() {
        val rule = TaxRule.create("Sierra Leone", TaxType.CORPORATE_INCOME_TAX, BigDecimal("0.30"))

        rule.jurisdiction shouldBe "Sierra Leone"
        rule.taxType shouldBe TaxType.CORPORATE_INCOME_TAX
        rule.rateStructure shouldBe RateStructure.Flat(BigDecimal("0.30"))
    }

    @Test
    fun `given a blank jurisdiction, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            TaxRule.create("", TaxType.CORPORATE_INCOME_TAX, BigDecimal("0.30"))
        }
    }

    @Test
    fun `given a negative rate, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            TaxRule.create("Sierra Leone", TaxType.CORPORATE_INCOME_TAX, BigDecimal("-0.01"))
        }
    }

    @Test
    fun `given a zero rate, when created, then it succeeds`() {
        val rule = TaxRule.create("Sierra Leone", TaxType.CORPORATE_INCOME_TAX, BigDecimal.ZERO)

        rule.rateStructure shouldBe RateStructure.Flat(BigDecimal.ZERO)
    }

    @Test
    fun `given an explicit non-flat RateStructure, when created, then it holds it directly`() {
        val structure = RateStructure.CategorySplit(mapOf("trading" to BigDecimal("0.125"), "passive" to BigDecimal("0.25")))

        val rule = TaxRule.create("Ireland", TaxType.CORPORATE_INCOME_TAX, structure)

        rule.rateStructure shouldBe structure
    }
}
