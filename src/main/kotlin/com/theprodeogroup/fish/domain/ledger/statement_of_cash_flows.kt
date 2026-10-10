package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/** One IAS 7 activity category's net cash movement over the statement's date range. Signed - positive is a net inflow. */
data class CashFlowActivityAmount(
    val activity: CashFlowActivity,
    val netAmount: Money
)

/**
 * IAS 7's full requirement - reconciling a cash/bank Account's opening
 * balance to its closing balance over a date range, AND categorizing the
 * movement into Operating/Investing/Financing activities
 * (docs/DDD_Design.md Section 2.1). A query/report layer over
 * already-posted `JournalEntry` data, same shape as `TrialBalance`/
 * `WorkingCapital`.
 *
 * **Categorization reads the `DimensionType.CASH_FLOW_ACTIVITY` tag off
 * the cash/bank `JournalLine` itself**, not derived from the counter-
 * account - confirmed 2026-08-12, after the originally-recommended
 * "derive from counter-account type" heuristic was traced against real
 * entries and found to break two ways (see git history / prior design
 * note): `FixedAsset.dispose()`'s cash line has a Revenue-typed
 * counter-account but is conceptually Investing, and compound multi-line
 * entries have no single well-defined "the counter-account." Tagging the
 * cash line directly, in the method that actually knows the transaction's
 * economic substance (`Customer.receivePayment()`/`Supplier.makePayment()`
 * → OPERATING, `FixedAsset.dispose()` → INVESTING,
 * `CashBookEntry.cashFlowActivity` → caller-supplied, defaulting to
 * OPERATING), sidesteps both problems entirely.
 *
 * A cash line with no `CASH_FLOW_ACTIVITY` tag (an older entry predating
 * this retrofit, or a posting method not yet updated - e.g. Payroll/
 * Prepayment don't touch cash directly today, so weren't retrofitted)
 * falls into [uncategorizedAmount] rather than being silently dropped or
 * crashing - [activityAmounts] plus [uncategorizedAmount] always sums to
 * [netCashFlow].
 */
