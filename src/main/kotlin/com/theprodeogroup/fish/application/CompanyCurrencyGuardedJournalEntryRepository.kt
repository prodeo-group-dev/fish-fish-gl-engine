package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.util.Currency

/**
 * Raised when a NEW journal entry has a line in a currency other than its Company's base currency (ME-FX-3,
 * docs/Multi_Entity_Accounting_Playbook.md, 2026-10-22). Every report is built in the Company's currency and refuses to add two
 * currencies, so such a line would break the Company's reports for good. Until foreign amounts have a proper home on the line
 * (the memo foreign amount, currency and rate of the multi-currency design), no line may be in another currency. Mapped to
 * 409 `currency_not_company_currency` by the web layer.
 */
class CurrencyNotCompanyCurrencyException(
    val lineIndex: Int,
    val lineCurrency: Currency,
    val companyCurrency: Currency
) : RuntimeException(
    "Line ${lineIndex + 1} is in ${lineCurrency.currencyCode} but this Company's books are kept in ${companyCurrency.currencyCode}. " +
        "Post it in ${companyCurrency.currencyCode}."
)

/**
 * The one place every posting use case saves a journal entry through, so the currency rule cannot be forgotten by a route
 * (twenty-two use cases write entries). It checks only entries being created now:
 *  - an entry already stored may be saved again (a status change on a legacy entry that predates this rule is not a new line);
 *  - a reversal ([JournalEntry.reversalOfEntryId] set) is the clean-up path for such a legacy entry and is allowed;
 *  - a period or Company that cannot be resolved is left to the delegate's own checks, not turned into a new refusal.
 *
 * A refusal happens when the entry is saved, after a use case may already have recorded activity on the accounts it named
 * (a harmless flag); no ledger row is written. Idempotent replays are unaffected: only a request that would create a foreign
 * line is refused, before any entry exists.
 */
class CompanyCurrencyGuardedJournalEntryRepository(
    private val delegate: JournalEntryRepository,
    private val periodRepository: PeriodRepository,
    private val companyRepository: CompanyRepository
) : JournalEntryRepository by delegate {

    override fun save(entry: JournalEntry) {
        if (entry.reversalOfEntryId == null && delegate.findById(entry.id) == null) check(entry)
        delegate.save(entry)
    }

    override fun insertIfAbsent(entry: JournalEntry): Boolean {
        check(entry)
        return delegate.insertIfAbsent(entry)
    }

    private fun check(entry: JournalEntry) {
        val period = periodRepository.findById(entry.periodId) ?: return
        val companyCurrency = companyRepository.findById(period.companyId)?.baseCurrency ?: return
        entry.lines.forEachIndexed { index, line ->
            if (line.amount.currency != companyCurrency) throw CurrencyNotCompanyCurrencyException(index, line.amount.currency, companyCurrency)
        }
    }
}
