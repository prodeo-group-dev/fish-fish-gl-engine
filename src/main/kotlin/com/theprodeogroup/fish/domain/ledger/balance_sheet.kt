package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.util.Currency

/** One Asset/Liability/Equity Account's balance in the report, carrying enough to render a real line item, not just a total. */
data class BalanceSheetLine(
    val accountId: AccountId,
    val code: String,
    val name: String,
    val classification: AccountClassification?,
    val balance: Money
)

/**
 * The Balance Sheet report (docs/DDD_Design.md Section 2.1/3.1) - built
 * on top of `TrialBalance` rather than as its own from-scratch aggregate,
 * per that class's own KDoc ("A future Balance Sheet view would use this
 * to move these lines into the liability section"). A genuine Balance
 * Sheet differs from a raw Trial Balance in two ways this class exists
 * to bridge: Revenue/Expense accounts don't get their own line items
 * (they're period-bound, `ProfitAndLoss`'s domain, not a point-in-time
 * snapshot's), and their net effect folds into Equity as
 * [retainedEarnings] instead - the accumulated surplus/deficit of every
 * posted entry ever, not scoped to the currently-open Period, since this
 * codebase has no period-closing-to-retained-earnings mechanism that
 * would otherwise reset that number each Period.
 */
class BalanceSheet private constructor(
    val companyId: CompanyId,
    val currency: Currency,
    val assetLines: List<BalanceSheetLine>,
    val liabilityLines: List<BalanceSheetLine>,
    val equityLines: List<BalanceSheetLine>,
    val retainedEarnings: Money
) {
    val totalAssets: Money
        get() = assetLines.fold(zero) { sum, line -> sum + line.balance }

    val totalLiabilities: Money
        get() = liabilityLines.fold(zero) { sum, line -> sum + line.balance }

    val totalEquity: Money
        get() = equityLines.fold(retainedEarnings) { sum, line -> sum + line.balance }

    val isBalanced: Boolean
        get() = totalAssets == totalLiabilities + totalEquity

    private val zero: Money
        get() = Money(BigDecimal.ZERO, currency)

    companion object {
        /** [accounts] should all belong to one Company - the Chart of Accounts this report covers. */
        fun of(accounts: List<Account>, postedEntries: List<JournalEntry>, currency: Currency): BalanceSheet {
            require(accounts.isNotEmpty()) { "A BalanceSheet needs at least one Account" }
            val companyId = accounts.first().companyId
            require(accounts.all { it.companyId == companyId }) {
                "All Accounts in a BalanceSheet must belong to the same Company"
            }
            val trialBalance = TrialBalance.of(accounts, postedEntries, currency)
            val balanceByAccountId = trialBalance.lines.associate { it.accountId to it.balance }

            fun linesFor(type: AccountType): List<BalanceSheetLine> = accounts
                .filter { it.type == type }
                .map { BalanceSheetLine(it.id, it.code, it.name, it.classification, balanceByAccountId.getValue(it.id)) }
                .sortedBy { it.code }

            val zero = Money(BigDecimal.ZERO, currency)
            val totalRevenue = trialBalance.lines.filter { it.accountType == AccountType.REVENUE }
                .fold(zero) { sum, line -> sum + line.balance }
            val totalExpense = trialBalance.lines.filter { it.accountType == AccountType.EXPENSE }
                .fold(zero) { sum, line -> sum + line.balance }

            return BalanceSheet(
                companyId, currency,
                assetLines = linesFor(AccountType.ASSET),
                liabilityLines = linesFor(AccountType.LIABILITY),
                equityLines = linesFor(AccountType.EQUITY),
                retainedEarnings = totalRevenue - totalExpense
            )
        }
    }
}
