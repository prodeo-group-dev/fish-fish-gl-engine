package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.time.LocalDate
import java.util.Currency

/**
 * IAS 7's core requirement - reconciling a cash/bank Account's opening
 * balance to its closing balance over a date range (docs/DDD_Design.md
 * Section 2.1). A query/report layer over already-posted `JournalEntry`
 * data, same shape as `TrialBalance`/`WorkingCapital`.
 *
 * **Deliberately does not categorize movements into Operating/Investing/
 * Financing activities**, confirmed 2026-08-12 after tracing the
 * originally-recommended "derive from counter-account type" heuristic
 * against entries already built in this codebase and finding it breaks
 * two ways:
 * 1. `FixedAsset.dispose()`'s cash line's counter-account
 *    (`saleOfFixedAssetAccountId`) is Revenue-typed but conceptually an
 *    Investing activity, not Operating - a pure type-based rule gets
 *    this wrong.
 * 2. Compound multi-line entries (`FixedAsset.dispose()`,
 *    `SalesOrder.deliverLine()`'s COGS lines) have more than one
 *    non-cash line per entry, so "the counter-account" for a given cash
 *    line isn't even well-defined once an entry has 3+ lines.
 *
 * Retrofitting every posting method to explicitly tag cash-flow
 * activity (the heavier alternative originally floated) remains the
 * likely path if/when categorization gets built - not attempted here.
 */
class StatementOfCashFlows private constructor(
    val companyId: CompanyId,
    val cashAccountId: AccountId,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val currency: Currency,
    val openingBalance: Money,
    val closingBalance: Money
) {
    val netCashFlow: Money
        get() = closingBalance - openingBalance

    companion object {
        fun of(
            cashAccount: Account,
            postedEntries: List<JournalEntry>,
            startDate: LocalDate,
            endDate: LocalDate,
            currency: Currency
        ): StatementOfCashFlows {
            require(!endDate.isBefore(startDate)) { "endDate cannot be before startDate" }

            val openingEntries = postedEntries.filter { it.date.isBefore(startDate) }
            val closingEntries = postedEntries.filter { !it.date.isAfter(endDate) }
            val openingBalance = accountBalances(listOf(cashAccount), openingEntries, currency).getValue(cashAccount.id)
            val closingBalance = accountBalances(listOf(cashAccount), closingEntries, currency).getValue(cashAccount.id)

            return StatementOfCashFlows(
                cashAccount.companyId, cashAccount.id, startDate, endDate, currency, openingBalance, closingBalance
            )
        }
    }
}
