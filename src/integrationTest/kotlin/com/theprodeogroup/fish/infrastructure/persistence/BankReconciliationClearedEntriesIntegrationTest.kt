package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.BankReconciliation
import com.theprodeogroup.fish.domain.ledger.BankStatementLine
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.CashDirection
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * Which entries a later reconciliation does not offer again (CM item 6), against a real Postgres: the ids matched in
 * COMPLETED reconciliations of the same account, and nothing else. Skips (not fails) if `FISH_DB_USER`/
 * `FISH_DB_PASSWORD` aren't set.
 */
class BankReconciliationClearedEntriesIntegrationTest {

    private val companyRepository = ExposedCompanyRepository()
    private val accountRepository = ExposedAccountRepository()
    private val periodRepository = ExposedPeriodRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
    private val reconciliationRepository = ExposedBankReconciliationRepository()
    private val gbp = Currency.getInstance("GBP")

    @BeforeEach
    fun setUp() {
        assumeTrue(
            System.getenv("FISH_DB_USER") != null && System.getenv("FISH_DB_PASSWORD") != null,
            "FISH_DB_USER/FISH_DB_PASSWORD not set - skipping"
        )
        val dataSource = DatabaseConfig.dataSource()
        DatabaseMigrator.migrate(dataSource)
        DatabaseConfig.connectExposed(dataSource)
    }

    private class World(val company: Company, val period: Period, val bank: Account, val otherBank: Account, val sales: Account)

    private fun world(): World {
        val company = Company.create(TenantId.generate(), "Cleared Entries Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companyRepository.save(it) }
        val period = Period.create(company.id, PeriodType.MONTH, LocalDate.now().minusDays(1), LocalDate.now().plusDays(30)).also { it.open(); periodRepository.save(it) }
        val bank = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank", cashBookKind = CashBookKind.BANK).also { accountRepository.save(it) }
        val otherBank = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1020", "Other bank", cashBookKind = CashBookKind.BANK).also { accountRepository.save(it) }
        val sales = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales").also { accountRepository.save(it) }
        return World(company, period, bank, otherBank, sales)
    }

    private fun receipt(w: World, on: Account, amount: String) =
        JournalEntry.create(
            w.period.id, LocalDate.now(),
            listOf(
                JournalLine(on.id, Money(BigDecimal(amount), gbp), TransactionSide.DEBIT),
                JournalLine(w.sales.id, Money(BigDecimal(amount), gbp), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL, "Receipt"
        ).also { it.post(); journalEntryRepository.save(it) }

    /** Starts a reconciliation of [account] with one statement line for [entry], matches it, and optionally completes it. */
    private fun reconcile(w: World, account: Account, entry: JournalEntry, amount: String, complete: Boolean): BankReconciliation {
        val line = BankStatementLine(date = LocalDate.now(), amount = Money(BigDecimal(amount), gbp), direction = CashDirection.RECEIVED, description = "line")
        val rec = BankReconciliation.create(account.id, LocalDate.now(), Money(BigDecimal(amount), gbp), listOf(line), listOf(entry), gbp)
        rec.match(line.id, entry.id).isValid shouldBe true
        if (complete) rec.complete()
        reconciliationRepository.save(rec, w.company.id)
        return rec
    }

    @Test
    fun `given completed and open reconciliations, then only entries matched in completed ones of that account are cleared`() {
        val w = world()
        val clearedEntry = receipt(w, w.bank, "100.00")
        val openEntry = receipt(w, w.bank, "30.00")
        val otherAccountEntry = receipt(w, w.otherBank, "55.00")
        reconcile(w, w.bank, clearedEntry, "100.00", complete = true)
        reconcile(w, w.bank, openEntry, "30.00", complete = false)
        reconcile(w, w.otherBank, otherAccountEntry, "55.00", complete = true)

        reconciliationRepository.findClearedEntryIds(w.company.id, w.bank.id) shouldBe setOf(clearedEntry.id)
        reconciliationRepository.findClearedEntryIds(w.company.id, w.otherBank.id) shouldBe setOf(otherAccountEntry.id)
    }

    @Test
    fun `given a completed reconciliation, when it is excluded, then its own entries are not counted as cleared by others`() {
        val w = world()
        val entry = receipt(w, w.bank, "100.00")
        val rec = reconcile(w, w.bank, entry, "100.00", complete = true)

        reconciliationRepository.findClearedEntryIds(w.company.id, w.bank.id, excluding = rec.id) shouldBe emptySet()
    }

    @Test
    fun `given a completed reconciliation, when a new one is read back, then it does not offer the cleared entry but offers a new one, from the database`() {
        val w = world()
        val clearedEntry = receipt(w, w.bank, "100.00")
        reconcile(w, w.bank, clearedEntry, "100.00", complete = true)
        val freshEntry = receipt(w, w.bank, "20.00")
        val line = BankStatementLine(date = LocalDate.now(), amount = Money(BigDecimal("20.00"), gbp), direction = CashDirection.RECEIVED, description = "line")
        val second = BankReconciliation.create(
            w.bank.id, LocalDate.now(), Money(BigDecimal("20.00"), gbp), listOf(line),
            journalEntryRepository.findAllByCompany(w.company.id), gbp,
            alreadyReconciledEntryIds = reconciliationRepository.findClearedEntryIds(w.company.id, w.bank.id)
        )
        reconciliationRepository.save(second, w.company.id)

        val reread = reconciliationRepository.findById(second.id, w.company.id, journalEntryRepository.findAllByCompany(w.company.id))!!

        reread.unmatchedEntries.map { it.id } shouldBe listOf(freshEntry.id)
    }
}
