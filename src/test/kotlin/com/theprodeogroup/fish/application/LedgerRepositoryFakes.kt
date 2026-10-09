package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.BankReconciliation
import com.theprodeogroup.fish.domain.ledger.BankReconciliationId
import com.theprodeogroup.fish.domain.ledger.BankReconciliationRepository
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
     * 10.2). By default this fake has no `PeriodRepository` to replicate that
     * with and returns everything, which is only right for a test with one
     * Company. A test that needs the real behaviour - above all a Tenant
     * isolation test, where returning another Company's entries would hide
     * exactly the leak it exists to catch (T15 / G1) - sets [periodSource]
     * to the `PeriodRepository` its entries' Periods were saved to, and then
     * only entries whose Period belongs to [companyId] come back.
     */
    var periodSource: PeriodRepository? = null

    override fun findAllByCompany(companyId: CompanyId): List<JournalEntry> {
        val periods = periodSource ?: return store.values.toList()
        return store.values.filter { entry -> periods.findById(entry.periodId)?.companyId == companyId }
    }
}

class FakeBankReconciliationRepository : BankReconciliationRepository {
    private data class Record(
        val companyId: CompanyId,
        val accountId: AccountId,
        val statementDate: java.time.LocalDate,
        val statementEndingBalance: com.theprodeogroup.common.Money,
        val statementLines: List<com.theprodeogroup.fish.domain.ledger.BankStatementLine>,
        val currency: java.util.Currency,
        val matches: Set<Pair<com.theprodeogroup.fish.domain.ledger.BankStatementLineId, JournalEntryId>>,
        val status: com.theprodeogroup.fish.domain.ledger.BankReconciliationStatus
    )

    private val store = mutableMapOf<BankReconciliationId, Record>()

    override fun save(reconciliation: BankReconciliation, companyId: CompanyId) {
        store[reconciliation.id] = Record(
            companyId, reconciliation.accountId, reconciliation.statementDate, reconciliation.statementEndingBalance,
            reconciliation.statementLines, reconciliation.currency, reconciliation.currentMatches, reconciliation.status
        )
    }

    override fun findById(id: BankReconciliationId, companyId: CompanyId, postedEntries: List<JournalEntry>): BankReconciliation? {
        val record = store[id] ?: return null
        if (record.companyId != companyId) return null
        return BankReconciliation.reconstitute(
            id, record.accountId, record.statementDate, record.statementEndingBalance,
            record.statementLines, postedEntries, record.currency, record.matches, record.status
        )
    }

    override fun findAllByCompany(companyId: CompanyId, postedEntries: List<JournalEntry>, accountId: AccountId?): List<BankReconciliation> =
        store.entries
            .filter { (_, record) -> record.companyId == companyId && (accountId == null || record.accountId == accountId) }
            .map { (id, record) ->
                BankReconciliation.reconstitute(
                    id, record.accountId, record.statementDate, record.statementEndingBalance,
                    record.statementLines, postedEntries, record.currency, record.matches, record.status
                )
            }
}
