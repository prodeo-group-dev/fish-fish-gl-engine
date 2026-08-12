package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.common.ValidationResult
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A balanced, atomic set of debit/credit lines posted on a date
 * (docs/DDD_Design.md Section 1/3.1/6) - the last piece of the Ledger
 * core build order (Money -> Account -> Period -> JournalEntry).
 *
 * Deliberately does NOT check `Period.status` itself - per the Section
 * 3.1 design note, "no posting into a closed period" is an
 * application-layer check (`PostingService`, not yet built) that loads
 * both this and the `Period` it references and validates, keeping them
 * out of each other's consistency boundary.
 *
 * Balance validation ([validateLines]) returns [ValidationResult] per
 * Section 6's spec ("via ValidationResult, not a silent post"), callable
 * standalone before attempting [create] - [create] itself still throws
 * on invalid input (`require`), matching `Money`/`Account`'s precedent
 * for a caller that skipped the pre-flight check.
 */
class JournalEntry private constructor(
    val id: JournalEntryId,
    val periodId: PeriodId,
    val date: LocalDate,
    val lines: List<JournalLine>,
    val source: JournalSource,
    val description: String?,
    initialStatus: PostingStatus,
    val reversalOfEntryId: JournalEntryId?
) {
    var status: PostingStatus = initialStatus
        private set

    private val _domainEvents = mutableListOf<DomainEvent>()

    /** Drains and returns events raised since the last call - same pattern as `Tenant`. */
    fun pullDomainEvents(): List<DomainEvent> {
        val events = _domainEvents.toList()
        _domainEvents.clear()
        return events
    }

    fun isEditable(): Boolean = status.isEditable()

    /** Section 6: Draft/Pending -> Posted, raises [JournalEntryPosted]. */
    fun post(now: Instant = Instant.now()): ValidationResult {
        if (!status.canTransitionTo(PostingStatus.POSTED)) {
            return ValidationResult.failure("Cannot post a JournalEntry in status $status")
        }
        status = PostingStatus.POSTED
        _domainEvents.add(JournalEntryPosted(id, now))
        return ValidationResult.success()
    }

    /**
     * Section 6: creates and returns the reversal entry, transitioning
     * this entry to Reversed - the original is never edited or deleted
     * in place. Returns null (rather than a ValidationResult) if this
     * entry isn't in a reversible status - only `Posted`/`System` can
     * transition to `Reversed`, per `PostingStatus`.
     *
     * The reversal entry starts already `Posted` (not `Draft`) since
     * it's system-generated and needs to take effect immediately to
     * cancel the original - its lines are the same accounts/amounts with
     * [TransactionSide.opposite] sides, which preserves the balance
     * invariant by construction, so it bypasses [create]'s validation.
     */
    fun reverse(now: Instant = Instant.now()): JournalEntry? {
        if (!status.canTransitionTo(PostingStatus.REVERSED)) {
            return null
        }
        status = PostingStatus.REVERSED

        val reversalLines = lines.map { it.copy(side = it.side.opposite()) }
        val reversalEntry = JournalEntry(
            id = JournalEntryId.generate(),
            periodId = periodId,
            date = now.atZone(ZoneOffset.UTC).toLocalDate(),
            lines = reversalLines,
            source = JournalSource.REVERSAL,
            description = "Reversal of $id",
            initialStatus = PostingStatus.POSTED,
            reversalOfEntryId = id
        )
        reversalEntry._domainEvents.add(JournalEntryPosted(reversalEntry.id, now))
        return reversalEntry
    }

    companion object {
        /**
         * Sum of debit `Money` amounts must equal sum of credit `Money`
         * amounts, *per currency* independently (Section 8's open
         * question about whether that's the right invariant for
         * multi-currency entries is still unresolved - this implements
         * what's currently specified, not a final answer).
         */
        fun validateLines(lines: List<JournalLine>): ValidationResult {
            if (lines.isEmpty()) {
                return ValidationResult.failure("A JournalEntry must have at least one line")
            }
            return lines.groupBy { it.amount.currency }.values
                .fold(ValidationResult.success()) { acc, currencyLines ->
                    val currency = currencyLines.first().amount.currency
                    val debitTotal = currencyLines
                        .filter { it.side == TransactionSide.DEBIT }
                        .fold(Money(BigDecimal.ZERO, currency)) { sum, line -> sum + line.amount }
                    val creditTotal = currencyLines
                        .filter { it.side == TransactionSide.CREDIT }
                        .fold(Money(BigDecimal.ZERO, currency)) { sum, line -> sum + line.amount }
                    val currencyResult = if (debitTotal == creditTotal) {
                        ValidationResult.success()
                    } else {
                        ValidationResult.failure(
                            "Lines in ${currency.currencyCode} do not balance: debits=$debitTotal, credits=$creditTotal"
                        )
                    }
                    acc.combine(currencyResult)
                }
        }

        fun create(
            periodId: PeriodId,
            date: LocalDate,
            lines: List<JournalLine>,
            source: JournalSource,
            description: String? = null,
            id: JournalEntryId = JournalEntryId.generate()
        ): JournalEntry {
            val validation = validateLines(lines)
            require(validation.isValid) { validation.errors.joinToString("; ") }
            return JournalEntry(id, periodId, date, lines, source, description, PostingStatus.DRAFT, null)
        }
    }
}
