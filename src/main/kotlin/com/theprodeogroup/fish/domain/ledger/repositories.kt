package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * Repository interfaces for the core Ledger build order (Money -> Account
 * -> Period -> JournalEntry, docs/DDD_Design.md Section 10.1) - the
 * interfaces live in `domain`, implementations in `infrastructure`, per
 * Section 5's plan. `Money` isn't a repository - it's a value object
 * embedded wherever it's used (`JournalLine.amount`), not an aggregate
 * with its own identity/table. `CashBookEntry` isn't either - it's
 * explicitly documented as "not a persisted aggregate," ephemeral,
 * converts to a `JournalEntry` which *is* persisted.
 *
 * All three follow the same minimal shape: [save] as a single upsert
 * (no separate insert/update - none of these aggregates have an
 * "isNew" flag to distinguish the two, and Postgres `ON CONFLICT`
 * makes upsert trivial), [findById], and a bulk fetch scoped to a
 * `CompanyId` (what every report type - `TrialBalance`/`ProfitAndLoss`/
 * `WorkingCapital`/etc. - actually needs: a `List<Account>` /
 * `List<JournalEntry>` for one Company). No `delete` method - `Account`'s
 * own `validateDeletion()` already gates hard deletion to "no posted
 * activity," and deactivation (a domain state transition, not a
 * repository concern) is the normal path.
 */
interface AccountRepository {
    fun save(account: Account)
    fun findById(id: AccountId): Account?
    fun findAllByCompany(companyId: CompanyId): List<Account>
}

interface PeriodRepository {
    fun save(period: Period)
    fun findById(id: PeriodId): Period?
    fun findAllByCompany(companyId: CompanyId): List<Period>
}

interface JournalEntryRepository {
    fun save(entry: JournalEntry)
    fun findById(id: JournalEntryId): JournalEntry?

    /**
     * Every posted entry for [companyId], across all Periods - deliberately
     * not scoped to one Period, since several report types
     * (`StatementOfCashFlows`'s opening balance, `WorkingCapital`) need
     * entries *before* a given date, not just within one Period.
     * `Company` isn't persisted yet, so this actually filters by Period
     * ownership under the hood (`periods.company_id`) - documented on
     * the implementation, not a public contract detail.
     */
    fun findAllByCompany(companyId: CompanyId): List<JournalEntry>
    fun findAllByPeriod(periodId: PeriodId): List<JournalEntry>
}
