package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import java.time.LocalDate

/**
 * Outcome of [PostJournalEntryUseCase.execute] - a sealed `Result`, not
 * the domain-layer's usual "return `null` on failure" idiom
 * (`AddCompanyToTenantUseCase`, Section 10.6). That idiom collapses every
 * failure reason into one undifferentiated signal, which was an
 * acceptable tradeoff there (2 reasons, lower-stakes operation). Posting
 * has 4 genuinely distinct failure reasons and real financial-integrity
 * consequences - a caller (eventually a UI or API) plausibly needs to
 * react differently to "this Period is Closed" versus "these lines don't
 * balance." Discussed and confirmed with the user before building, not
 * assumed from precedent.
 */
sealed class PostJournalEntryResult {
    data class Success(val entry: JournalEntry, val events: List<DomainEvent>) : PostJournalEntryResult()
    data object PeriodNotFound : PostJournalEntryResult()
    data object PeriodNotOpen : PostJournalEntryResult()
    data class InvalidLines(val errors: List<String>) : PostJournalEntryResult()
    data class AccountNotFound(val accountId: AccountId) : PostJournalEntryResult()
}

/**
 * The `PostingService` flagged, but deliberately not built, in both
 * `JournalEntry`'s and `Period`'s own KDoc since Section 3.1: "no posting
 * into a closed period is an application-layer check... that loads both
 * this and the Period it references and validates, keeping them out of
 * each other's consistency boundary." This is that check, now real -
 * `JournalEntry` and `Period` still know nothing about each other; this
 * use case is exactly the place Section 3.1 always said the check
 * belonged.
 *
 * Also fulfills `Account.recordActivity()`'s own KDoc ("Called by the
 * application service when a JournalEntry posts against this Account") -
 * every `Account` referenced by the entry's lines gets marked and
 * persisted, closing a wiring gap that's existed since `Account` was
 * first built.
 *
 * **Validates before constructing anything**: `JournalEntry.validateLines()`
 * and every referenced `Account`'s existence are checked *before*
 * `JournalEntry.create()` is called, so a failure here never persists a
 * partial or malformed entry - by the time `create()` runs, it's
 * guaranteed to succeed (its own `require()` checks are redundant at that
 * point, not bypassed).
 *
 * **Scope**: only creating and posting a brand-new `JournalEntry`
 * (`Draft -> Posted`). Reversal (`JournalEntry.reverse()`) is a separate,
 * not-yet-built use case - out of scope here.
 */
class PostJournalEntryUseCase(
    private val periodRepository: PeriodRepository,
    private val accountRepository: AccountRepository,
    private val journalEntryRepository: JournalEntryRepository
) {
    data class Request(
        val periodId: PeriodId,
        val date: LocalDate,
        val lines: List<JournalLine>,
        val source: JournalSource,
        val description: String? = null
    )

    fun execute(request: Request): PostJournalEntryResult {
        val period = periodRepository.findById(request.periodId)
            ?: return PostJournalEntryResult.PeriodNotFound
        if (!period.allowsPosting()) {
            return PostJournalEntryResult.PeriodNotOpen
        }

        val validation = JournalEntry.validateLines(request.lines)
        if (!validation.isValid) {
            return PostJournalEntryResult.InvalidLines(validation.errors)
        }

        val accounts = mutableListOf<Account>()
        for (accountId in request.lines.map { it.accountId }.distinct()) {
            val account = accountRepository.findById(accountId)
                ?: return PostJournalEntryResult.AccountNotFound(accountId)
            accounts.add(account)
        }

        val entry = JournalEntry.create(request.periodId, request.date, request.lines, request.source, request.description)
        val posting = entry.post()
        check(posting.isValid) {
            "PostJournalEntryUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        accounts.forEach { account ->
            account.recordActivity()
            accountRepository.save(account)
        }
        journalEntryRepository.save(entry)

        return PostJournalEntryResult.Success(entry, entry.pullDomainEvents())
    }
}
