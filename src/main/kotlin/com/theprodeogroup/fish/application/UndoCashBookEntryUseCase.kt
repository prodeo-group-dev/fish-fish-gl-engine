package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.ledger.AccountId
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
 * Undo an entry shown in a cash or bank book (docs/GL_Cash_And_Bank_Books_SRS.md, FR-CB26): a wrong entry is corrected by
 * a reversing entry, never by editing or deleting it. Both rows then show in the book and the balance is back.
 *
 * The entry must have a line on this Company's book, must be POSTED (not a draft, not already undone), must not itself be
 * a reversal, and its Period must still allow posting. [ComputeCashBookUseCase]'s `canUndo` applies the same rules, so a
 * screen offers Undo only where it can work.
 *
 * Two simultaneous Undos of the same entry cannot both reverse it: the reversing entry gets an id derived from the
 * original's, and it is stored only if no entry has that id yet ([JournalEntryRepository.insertIfAbsent]), so exactly
 * one wins and the other is [Result.AlreadyUndone]. Repeating an Undo that already succeeded is also
 * [Result.AlreadyUndone], not a second reversal.
 */
class UndoCashBookEntryUseCase(
    private val companyRepository: CompanyRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val periodRepository: PeriodRepository
) {
    sealed class Result {
        data class Undone(val reversal: JournalEntry, val balanceAfter: Money) : Result()
        data object CompanyNotFound : Result()
        data object AccountNotFound : Result()
        data object NotACashBook : Result()

        /** No such entry on this book (also what an entry of another Company or another book looks like). */
        data object EntryNotFound : Result()
        data object AlreadyUndone : Result()

        /** A draft, or itself a reversal: it cannot be undone. */
        data object NotUndoable : Result()
        data object PeriodNotOpen : Result()
    }

    fun execute(companyId: CompanyId, bookAccountId: AccountId, entryId: JournalEntryId, now: Instant = Instant.now()): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound
        val book = accountRepository.findById(bookAccountId)?.takeIf { it.companyId == companyId } ?: return Result.AccountNotFound
        if (book.cashBookKind == null) return Result.NotACashBook

        val entry = journalEntryRepository.findById(entryId)?.takeIf { e -> e.lines.any { it.accountId == book.id } }
            ?: return Result.EntryNotFound
        if (entry.status == PostingStatus.REVERSED) return Result.AlreadyUndone
        if (entry.status != PostingStatus.POSTED || entry.reversalOfEntryId != null || entry.source == JournalSource.REVERSAL) return Result.NotUndoable
        val period = periodRepository.findOwnedBy(entry.periodId, companyId) ?: return Result.EntryNotFound
        if (!period.allowsPosting()) return Result.PeriodNotOpen

        val reversalId = JournalEntryId(UUID.nameUUIDFromBytes("undo|${entry.id.value}".toByteArray(Charsets.UTF_8)))
        val reversal = entry.reverse(now, reversalId)
            ?: return if (entry.status == PostingStatus.REVERSED) Result.AlreadyUndone else Result.NotUndoable
        if (!journalEntryRepository.insertIfAbsent(reversal)) return Result.AlreadyUndone

        journalEntryRepository.save(entry)
        reversal.lines.map { it.accountId }.distinct().forEach { id ->
            accountRepository.findById(id)?.let { account ->
                account.recordActivity()
                accountRepository.save(account)
            }
        }
        return Result.Undone(reversal, journalEntryRepository.balanceOfBook(book, company.baseCurrency))
    }
}
