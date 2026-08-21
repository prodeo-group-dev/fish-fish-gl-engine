package com.theprodeogroup.fish.domain.tax

import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.ProfitAndLoss
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.Instant
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
 * **Persisted, unlike `TrialBalance`/`ProfitAndLoss`/`WorkingCapital`**
 * (docs/DDD_Design.md Section 10.11) - a deliberate deviation from the
 * "reports are ephemeral, recomputed on demand" precedent every other
 * report type in this codebase follows. Tax computations exist for
 * audit/compliance purposes: a government-ready figure needs to be
 * preserved as it was actually computed and (eventually) reported, not
 * silently replaced by whatever a later recomputation produces after
 * more entries post to the same Period.
 *
 * References [TaxRule] by [taxRuleId], not by embedding the whole
 * object - now that `TaxRule` has real persisted identity (Section
 * 10.11), this matches the "reference other aggregates by ID" pattern
 * used everywhere else in this codebase. [of] still takes the resolved
 * `TaxRule` as an input (the caller already has it, needed for the
 * actual `.rate` arithmetic) - only what gets *stored* on the result
 * changed shape.
 *
 * [taxDue] is floored at zero - a loss-making Period ([taxableProfit]
 * negative) owes no tax, never a negative liability/refund. No
 * loss-carryforward modeling exists (or is implied) - that's a genuinely
 * separate, more complex tax concept, out of scope here.
 */
class TaxComputation private constructor(
    val id: TaxComputationId,
    val companyId: CompanyId,
    val periodId: PeriodId,
    val taxRuleId: TaxRuleId,
    val taxableProfit: Money,
    val taxDue: Money,
    val computedAt: Instant
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
            currency: Currency,
            id: TaxComputationId = TaxComputationId.generate(),
            now: Instant = Instant.now()
        ): TaxComputation {
            require(taxRule.taxType == TaxType.CORPORATE_INCOME_TAX) {
                "TaxComputation only supports CORPORATE_INCOME_TAX for now - ${taxRule.taxType} has no computation defined"
            }

            val profitAndLoss = ProfitAndLoss.of(accounts, postedEntries, periodId, currency)
            val taxableProfit = profitAndLoss.netIncome
            val zero = Money(BigDecimal.ZERO, currency)
            val positiveProfit = if (taxableProfit.amount.signum() > 0) taxableProfit else zero
            val taxDue = positiveProfit * taxRule.rate

            return TaxComputation(id, profitAndLoss.companyId, periodId, taxRule.id, taxableProfit, taxDue, now)
        }

        /**
         * Rebuilds an already-valid TaxComputation from persisted data,
         * bypassing [of]'s recomputation - a saved row already reflects
         * a specific point-in-time calculation that shouldn't silently
         * change on reload even if the underlying Ledger data has since
         * moved on. `internal`, for `TaxComputationRepository`
         * implementations only, matching every other aggregate's
         * `reconstitute()` precedent.
         */
        internal fun reconstitute(
            id: TaxComputationId,
            companyId: CompanyId,
            periodId: PeriodId,
            taxRuleId: TaxRuleId,
            taxableProfit: Money,
            taxDue: Money,
            computedAt: Instant
        ): TaxComputation = TaxComputation(id, companyId, periodId, taxRuleId, taxableProfit, taxDue, computedAt)
    }
}
