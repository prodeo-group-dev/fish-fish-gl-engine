package com.theprodeogroup.fish.domain.ledger

import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val USD: Currency = Currency.getInstance("USD")
private val JPY: Currency = Currency.getInstance("JPY")

// Mano River launch markets (spec Section 2.6/7.12): Sierra Leone, Liberia,
// Guinea, Cote d'Ivoire. GNF and XOF are zero-decimal, same shape as JPY -
// this isn't a hypothetical edge case, it's two of the four launch markets.
private val SLE: Currency = Currency.getInstance("SLE") // Sierra Leone - 2 decimal places
private val LRD: Currency = Currency.getInstance("LRD") // Liberia - 2 decimal places
private val GNF: Currency = Currency.getInstance("GNF") // Guinea - 0 decimal places
private val XOF: Currency = Currency.getInstance("XOF") // Cote d'Ivoire - 0 decimal places

class MoneyTest {

    @Test
    fun `given two Money values in the same currency, when added, then the result has the summed amount and the same currency`() {
        val a = Money(BigDecimal("10.00"), GBP)
        val b = Money(BigDecimal("5.50"), GBP)

        val result = a + b

        result.amount shouldBe BigDecimal("15.50")
        result.currency shouldBe GBP
    }

    @Test
    fun `given two Money values in the same currency, when subtracted, then the result has the difference and the same currency`() {
        val a = Money(BigDecimal("10.00"), GBP)
        val b = Money(BigDecimal("3.50"), GBP)

        val result = a - b

        result.amount shouldBe BigDecimal("6.50")
        result.currency shouldBe GBP
    }

    @Test
    fun `given a Money value, when multiplied by a scalar, then the amount scales and the currency is unchanged`() {
        val a = Money(BigDecimal("2.00"), GBP)

        val result = a * BigDecimal("10")

        result.amount shouldBe BigDecimal("20.00")
        result.currency shouldBe GBP
    }

    @Test
    fun `given a Money value, when divided by a scalar, then the amount divides and rounds to the minor unit`() {
        val a = Money(BigDecimal("60.00"), GBP)

        val result = a / BigDecimal("20")

        result.amount shouldBe BigDecimal("3.00")
    }

    @Test
    fun `given a Money value, when divided by a scalar that doesn't divide evenly, then it rounds rather than throwing`() {
        val a = Money(BigDecimal("10.00"), GBP)

        val result = a / BigDecimal("3")

        result.amount shouldBe BigDecimal("3.33")
    }

    @Test
    fun `given a Money value, when divided by zero, then it fails`() {
        val a = Money(BigDecimal("10.00"), GBP)

        shouldThrow<IllegalArgumentException> { a / BigDecimal.ZERO }
    }

    @Test
    fun `given two Money values in different currencies, when subtracted, then it fails`() {
        val a = Money(BigDecimal("10.00"), GBP)
        val b = Money(BigDecimal("10.00"), USD)

        shouldThrow<IllegalArgumentException> { a - b }
    }

    @Test
    fun `given two Money values in different currencies, when added, then it fails rather than silently producing a wrong number`() {
        val a = Money(BigDecimal("10.00"), GBP)
        val b = Money(BigDecimal("10.00"), USD)

        shouldThrow<IllegalArgumentException> { a + b }
    }

    @Test
    fun `given a Money value, when compared to another in a different currency, then comparison fails rather than silently comparing raw amounts`() {
        val a = Money(BigDecimal("10.00"), GBP)
        val b = Money(BigDecimal("10.00"), USD)

        shouldThrow<IllegalArgumentException> { a.compareTo(b) }
    }

    @Test
    fun `given a Money value, when compared to a larger amount in the same currency, then it compares as less`() {
        val a = Money(BigDecimal("10.00"), GBP)
        val b = Money(BigDecimal("20.00"), GBP)

        (a < b) shouldBe true
    }

    @Test
    fun `given two Money values with the same numeric value but different BigDecimal scales, when compared for equality, then they are equal`() {
        val a = Money(BigDecimal("10.0"), GBP)
        val b = Money(BigDecimal("10.00"), GBP)

        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }

    @Test
    fun `given an amount with more decimal places than the currency allows, when a Money is created, then it is rounded to the currency's minor unit`() {
        val money = Money(BigDecimal("10.005"), GBP)

        money.amount shouldBe BigDecimal("10.00")
    }

    @Test
    fun `given a currency with zero minor units like JPY, when a Money is created, then the amount has no decimal places`() {
        val money = Money(BigDecimal("100.4"), JPY)

        money.amount shouldBe BigDecimal("100")
    }

    @Test
    fun `given Guinean Franc, a zero-decimal Mano River launch currency, when a Money is created, then it has no decimal places`() {
        val money = Money(BigDecimal("50000.7"), GNF)

        money.amount shouldBe BigDecimal("50001")
    }

    @Test
    fun `given West African CFA Franc, a zero-decimal Mano River launch currency, when a Money is created, then it has no decimal places`() {
        val money = Money(BigDecimal("12500.2"), XOF)

        money.amount shouldBe BigDecimal("12500")
    }

    @Test
    fun `given Sierra Leonean Leone and Liberian Dollar, two-decimal Mano River launch currencies, when Money is created, then it rounds to 2 decimal places`() {
        Money(BigDecimal("100.005"), SLE).amount shouldBe BigDecimal("100.00")
        Money(BigDecimal("100.005"), LRD).amount shouldBe BigDecimal("100.00")
    }

    @Test
    fun `given GNF and XOF, when added across the same currency, then the sum stays whole-number scaled`() {
        val a = Money(BigDecimal("1000"), GNF)
        val b = Money(BigDecimal("2500"), GNF)

        (a + b).amount shouldBe BigDecimal("3500")
    }
}
