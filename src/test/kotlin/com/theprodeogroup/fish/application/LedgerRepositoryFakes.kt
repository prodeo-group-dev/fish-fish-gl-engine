package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * In-memory stand-ins for the core Ledger repository interfaces, same
 * discipline as `TenancyRepositoryFakes.kt` - pure orchestration tests
 * for `application`-layer use cases shouldn't need a real database
 * (already covered by the Exposed `integrationTest` suite,
 * docs/DDD_Design.md Section 10.2).
 */
class FakeAccountRepository : AccountRepository {
    val saveCalls = mutableListOf<AccountId>()
    private val store = mutableMapOf<AccountId, Account>()
    override fun save(account: Account) {
        saveCalls.add(account.id)
        store[account.id] = account
    }
    override fun findById(id: AccountId): Account? = store[id]
    override fun findAllByCompany(companyId: CompanyId): List<Account> = store.values.filter { it.companyId == companyId }
}

class FakePeriodRepository : PeriodRepository {
    private val store = mutableMapOf<PeriodId, Period>()
    override fun save(period: Period) { store[period.id] = period }
    override fun findById(id: PeriodId): Period? = store[id]
    override fun findAllByCompany(companyId: CompanyId): List<Period> = store.values.filter { it.companyId == companyId }
}

class FakeJournalEntryRepository : JournalEntryRepository {
    val saveCalls = mutableListOf<JournalEntryId>()
    private val store = mutableMapOf<JournalEntryId, JournalEntry>()
    override fun save(entry: JournalEntry) {
        saveCalls.add(entry.id)
        store[entry.id] = entry
    }
    override fun findById(id: JournalEntryId): JournalEntry? = store[id]
    override fun findAllByPeriod(periodId: PeriodId): List<JournalEntry> = store.values.filter { it.periodId == periodId }

    /**
     * The real `ExposedJournalEntryRepository` derives this by joining
     * through `Period` (a Company's Periods, then those Periods' entries)
     * since `journal_entries` has no `company_id` of its own (Section
     * 10.2). This fake has no `PeriodRepository` to replicate that with,
     * and no test in this package needs it - returns everything, not a
     * faithful reproduction, deliberately simplified since it's unused.
     */
    override fun findAllByCompany(companyId: CompanyId): List<JournalEntry> = store.values.toList()
}
