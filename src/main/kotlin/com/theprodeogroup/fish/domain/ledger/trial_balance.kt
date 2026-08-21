package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.util.Currency

/** One Account's balance in the report, in its own `AccountType.normalBalance()` direction. */
data class TrialBalanceLine(
    val accountId: AccountId,
    val accountType: AccountType,
    val balance: Money
)

/**
 * The Trial Balance report (docs/DDD_Design.md Section 2.1/3.1) - a
 * query/report over already-posted `JournalEntry` data, not new domain
 * logic. Verifies the accounting equation's more general, always-true
 * form: `Sigma(Asset + Expense balances) = Sigma(Liability + Equity +
 * Revenue balances)`, which holds automatically if every posted
 * `JournalEntry` is itself balanced - this report exists to *catch*
 * corruption/bugs/bypassed validation, not to enforce the equation
 * itself.
 *
 * A snapshot as of "all posted activity so far," not scoped to a single
 * `Period` - unlike `ProfitAndLoss`, Balance Sheet-style accounts
 * (Asset/Liability/Equity) are point-in-time, not period-bound
 * (Section 2.1's scope note).
 */
class TrialBalance private constructor(
    val companyId: CompanyId,
    val currency: Currency,
    val lines: List<TrialBalanceLine>
) {
    val totalAssetAndExpense: Money
        get() = sumWhere { it == AccountType.ASSET || it == AccountType.EXPENSE }

    val totalLiabilityEquityRevenue: Money
        get() = sumWhere { it == AccountType.LIABILITY || it == AccountType.EQUITY || it == AccountType.REVENUE }

    val isBalanced: Boolean
        get() = totalAssetAndExpense == totalLiabilityEquityRevenue

    /**
     * IAS 1: a Cash/Asset account with a negative balance (an overdraft)
     * should be *presented* as a Current Liability, not a negative
     * Asset. This is a presentation-layer flag only - it doesn't change
     * [totalAssetAndExpense]/[totalLiabilityEquityRevenue]/[isBalanced],
     * which stay based on each Account's own `normalBalance()` type
     * exactly as before. A future Balance Sheet view would use this to
     * move these lines into the liability section when rendering.
     */
    val overdraftLines: List<TrialBalanceLine>
        get() = lines.filter { it.accountType == AccountType.ASSET && it.balance.amount.signum() < 0 }

    private fun sumWhere(predicate: (AccountType) -> Boolean): Money =
        lines.filter { predicate(it.accountType) }
            .fold(Money(BigDecimal.ZERO, currency)) { sum, line -> sum + line.balance }

    companion object {
        /** [accounts] should all belong to one Company - the Chart of Accounts this report covers. */
        fun of(accounts: List<Account>, postedEntries: List<JournalEntry>, currency: Currency): TrialBalance {
            require(accounts.isNotEmpty()) { "A TrialBalance needs at least one Account" }
            val companyId = accounts.first().companyId
            require(accounts.all { it.companyId == companyId }) {
                "All Accounts in a TrialBalance must belong to the same Company"
            }
            val balances = accountBalances(accounts, postedEntries, currency)
            val lines = accounts.map { TrialBalanceLine(it.id, it.type, balances.getValue(it.id)) }
            return TrialBalance(companyId, currency, lines)
        }
    }
}
