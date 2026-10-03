package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.common.ValidationResult
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

/**
 * Identity of a BankReconciliation aggregate.
 */
@JvmInline
value class BankReconciliationId(val value: UUID) {
    companion object {
        fun generate(): BankReconciliationId = BankReconciliationId(UUID.randomUUID())
    }
}

/**
 * Reconciles one Cash/Bank `Account`'s posted activity against an
 * external bank statement (docs/DDD_Design.md Section 2.1's confirmed
 * GL Engine scope). Confirmed scope 2026-08-12, since no spec document
 * defines a workflow, matching tolerance, or outstanding-item write-off
 * mechanism (unlike `ArrearsCase`, Section 3.3): **manual/explicit
 * matching only** - the caller pairs a specific [BankStatementLine]
 * with a specific posted `JournalEntry` via [match]; no amount/date
 * heuristic is invented. Balance-adjustment math (deposits in transit,
 * unpresented cheques offsetting a stated ending balance) is also out
 * of scope - [isFullyReconciled] tracks match *completeness*, not a
 * derived balance tie-out.
 *
 * [accountId] is assumed to be an Asset-type Cash/Bank `Account`, same
 * assumption `CashBookEntry` already makes - [BankStatementLine.direction]
 * maps to [TransactionSide] the same way (`RECEIVED` -> `DEBIT`, `PAID`
 * -> `CREDIT`), not a generic conversion for any Account type.
 */
class BankReconciliation private constructor(
    val id: BankReconciliationId,
    val accountId: AccountId,
    val statementDate: LocalDate,
    val statementEndingBalance: Money,
    val statementLines: List<BankStatementLine>,
    val postedEntries: List<JournalEntry>,
    val currency: Currency,
    initialMatches: Set<Pair<BankStatementLineId, JournalEntryId>> = emptySet()
) {
    /**
     * The actual pairing, not two independent sets of "already used" ids
     * - `unmatch()` needs to know *which* entry a given statement line
     * was matched to, which two separate `Set<BankStatementLineId>`/
     * `Set<JournalEntryId>` can't express. [matches] is the one source
     * of truth; [unmatchedStatementLines]/[unmatchedEntries] below are
     * both derived from it.
     */
    private val matches = initialMatches.toMutableSet()

    val currentMatches: Set<Pair<BankStatementLineId, JournalEntryId>>
        get() = matches.toSet()

    val unmatchedStatementLines: List<BankStatementLine>
        get() = statementLines.filter { line -> matches.none { it.first == line.id } }

    val unmatchedEntries: List<JournalEntry>
        get() = postedEntries.filter { entry -> matches.none { it.second == entry.id } }

    val isFullyReconciled: Boolean
        get() = unmatchedStatementLines.isEmpty() && unmatchedEntries.isEmpty()

    /**
     * Pairs [statementLineId] with [journalEntryId]. Both must be
     * unmatched, and the entry must have a line against [accountId] on
     * the side [BankStatementLine.direction] implies, for the same
     * amount - a deliberate data-entry check, not a matching heuristic
     * (the caller already chose this specific pair).
     */
    fun match(statementLineId: BankStatementLineId, journalEntryId: JournalEntryId): ValidationResult {
        val statementLine = statementLines.find { it.id == statementLineId }
            ?: return ValidationResult.failure("No such bank statement line on this reconciliation")
        if (matches.any { it.first == statementLineId }) {
            return ValidationResult.failure("Bank statement line is already matched")
        }
        val entry = postedEntries.find { it.id == journalEntryId }
            ?: return ValidationResult.failure("No such eligible posted JournalEntry against this Account")
        if (matches.any { it.second == journalEntryId }) {
            return ValidationResult.failure("JournalEntry is already matched")
        }

        val expectedSide = when (statementLine.direction) {
            CashDirection.RECEIVED -> TransactionSide.DEBIT
            CashDirection.PAID -> TransactionSide.CREDIT
        }
        val matchingLine = entry.lines.find { it.accountId == accountId && it.side == expectedSide }
            ?: return ValidationResult.failure(
                "JournalEntry has no line against this Account on the expected side ($expectedSide)"
            )
        if (matchingLine.amount != statementLine.amount) {
            return ValidationResult.failure(
                "Amounts do not match: statement line ${statementLine.amount}, entry line ${matchingLine.amount}"
            )
        }

        matches.add(statementLineId to journalEntryId)
        return ValidationResult.success()
    }

    /**
     * Undoes a match (2026-10-03, FR-BANKREC-04) - deletes the pairing
     * outright, per the direct decision that a reconciliation match has
     * no compliance reason to stay immutable the way `AuditLogEntry`
     * does, unlike a posted `JournalEntry`'s own reversal-only
     * correction model.
     */
    fun unmatch(statementLineId: BankStatementLineId, journalEntryId: JournalEntryId): ValidationResult {
        val pair = statementLineId to journalEntryId
        if (pair !in matches) {
            return ValidationResult.failure("This statement line and JournalEntry are not currently matched to each other")
        }
        matches.remove(pair)
        return ValidationResult.success()
    }

    companion object {
        fun create(
            accountId: AccountId,
            statementDate: LocalDate,
            statementEndingBalance: Money,
            statementLines: List<BankStatementLine>,
            postedEntries: List<JournalEntry>,
            currency: Currency,
            id: BankReconciliationId = BankReconciliationId.generate()
        ): BankReconciliation {
            require(statementLines.all { it.amount.currency == currency }) {
                "All bank statement lines must be in the same currency as the reconciliation"
            }
            val eligibleEntries = postedEntries.filter { entry ->
                entry.status.hasHistoricalEffect() && entry.lines.any { it.accountId == accountId }
            }
            return BankReconciliation(
                id, accountId, statementDate, statementEndingBalance, statementLines, eligibleEntries, currency
            )
        }

        /**
         * Rebuilds a `BankReconciliation` from persisted state, restoring
         * [initialMatches] rather than starting empty - `internal`,
         * matching every other aggregate's `reconstitute()` visibility.
         * Doesn't re-apply [create]'s eligible-entries filter - a
         * persisted reconciliation's own [postedEntries] list was already
         * filtered when first created (or should be re-supplied already
         * filtered by the caller, e.g. a repository re-querying posted
         * entries for the Account).
         */
        internal fun reconstitute(
            id: BankReconciliationId,
            accountId: AccountId,
            statementDate: LocalDate,
            statementEndingBalance: Money,
            statementLines: List<BankStatementLine>,
            postedEntries: List<JournalEntry>,
            currency: Currency,
            matches: Set<Pair<BankStatementLineId, JournalEntryId>>
        ): BankReconciliation = BankReconciliation(
            id, accountId, statementDate, statementEndingBalance, statementLines, postedEntries, currency, matches
        )
    }
}
