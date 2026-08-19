package com.theprodeogroup.fish.domain.tax

import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.ProfitAndLoss
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.util.Currency

/**
 * Computed tax-due record for a Company/Period (docs/DDD_Design.md
 * Section 3.4; spec Section 7.12: "derived directly from a company's
 * posted... ledger transactions for a given period... reuses the
 * existing Account, Period... model"). For the only supported
 * [TaxRule.taxType] ([TaxType.CORPORATE_INCOME_TAX]), that reuse is
 * direct: [taxableProfit] IS `ProfitAndLoss.netIncome` for the same
 * Period - one canonical net-profit calculation, not a second one
 * recomputed here.
 *
 * A pure computed report, same shape as `TrialBalance`/`ProfitAndLoss` -
 * no identity, no persistence, no lifecycle, no repository. Recomputed
 * on demand from already-posted data every time, never stored.
 *
 * [taxDue] is floored at zero - a loss-making Period ([taxableProfit]
 * negative) owes no tax, never a negative liability/refund. No
 * loss-carryforward modeling exists (or is implied) - that's a genuinely
 * separate, more complex tax concept, out of scope here.
 */
class TaxComputation private constructor(
    val companyId: CompanyId,
    val periodId: PeriodId,
    val taxRule: TaxRule,
    val taxableProfit: Money,
    val taxDue: Money
) {
    companion object {
        /**
         * [accounts] should all belong to one Company - passed straight
         * through to `ProfitAndLoss.of()`, which performs its own
         * single-Company validation (throws if violated, not caught or
         * re-wrapped here).
         */
        fun of(
            taxRule: TaxRule,
            accounts: List<Account>,
            postedEntries: List<JournalEntry>,
            periodId: PeriodId,
            currency: Currency
        ): TaxComputation {
            require(taxRule.taxType == TaxType.CORPORATE_INCOME_TAX) {
                "TaxComputation only supports CORPORATE_INCOME_TAX for now - ${taxRule.taxType} has no computation defined"
            }

            val profitAndLoss = ProfitAndLoss.of(accounts, postedEntries, periodId, currency)
            val taxableProfit = profitAndLoss.netIncome
            val zero = Money(BigDecimal.ZERO, currency)
            val positiveProfit = if (taxableProfit.amount.signum() > 0) taxableProfit else zero
            val taxDue = positiveProfit * taxRule.rate

            return TaxComputation(profitAndLoss.companyId, periodId, taxRule, taxableProfit, taxDue)
        }
    }
}
