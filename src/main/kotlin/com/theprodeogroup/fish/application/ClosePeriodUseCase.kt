package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.PeriodStatus
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository

/**
 * Outcome of [ClosePeriodUseCase.execute] - a sealed `Result`, same
 * reasoning as `PostJournalEntryResult` (docs/DDD_Design.md Section
 * 10.8): closing a Period has multiple genuinely distinct failure
 * reasons with real consequences, not a low-stakes case the domain
 * layer's usual "return null" idiom fits comfortably.
 */
sealed class ClosePeriodResult {
    data class Success(val period: Period, val events: List<DomainEvent>) : ClosePeriodResult()
    data object PeriodNotFound : ClosePeriodResult()
    data object PeriodNotOpen : ClosePeriodResult()
    data class UnresolvedEntriesExist(val entryIds: List<JournalEntryId>) : ClosePeriodResult()
}

/**
 * `Section 5`'s second named example use case (`PostJournalEntry,
 * ClosePeriod, ComputeTax, ...`), docs/DDD_Design.md Section 10.9.
 *
 * **Blocks closing while unresolved `JournalEntry`s remain in the
 * Period** - any entry whose `PostingStatus` isn't yet final
 * (`Draft`/`Pending`/`Rejected`) has to be posted or otherwise resolved
 * first. Confirmed with the user before building, not assumed - nothing
 * in the spec or design doc addressed this either way, and it's a
 * genuine judgment call: the alternative (close regardless) would leave
 * a Draft entry permanently stuck once the Period is Closed, since
 * `PostJournalEntryUseCase` already refuses to post into a non-Open
 * Period (Section 10.8).
 *
 * **Validates before acting**: the unresolved-entries check runs before
 * `Period.close()` is called, so a rejected close never mutates the
 * Period's state at all - matches `PostJournalEntryUseCase`'s "validate
 * before constructing anything" discipline.
 *
 * **Returns the `PeriodClosed` event `Period.close()` now raises**
 * (added alongside this use case - `Period` had no domain-event
 * machinery at all before this, even though Section 4 already named
 * `PeriodClosed`/`PeriodLocked`), per the same "aggregate collects,
 * application service publishes" pattern as every other use case built
 * this session.
 */
class ClosePeriodUseCase(
    private val periodRepository: PeriodRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    fun execute(periodId: PeriodId): ClosePeriodResult {
        val period = periodRepository.findById(periodId)
            ?: return ClosePeriodResult.PeriodNotFound
        if (!period.status.canTransitionTo(PeriodStatus.CLOSED)) {
            return ClosePeriodResult.PeriodNotOpen
        }

        val unresolvedIds = journalEntryRepository.findAllByPeriod(periodId)
            .filter { !it.status.isFinal() }
            .map { it.id }
        if (unresolvedIds.isNotEmpty()) {
            return ClosePeriodResult.UnresolvedEntriesExist(unresolvedIds)
        }

        val closing = period.close()
        check(closing.isValid) {
            "ClosePeriodUseCase found a Period eligible to close that Period.close() then rejected: " +
                closing.errors.joinToString()
        }

        periodRepository.save(period)

        return ClosePeriodResult.Success(period, period.pullDomainEvents())
    }
}
