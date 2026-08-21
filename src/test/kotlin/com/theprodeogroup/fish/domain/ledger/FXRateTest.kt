package com.theprodeogroup.fish.domain.ledger

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val USD: Currency = Currency.getInstance("USD")
private val GBP: Currency = Currency.getInstance("GBP")
private val JPY: Currency = Currency.getInstance("JPY")
private val TODAY = LocalDate.of(2026, 8, 21)

class FXRateTest {

    @Test
    fun `converting an amount multiplies by the rate and changes currency`() {
        val rate = FXRate(USD, GBP, BigDecimal("0.79"), TODAY)

        val result = rate.convert(Money(BigDecimal("50000.00"), USD))

        result shouldBe Money(BigDecimal("39500.00"), GBP)
    }

    @Test
    fun `converting an amount in the wrong source currency is rejected`() {
        val rate = FXRate(USD, GBP, BigDecimal("0.79"), TODAY)

        shouldThrow<IllegalArgumentException> { rate.convert(Money(BigDecimal("100.00"), GBP)) }
    }

    @Test
    fun `converting to a zero-decimal currency rounds to the minor unit - Money's own rounding, for free`() {
        val rate = FXRate(USD, JPY, BigDecimal("150.4"), TODAY)

        val result = rate.convert(Money(BigDecimal("100.00"), USD))

        result shouldBe Money(BigDecimal("15040"), JPY)
    }

    @Test
    fun `a rate between the same currency twice is rejected`() {
        shouldThrow<IllegalArgumentException> { FXRate(USD, USD, BigDecimal("1.00"), TODAY) }
    }

    @Test
    fun `a non-positive rate is rejected`() {
        shouldThrow<IllegalArgumentException> { FXRate(USD, GBP, BigDecimal("0"), TODAY) }
        shouldThrow<IllegalArgumentException> { FXRate(USD, GBP, BigDecimal("-0.79"), TODAY) }
    }
}
