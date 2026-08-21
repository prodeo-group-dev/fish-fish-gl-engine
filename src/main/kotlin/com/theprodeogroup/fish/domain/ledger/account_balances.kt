package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.PostingStatus
import java.math.BigDecimal
import java.util.Currency

/**
 * Whether an entry's lines have real, immutable historical financial
 * effect for reporting purposes - deliberately **not** the same as
 * `PostingStatus.affectsBalance()`, which excludes `REVERSED`.
 *
 * A `Reversed` entry genuinely was posted; its effect is only cancelled
 * by summing it *together with* its paired reversal entry (opposite
 * lines, itself `Posted`) - excluding the original while including the
 * reversal would leave only the reversal's lines counted, doubling the
 * error instead of netting to zero. `Draft`/`Pending`/`Rejected` never
 * had real effect, so they're excluded the same as `affectsBalance()`.
 *
 * `internal`, not file-private - `BankReconciliation` also needs this
 * same filter (only Posted/System/Reversed entries are eligible to
 * reconcile against a bank statement).
 */
internal fun PostingStatus.hasHistoricalEffect(): Boolean =
    this == PostingStatus.POSTED || this == PostingStatus.SYSTEM || this == PostingStatus.REVERSED

/**
 * Sums each [account]'s posted `JournalLine` activity into a single
 * signed `Money` balance, in that account's own `AccountType.normalBalance()`
 * direction - shared by `TrialBalance` and `ProfitAndLoss` (docs/DDD_Design.md
 * Section 2.1/3.1's "query/report layer, no new domain logic" framing).
 *
 * Only entries with real historical effect are counted (see
 * [hasHistoricalEffect] - Draft/Pending/Rejected entries never had any).
 * Lines referencing an Account not in [accounts] are ignored - callers
 * pass the Chart of Accounts they care about (typically one Company's).
 *
 * Assumes every relevant `Money` amount is already in [currency] -
 * mixing currencies throws via `Money.plus()`'s existing guard, matching
 * the rest of the codebase's conservative default (Section 8's
 * multi-currency question is still open, not solved here).
 */
internal fun accountBalances(
    accounts: List<Account>,
    entries: List<JournalEntry>,
    currency: Currency
): Map<AccountId, Money> {
    val zero = Money(BigDecimal.ZERO, currency)
    val balances = accounts.associate { it.id to zero }.toMutableMap()

    entries
        .filter { it.status.hasHistoricalEffect() }
        .flatMap { it.lines }
        .forEach { line ->
            val account = accounts.find { it.id == line.accountId } ?: return@forEach
            val current = balances.getValue(account.id)
            val delta = if (line.side == account.type.normalBalance()) line.amount else zero - line.amount
            balances[account.id] = current + delta
        }

    return balances
}
