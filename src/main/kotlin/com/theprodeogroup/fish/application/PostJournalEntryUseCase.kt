package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.findOwnedBy
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.common.DomainEvent
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
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
        /** The Company the caller was authorized against - the Period must belong to it (T19 / F9). */
        val companyId: CompanyId,
        val periodId: PeriodId,
        val date: LocalDate,
        val lines: List<JournalLine>,
        val source: JournalSource,
        val description: String? = null
    )

    fun execute(request: Request): PostJournalEntryResult =
        when (val prepared = prepare(request, JournalEntryId.generate())) {
            is Prepared.Refused -> prepared.result
            is Prepared.Ready -> {
                prepared.accounts.forEach { account ->
                    account.recordActivity()
                    accountRepository.save(account)
                }
                journalEntryRepository.save(prepared.entry)
                PostJournalEntryResult.Success(prepared.entry, prepared.entry.pullDomainEvents())
            }
        }

    /** The outcome of [executeOnce]. */
    sealed class OnceResult {
        data class Posted(val entry: JournalEntry, val events: List<DomainEvent>) : OnceResult()

        /** An entry with the given id already exists; nothing was posted and nothing was changed. */
        data class AlreadyExists(val entry: JournalEntry) : OnceResult()
        data class Refused(val result: PostJournalEntryResult) : OnceResult()
    }

    /**
     * Posts the entry with the caller-chosen [entryId], at most once (docs/GL_Cash_And_Bank_Books_SRS.md, Release B):
     * it is stored only if no entry with that id exists, so two simultaneous requests that derive the same id from
     * the same idempotency key cannot both post, and a retry after the first succeeded changes nothing. Every
     * validation is the same as [execute]'s. Account activity is recorded only when this call actually posted.
     */
    fun executeOnce(request: Request, entryId: JournalEntryId): OnceResult =
        when (val prepared = prepare(request, entryId)) {
            is Prepared.Refused -> OnceResult.Refused(prepared.result)
            is Prepared.Ready ->
                if (journalEntryRepository.insertIfAbsent(prepared.entry)) {
                    prepared.accounts.forEach { account ->
                        account.recordActivity()
                        accountRepository.save(account)
                    }
                    OnceResult.Posted(prepared.entry, prepared.entry.pullDomainEvents())
                } else {
                    val existing = journalEntryRepository.findById(entryId)
                        ?: error("insertIfAbsent refused entry ${entryId.value} but no such entry can be read")
                    OnceResult.AlreadyExists(existing)
                }
        }

    private sealed class Prepared {
        data class Ready(val entry: JournalEntry, val accounts: List<Account>) : Prepared()
        data class Refused(val result: PostJournalEntryResult) : Prepared()
    }

    private fun prepare(request: Request, entryId: JournalEntryId): Prepared {

        val period = periodRepository.findOwnedBy(request.periodId, request.companyId)
            ?: return Prepared.Refused(PostJournalEntryResult.PeriodNotFound)
        if (!period.allowsPosting()) {
            return Prepared.Refused(PostJournalEntryResult.PeriodNotOpen)
        }

        val validation = JournalEntry.validateLines(request.lines)
        if (!validation.isValid) {
            return Prepared.Refused(PostJournalEntryResult.InvalidLines(validation.errors))
        }

        val accounts = mutableListOf<Account>()
        for (accountId in request.lines.map { it.accountId }.distinct()) {
            // Cross-tenant isolation gap (2026-09-21): an Account belonging to a different
            // Company than this Period's was previously accepted as long as it existed at
            // all, letting a caller post into another Company's ledger by supplying an
            // Account id they don't own. `.takeIf { it.companyId == period.companyId }`
            // mirrors RecordOpeningBalanceUseCase's own established fix for the identical
            // gap - a cross-Company Account is treated as AccountNotFound, not a distinct
            // "forbidden" case, so this never confirms another Company's Account exists.
            val account = accountRepository.findById(accountId)
                ?.takeIf { it.companyId == period.companyId }
                ?: return Prepared.Refused(PostJournalEntryResult.AccountNotFound(accountId))
            accounts.add(account)
        }

        val entry = JournalEntry.create(request.periodId, request.date, request.lines, request.source, request.description, entryId)
        val posting = entry.post()
        check(posting.isValid) {
            "PostJournalEntryUseCase built a JournalEntry that failed its own post() precondition: " +
                posting.errors.joinToString()
        }

        return Prepared.Ready(entry, accounts)
    }
}
