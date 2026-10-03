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

/**
 * Persistence contract for [BankReconciliation]
 * (docs/GL_Working_Capital_And_Bank_Reconciliation_Software_Requirements_Specification.md
 * Section 2.2/4, decided 2026-10-03). [companyId] is a separate parameter
 * on both methods, not a field read off the aggregate - `BankReconciliation`
 * itself carries no `CompanyId` (only [AccountId], same as every other
 * Ledger aggregate), but the persisted row needs one for the same direct,
 * company-scoped-query convention every other repository in this
 * codebase already uses (`findById` scoped to a Company, not just an id,
 * closing the same cross-tenant-lookup gap `authorizeTenantFor*` guards
 * against at the route layer).
 *
 * [postedEntries] is caller-supplied on [findById], matching
 * [BankReconciliation.create]'s own shape - this repository doesn't
 * depend on [JournalEntryRepository] itself; the calling use case already
 * has to fetch posted entries for its own purposes and passes them
 * through, the same composition responsibility every `Compute*UseCase`
 * in `application` already carries.
 */
interface BankReconciliationRepository {
    fun save(reconciliation: BankReconciliation, companyId: CompanyId)
    fun findById(id: BankReconciliationId, companyId: CompanyId, postedEntries: List<JournalEntry>): BankReconciliation?

    /**
     * Every reconciliation for [companyId], optionally narrowed to one
     * [accountId] - added 2026-10-03 after WEB flagged a real discovery
     * gap: without this, a caller could only ever find a reconciliation
     * again by already holding its id, the same "no way back" problem
     * WEB.3 already fixed for School ids. [postedEntries] is
     * caller-supplied once (not re-fetched per row), same composition
     * responsibility [findById] already carries - callers already pay
     * that cost once per request regardless of how many reconciliations
     * come back.
     */
    fun findAllByCompany(companyId: CompanyId, postedEntries: List<JournalEntry>, accountId: AccountId? = null): List<BankReconciliation>
}
