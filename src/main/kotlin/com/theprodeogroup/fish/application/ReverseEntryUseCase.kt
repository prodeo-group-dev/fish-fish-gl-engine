package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.ledger.findOwnedBy
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.Instant
import java.util.UUID

/**
 * GL-RF-RV1, the generic journal reversal (Femi via CM, 2026-10-23: "Refuse, point to CBB"). A posted entry is corrected by a reversing
 * entry, never by editing or deleting it: the reversal has the same accounts and amounts with the sides swapped, and the original is
 * marked REVERSED, both in ONE transaction ([JournalEntryRepository.recordReversal]).
 *
 * **Every journal is reversed, always**: there is NO reconciliation check here (CBB holds the reconciliations; "the GL is not expected to
 * undo anything" and "it can always do a Journal Reversal"). The one refusal of kind is an entry whose source is a cash or bank book
 * ([JournalSource.CASH_BOOK]): "CBB corrects errors in a Bank and Cash Book. Never the GL", so [Result.CorrectInCashAndBankBooks].
 *
 * Also refused: an entry already reversed ([Result.AlreadyReversed]), an entry that is itself a reversal or not POSTED
 * ([Result.NotReversible]; a wrong reversal is corrected with a new journal), and an entry whose Period no longer allows posting. An entry
 * of another Company reads as [Result.EntryNotFound], never a distinct refusal, so this never confirms another Company's entry exists.
 *
 * Two simultaneous reversals of the same entry cannot both land: the reversal gets an id derived from the original's and is stored only
 * if no entry has that id yet, so exactly one wins and the other is [Result.AlreadyReversed]. Repeating a reversal that succeeded is
 * [Result.AlreadyReversed], not a second reversal.
 *
 * This is not the older `ReverseJournalEntryUseCase`, which takes an entry id with no Company and is wired to no route.
 */
class ReverseEntryUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val periodRepository: PeriodRepository
) {
    sealed class Result {
        data class Reversed(val reversal: JournalEntry) : Result()
        data object CompanyNotFound : Result()

        /** No such entry in this Company (also what an entry of another Company looks like). */
        data object EntryNotFound : Result()
        data object AlreadyReversed : Result()

        /** A draft, or itself a reversal. */
        data object NotReversible : Result()

        /** The entry was made in a cash or bank book: it is corrected there, never in the GL. */
        data object CorrectInCashAndBankBooks : Result()
        data object PeriodNotOpen : Result()
    }

    fun execute(companyId: CompanyId, entryId: JournalEntryId, now: Instant = Instant.now()): Result {
        companyRepository.findById(companyId) ?: return Result.CompanyNotFound
        val entry = journalEntryRepository.findById(entryId) ?: return Result.EntryNotFound
        val period = periodRepository.findOwnedBy(entry.periodId, companyId) ?: return Result.EntryNotFound

        if (entry.status == PostingStatus.REVERSED) return Result.AlreadyReversed
        if (entry.reversalOfEntryId != null || entry.source == JournalSource.REVERSAL) return Result.NotReversible
        if (entry.source == JournalSource.CASH_BOOK) return Result.CorrectInCashAndBankBooks
        if (!entry.status.canTransitionTo(PostingStatus.REVERSED)) return Result.NotReversible
        if (!period.allowsPosting()) return Result.PeriodNotOpen

        val reversalId = JournalEntryId(UUID.nameUUIDFromBytes("reverse|${entry.id.value}".toByteArray(Charsets.UTF_8)))
        val reversal = entry.reverse(now, reversalId)
            ?: return if (entry.status == PostingStatus.REVERSED) Result.AlreadyReversed else Result.NotReversible
        // One transaction: the reversal is stored and the original is marked REVERSED together, or neither.
        if (!journalEntryRepository.recordReversal(entry, reversal)) return Result.AlreadyReversed

        reversal.lines.map { it.accountId }.distinct().forEach { id ->
            accountRepository.findById(id)?.let { account ->
                account.recordActivity()
                accountRepository.save(account)
            }
        }
        return Result.Reversed(reversal)
    }
}
