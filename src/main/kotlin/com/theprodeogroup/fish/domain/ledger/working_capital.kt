package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.util.Currency

/**
 * Working Capital = Current Assets − Current Liabilities (docs/DDD_Design.md
 * Section 2.1's confirmed generic GL Engine scope) - a query/report layer
 * over already-modeled data, no new domain logic, same shape as
 * `TrialBalance`/`ProfitAndLoss`. Reuses `AccountClassification`
 * (CURRENT/NON_CURRENT, built for IAS 1 balance-sheet presentation) to
 * identify which Asset/Liability accounts count.
 *
 * Confirmed scope 2026-08-12: the raw Working Capital figure only, not
 * Current Ratio/Quick Ratio/Cash Conversion Cycle - no source document
 * defines a formula or threshold for those, and Quick Ratio/CCC would
 * need data this codebase doesn't have yet (a way to distinguish
 * Inventory from Cash/Receivables among Current Assets, and COGS,
 * both deliberately deferred in Inventory Management, Section 2.6).
 * A point-in-time snapshot like `TrialBalance`, not scoped to a `Period`
 * - Current Assets/Liabilities are Balance Sheet accounts.
 */
class WorkingCapital private constructor(
    val companyId: CompanyId,
    val currency: Currency,
    val totalCurrentAssets: Money,
    val totalCurrentLiabilities: Money
) {
    val workingCapital: Money
        get() = totalCurrentAssets - totalCurrentLiabilities

    companion object {
        /** [accounts] should all belong to one Company. */
        fun of(accounts: List<Account>, postedEntries: List<JournalEntry>, currency: Currency): WorkingCapital {
            require(accounts.isNotEmpty()) { "A WorkingCapital report needs at least one Account" }
            val companyId = accounts.first().companyId
            require(accounts.all { it.companyId == companyId }) {
                "All Accounts in a WorkingCapital report must belong to the same Company"
            }
            val balances = accountBalances(accounts, postedEntries, currency)

            val zero = Money(BigDecimal.ZERO, currency)
            val totalCurrentAssets = accounts
                .filter { it.type == AccountType.ASSET && it.classification == AccountClassification.CURRENT }
                .fold(zero) { sum, account -> sum + balances.getValue(account.id) }
            val totalCurrentLiabilities = accounts
                .filter { it.type == AccountType.LIABILITY && it.classification == AccountClassification.CURRENT }
                .fold(zero) { sum, account -> sum + balances.getValue(account.id) }

            return WorkingCapital(companyId, currency, totalCurrentAssets, totalCurrentLiabilities)
        }
    }
}