class StatementOfCashFlows private constructor(
    val companyId: CompanyId,
    /** The first cash or bank account of the pool (kept for callers written when there was exactly one). */
    val cashAccountId: AccountId,
    /** Every cash and bank account whose movements this statement covers (IAS 7: cash and cash equivalents). */
    val cashAccountIds: List<AccountId>,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val currency: Currency,
    val openingBalance: Money,
    val closingBalance: Money,
    val activityAmounts: List<CashFlowActivityAmount>,
    val uncategorizedAmount: Money
) {
    val netCashFlow: Money
        get() = closingBalance - openingBalance

    companion object {
        /**
         * [accounts] (the Company's Chart of Accounts, optional) lets an untagged cash line be
         * categorised from the other lines of its own entry - see [inferActivity]. Without it an
         * untagged line is Uncategorized, exactly as before.
         */
        fun of(
            cashAccount: Account,
            postedEntries: List<JournalEntry>,
            startDate: LocalDate,
            endDate: LocalDate,
            currency: Currency,
            accounts: List<Account> = emptyList()
        ): StatementOfCashFlows = of(listOf(cashAccount), postedEntries, startDate, endDate, currency, accounts)

        /**
         * The same statement over a pool of cash and bank accounts (docs/GL_Cash_And_Bank_Books_SRS.md,
         * FR-CB40): opening, closing and each activity are summed across [cashAccounts], and an entry
         * whose every line is on a pool account (a transfer between cash and bank) is not a cash flow
         * and is skipped. With one account the result is exactly the single-account statement.
         */
        fun of(
            cashAccounts: List<Account>,
            postedEntries: List<JournalEntry>,
            startDate: LocalDate,
            endDate: LocalDate,
            currency: Currency,
            accounts: List<Account> = emptyList()
        ): StatementOfCashFlows {
            require(cashAccounts.isNotEmpty()) { "A statement of cash flows needs at least one cash or bank account" }
            require(!endDate.isBefore(startDate)) { "endDate cannot be before startDate" }
            val cashIds = cashAccounts.map { it.id }.toSet()
            val cashById = cashAccounts.associateBy { it.id }

            val openingEntries = postedEntries.filter { it.date.isBefore(startDate) }
            val closingEntries = postedEntries.filter { !it.date.isAfter(endDate) }
            val periodEntries = postedEntries.filter { !it.date.isBefore(startDate) && !it.date.isAfter(endDate) }

            val zeroMoney = Money(BigDecimal.ZERO, currency)
            val openingBalance = accountBalances(cashAccounts, openingEntries, currency).values.fold(zeroMoney) { sum, balance -> sum + balance }
            val closingBalance = accountBalances(cashAccounts, closingEntries, currency).values.fold(zeroMoney) { sum, balance -> sum + balance }

            val zero = Money(BigDecimal.ZERO, currency)
            val activityTotals = CashFlowActivity.entries.associateWith { zero }.toMutableMap()
            var uncategorized = zero

            val accountsById = accounts.associateBy { it.id }

            periodEntries
                .filter { it.status.hasHistoricalEffect() }
                .forEach { entry ->
                    // Every line on a pool account: money moved between cash and bank, not a cash flow.
                    if (entry.lines.all { it.accountId in cashIds }) return@forEach
                    entry.lines.filter { it.accountId in cashIds }.forEach { line ->
                        val cashAccount = cashById.getValue(line.accountId)
                        val signedAmount = if (line.side == cashAccount.type.normalBalance()) line.amount else zero - line.amount
                        val activity = line.dimensions[DimensionType.CASH_FLOW_ACTIVITY]
                            ?.let { runCatching { CashFlowActivity.valueOf(it) }.getOrNull() }
                            ?: inferActivity(entry, cashIds, accountsById)
                        if (activity != null) {
                            activityTotals[activity] = activityTotals.getValue(activity) + signedAmount
                        } else {
                            uncategorized = uncategorized + signedAmount
                        }
                    }
                }

            val activityAmounts = CashFlowActivity.entries.map {
                CashFlowActivityAmount(it, activityTotals.getValue(it))
            }

            return StatementOfCashFlows(
                cashAccounts.first().companyId, cashAccounts.first().id, cashAccounts.map { it.id }, startDate, endDate, currency,
                openingBalance, closingBalance, activityAmounts, uncategorized
            )
        }

        /**
         * Fallback for a cash line with no CASH_FLOW_ACTIVITY tag (UAT v2.2 W-L3: every manual journal,
         * and so a cash expense or an acquisition's reversal, is untagged). Looks only at the OTHER
         * lines of the same entry and answers only when they all point the same way; otherwise `null`,
         * which stays Uncategorized rather than a guess. A tag always wins, and the opening-balance and
         * suspense accounts are never a flow, so they stay Uncategorized.
         *
         * - Revenue, Expense, current Asset (receivables, stock, prepayments) and current Liability
         *   (payables, VAT, accruals) -> OPERATING
         * - non-current Asset (fixed assets, long-term investments) -> INVESTING
         * - non-current Liability (loans) and Equity (capital, drawings, dividends) -> FINANCING
         */
        private fun inferActivity(entry: JournalEntry, cashIds: Set<AccountId>, accountsById: Map<AccountId, Account>): CashFlowActivity? {
            val counterLines = entry.lines.filter { it.accountId !in cashIds }
            if (counterLines.isEmpty()) return null
            val activities = counterLines.map { line ->
                val counter = accountsById[line.accountId] ?: return null
                if (counter.code == ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE ||
                    counter.code == ChartOfAccountsTemplate.SUSPENSE_ACCOUNT_CODE
                ) return null
                when (counter.type) {
                    AccountType.REVENUE, AccountType.EXPENSE -> CashFlowActivity.OPERATING
                    AccountType.ASSET ->
                        if (counter.classification == AccountClassification.NON_CURRENT) CashFlowActivity.INVESTING else CashFlowActivity.OPERATING
                    AccountType.LIABILITY ->
                        if (counter.classification == AccountClassification.NON_CURRENT) CashFlowActivity.FINANCING else CashFlowActivity.OPERATING
                    AccountType.EQUITY -> CashFlowActivity.FINANCING
                }
            }.toSet()
            return activities.singleOrNull()
        }
    }
}
