package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.util.Currency

/**
 * The Profit & Loss / Income and Expenditure Account report
 * (docs/DDD_Design.md Section 2.1/3.1) - `Revenue - Expense` for a single
 * `Period`, a query over already-posted `JournalEntry` data, no new
 * domain invariants. "Income and Expenditure Account" is Purse's own
 * label for the identical computation (its `ClientType.NON_PROFIT`
 * classification) - a report-title concern, not a different class.
 *
 * Unlike `TrialBalance`, Income Statement accounts (Revenue/Expense) are
 * inherently period-bound ("for the month/year ended"), so this is
 * always scoped to one `Period`, not a running snapshot.
 */
class ProfitAndLoss private constructor(
    val companyId: CompanyId,
    val periodId: PeriodId,
    val currency: Currency,
    val totalRevenue: Money,
    val totalExpense: Money
) {
    val netIncome: Money
        get() = totalRevenue - totalExpense

    companion object {
        /** [accounts] should all belong to one Company; only Revenue/Expense accounts contribute to the totals. */
        fun of(
            accounts: List<Account>,
            postedEntries: List<JournalEntry>,
            periodId: PeriodId,
            currency: Currency
        ): ProfitAndLoss {
            require(accounts.isNotEmpty()) { "A ProfitAndLoss report needs at least one Account" }
            val companyId = accounts.first().companyId
            require(accounts.all { it.companyId == companyId }) {
                "All Accounts in a ProfitAndLoss report must belong to the same Company"
            }
            val periodEntries = postedEntries.filter { it.periodId == periodId }
            val balances = accountBalances(accounts, periodEntries, currency)

            val zero = Money(BigDecimal.ZERO, currency)
            val totalRevenue = accounts.filter { it.type == AccountType.REVENUE }
                .fold(zero) { sum, account -> sum + balances.getValue(account.id) }
            val totalExpense = accounts.filter { it.type == AccountType.EXPENSE }
                .fold(zero) { sum, account -> sum + balances.getValue(account.id) }

            return ProfitAndLoss(companyId, periodId, currency, totalRevenue, totalExpense)
        }
    }
}
