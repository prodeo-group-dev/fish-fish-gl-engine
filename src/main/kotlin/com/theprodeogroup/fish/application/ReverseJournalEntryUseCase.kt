package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.PeriodRepository

/**
 * Outcome of [ReverseJournalEntryUseCase.execute] - a sealed `Result`,
 * same reasoning as `PostJournalEntryResult`/`ClosePeriodResult`/
 * `ComputeTaxResult` (Sections 10.8/10.9/10.10): several genuinely
 * distinct failure reasons with real financial-integrity consequences,
 * not the domain layer's usual "return null" case. By this point in the
 * codebase's application layer the shape needs no fresh justification -
 * this is the fourth use case to follow it.
 */
sealed class ReverseJournalEntryResult {
    data class Success(
        val originalEntry: JournalEntry,
        val reversalEntry: JournalEntry,
        val events: List<DomainEvent>
    ) : ReverseJournalEntryResult()
    data object EntryNotFound : ReverseJournalEntryResult()
    data object PeriodNotOpen : ReverseJournalEntryResult()
    data object EntryNotReversible : ReverseJournalEntryResult()
}

/**
 * The reversal counterpart `PostJournalEntryUseCase` (Section 10.8)
 * deliberately left out of scope: "Reversal (`JournalEntry.reverse()`)
 * is a separate, not-yet-built use case." This is that use case.
 *
 * **Adds the same "no posting into a Closed Period" guard
 * `PostJournalEntryUseCase` enforces** - `JournalEntry.reverse()` itself
 * still knows nothing about `Period` (matching Section 3.1's design
 * note; the reversal entry it produces starts already `Posted`), but a
 * reversal genuinely creates a new `Posted` entry in the same Period as
 * the original, so it's exactly the same "no posting into a closed
 * period" scenario `PostJournalEntryUseCase` already guards against -
 * applying that existing rule here, not inventing a new one.
 *
 * **Also wires `Account.recordActivity()` for the reversal entry's
 * lines**, same reasoning as `PostJournalEntryUseCase` - a reversal
 * genuinely posts against these accounts (even though they were already
 * marked from the original posting, this keeps the behavior consistent
 * and correct regardless of load order).
 *
 * **Validates before acting**: the Period-open check and the
 * `JournalEntry.reverse()` call (which itself validates the entry's own
 * status) both happen before anything is saved - a rejected reversal
 * never mutates the original entry's status at all.
 *
 * A missing `Account` for one of the reversal entry's lines is treated
 * as an internal invariant violation (`check()`), not a caller-facing
 * `Result` case - every account a reversal references was necessarily
 * posted to by the original entry already, and `Account.validateDeletion()`
 * already prevents deleting an Account with posted activity, so this
 * should be unreachable outside of corrupted data.
 */
class ReverseJournalEntryUseCase(
    private val journalEntryRepository: JournalEntryRepository,
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository
) {
    fun execute(entryId: JournalEntryId): ReverseJournalEntryResult {
        val originalEntry = journalEntryRepository.findById(entryId)
            ?: return ReverseJournalEntryResult.EntryNotFound

        val period = periodRepository.findById(originalEntry.periodId)
        checkNotNull(period) {
            "ReverseJournalEntryUseCase found a JournalEntry (${entryId.value}) referencing a Period " +
                "(${originalEntry.periodId.value}) that no longer exists"
        }
        if (!period.allowsPosting()) {
            return ReverseJournalEntryResult.PeriodNotOpen
        }

        val reversalEntry = originalEntry.reverse() ?: return ReverseJournalEntryResult.EntryNotReversible

        for (accountId in reversalEntry.lines.map { it.accountId }.distinct()) {
            val account = accountRepository.findById(accountId)
            checkNotNull(account) {
                "ReverseJournalEntryUseCase's reversal entry referenced Account ($accountId) that no longer exists"
            }
            account.recordActivity()
            accountRepository.save(account)
        }

        journalEntryRepository.save(originalEntry)
        journalEntryRepository.save(reversalEntry)

        return ReverseJournalEntryResult.Success(originalEntry, reversalEntry, reversalEntry.pullDomainEvents())
    }
}
