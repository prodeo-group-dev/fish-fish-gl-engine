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
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * `JournalEntryRepository.insertIfAbsent` against a real Postgres (docs/GL_Cash_And_Bank_Books_SRS.md, Release B): the
 * database, not the application, is what makes a caller-chosen entry id a race-safe idempotency guard. Skips (not
 * fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class JournalEntryInsertIfAbsentIntegrationTest {

    private val companyRepository = ExposedCompanyRepository()
    private val accountRepository = ExposedAccountRepository()
    private val periodRepository = ExposedPeriodRepository()
    private val journalEntryRepository = ExposedJournalEntryRepository()
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

    private class World(val period: Period, val cash: Account, val sales: Account)

    private fun world(): World {
        val company = Company.create(TenantId.generate(), "Insert-if-absent Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp)
            .also { companyRepository.save(it) }
        val period = Period.create(company.id, PeriodType.MONTH, LocalDate.now(), LocalDate.now().plusDays(30)).also { it.open(); periodRepository.save(it) }
        val cash = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH).also { accountRepository.save(it) }
        val sales = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales").also { accountRepository.save(it) }
        return World(period, cash, sales)
    }

    private fun entry(w: World, id: JournalEntryId, amount: String) =
        JournalEntry.create(
            w.period.id, LocalDate.now(),
            listOf(
                JournalLine(w.cash.id, Money(BigDecimal(amount), gbp), TransactionSide.DEBIT),
                JournalLine(w.sales.id, Money(BigDecimal(amount), gbp), TransactionSide.CREDIT)
            ),
            JournalSource.CASH_BOOK, "Idempotent", id
        ).also { it.post() }

    @Test
    fun `given an entry id not yet used, when it is inserted twice, then the first wins, the second changes nothing, and the lines are not duplicated`() {
        val w = world()
        val id = JournalEntryId.generate()

        journalEntryRepository.insertIfAbsent(entry(w, id, "100.00")) shouldBe true
        journalEntryRepository.insertIfAbsent(entry(w, id, "999.00")) shouldBe false

        val stored = journalEntryRepository.findById(id)!!
        stored.lines.size shouldBe 2
        stored.lines.first { it.accountId == w.cash.id }.amount.amount.compareTo(BigDecimal("100.00")) shouldBe 0
        stored.source shouldBe JournalSource.CASH_BOOK
        journalEntryRepository.findAllByAccount(w.cash.id).size shouldBe 1
    }

    @Test
    fun `given a posted entry, when its reversal is recorded, then the reversal exists and the original is REVERSED together - and a second reversal changes nothing`() {
        val w = world()
        val original = entry(w, JournalEntryId.generate(), "100.00")
        journalEntryRepository.save(original)
        val reversalId = JournalEntryId.generate()
        val reversal = original.reverse(java.time.Instant.now(), reversalId)!!

        journalEntryRepository.recordReversal(original, reversal) shouldBe true

        journalEntryRepository.findById(original.id)!!.status shouldBe com.theprodeogroup.fish.domain.common.PostingStatus.REVERSED
        journalEntryRepository.findById(reversalId)!!.reversalOfEntryId shouldBe original.id
        journalEntryRepository.findAllByAccount(w.cash.id).size shouldBe 2
        val again = original.reverse(java.time.Instant.now(), reversalId)
        (again == null) shouldBe true
        journalEntryRepository.recordReversal(original, reversal) shouldBe false
        journalEntryRepository.findAllByAccount(w.cash.id).size shouldBe 2
    }

    @Test
    fun `given several simultaneous inserts with the same entry id, then exactly one wins and exactly one entry with two lines exists`() {
        val w = world()
        val id = JournalEntryId.generate()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val futures = (1..8).map { pool.submit<Boolean> { start.await(); journalEntryRepository.insertIfAbsent(entry(w, id, "25.00")) } }
        start.countDown()
        val results = futures.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()

        results.count { it } shouldBe 1
        journalEntryRepository.findById(id)!!.lines.size shouldBe 2
        journalEntryRepository.findAllByAccount(w.cash.id).size shouldBe 1
    }
}
