package com.theprodeogroup.fish.domain.ledger

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * An amount paired with a currency - never a bare number (docs/DDD_Design.md
 * Section 3.1). Foundational to the Ledger context; built before Account/
 * JournalEntry per the Section 6 build order.
 *
 * The amount is always normalized to the currency's minor unit (2 decimal
 * places for GBP/USD, 0 for JPY, etc.) using banker's rounding, so two Money
 * values representing the same amount are always equal regardless of how
 * many decimal places they were constructed with - avoids the classic
 * BigDecimal scale/equality footgun (e.g. "10.0" != "10.00" under the raw
 * BigDecimal.equals()).
 *
 * Arithmetic and comparison reject mixing currencies by throwing, rather
 * than silently producing a meaningless result.
 */
class Money(amount: BigDecimal, val currency: Currency) : Comparable<Money> {

    val amount: BigDecimal = amount.setScale(currency.defaultFractionDigits, RoundingMode.HALF_EVEN)

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(amount + other.amount, currency)
    }

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return amount.compareTo(other.amount)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Money) return false
        return currency == other.currency && amount.compareTo(other.amount) == 0
    }

    override fun hashCode(): Int = 31 * currency.hashCode() + amount.hashCode()

    override fun toString(): String = "$amount ${currency.currencyCode}"

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) {
            "Cannot operate on Money in different currencies: ${currency.currencyCode} vs ${other.currency.currencyCode}"
        }
    }
}
