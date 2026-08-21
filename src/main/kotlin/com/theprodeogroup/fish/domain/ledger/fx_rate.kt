package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * A foreign exchange rate between two currencies at a point in time
 * (docs/Sales_Processing_Requirements_Specification.md Section 6, IAS 21) -
 * `1 fromCurrency = rate toCurrency`. [asOf] is the rate's effective
 * date - a spot rate for translation at the transaction date, or a
 * period-end rate for receivable revaluation, depending on which the
 * caller supplies.
 */
class FXRate(
    val fromCurrency: Currency,
    val toCurrency: Currency,
    val rate: BigDecimal,
    val asOf: LocalDate
) {
    init {
        require(fromCurrency != toCurrency) { "An FX rate must be between two different currencies" }
        require(rate.signum() > 0) { "An FX rate must be positive" }
    }

    /** Converts [amount] from [fromCurrency] to [toCurrency] - [Money]'s own constructor rounds to the target currency's minor unit. */
    fun convert(amount: Money): Money {
        require(amount.currency == fromCurrency) {
            "Cannot convert ${amount.currency.currencyCode} using a rate from ${fromCurrency.currencyCode}"
        }
        return Money(amount.amount * rate, toCurrency)
    }
}
