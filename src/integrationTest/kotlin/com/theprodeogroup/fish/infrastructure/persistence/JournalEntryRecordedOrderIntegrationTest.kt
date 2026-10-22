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
import com.theprodeogroup.fish.domain.ledger.CashBook
import com.theprodeogroup.fish.domain.ledger.CashBookKind
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
 * Same-day order in the books (V35, CM item 7) against a real Postgres: the database numbers each entry as it is
 * inserted, every read returns the entries in (date, recorded order), and the number never changes afterwards.
 * Skips (not fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class JournalEntryRecordedOrderIntegrationTest {

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

    private class World(val company: Company, val period: Period, val cash: Account, val sales: Account)

    private fun world(): World {
        val company = Company.create(TenantId.generate(), "Recorded Order Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp)
            .also { companyRepository.save(it) }
        val period = Period.create(company.id, PeriodType.MONTH, LocalDate.now().minusDays(1), LocalDate.now().plusDays(30)).also { it.open(); periodRepository.save(it) }
        val cash = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH).also { accountRepository.save(it) }
        val sales = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales").also { accountRepository.save(it) }
        return World(company, period, cash, sales)
    }

    private fun receipt(w: World, date: LocalDate, amount: String, description: String) =
        JournalEntry.create(
            w.period.id, date,
            listOf(
                JournalLine(w.cash.id, Money(BigDecimal(amount), gbp), TransactionSide.DEBIT),
                JournalLine(w.sales.id, Money(BigDecimal(amount), gbp), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL, description
        ).also { it.post() }

    private fun payment(w: World, date: LocalDate, amount: String, description: String) =
        JournalEntry.create(
            w.period.id, date,
            listOf(
                JournalLine(w.sales.id, Money(BigDecimal(amount), gbp), TransactionSide.DEBIT),
                JournalLine(w.cash.id, Money(BigDecimal(amount), gbp), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL, description
        ).also { it.post() }

    @Test
    fun `given entries of one day saved in turn, when read by account, company or period, then every read returns them in the order they were recorded`() {
        val w = world()
        val today = LocalDate.now()
        val first = receipt(w, today, "100.00", "First").also { journalEntryRepository.save(it) }
        val second = payment(w, today, "30.00", "Second").also { journalEntryRepository.save(it) }
        val third = receipt(w, today, "5.00", "Third").also { journalEntryRepository.save(it) }
        val expected = listOf(first.id, second.id, third.id)

        journalEntryRepository.findAllByAccount(w.cash.id).map { it.id } shouldBe expected
        journalEntryRepository.findAllByCompany(w.company.id).map { it.id } shouldBe expected
        journalEntryRepository.findAllByPeriod(w.period.id).map { it.id } shouldBe expected
    }

    @Test
    fun `given entries on different days, when read, then the day decides before the recorded order`() {
        val w = world()
        val today = LocalDate.now()
        val laterDayRecordedFirst = receipt(w, today.plusDays(2), "10.00", "Later day").also { journalEntryRepository.save(it) }
        val earlierDayRecordedLast = receipt(w, today, "20.00", "Earlier day").also { journalEntryRepository.save(it) }

        journalEntryRepository.findAllByAccount(w.cash.id).map { it.id } shouldBe listOf(earlierDayRecordedLast.id, laterDayRecordedFirst.id)
    }

    @Test
    fun `given stored entries, when read, then each carries a recorded order that grows with each insert and does not change when the entry is saved again`() {
        val w = world()
        val first = receipt(w, LocalDate.now(), "1.00", "A").also { journalEntryRepository.save(it) }
        val second = receipt(w, LocalDate.now(), "2.00", "B").also { journalEntryRepository.save(it) }

        val firstSeq = journalEntryRepository.findById(first.id)!!.recordedSeq!!
        val secondSeq = journalEntryRepository.findById(second.id)!!.recordedSeq!!
        (secondSeq > firstSeq) shouldBe true

        journalEntryRepository.save(first)
        journalEntryRepository.findById(first.id)!!.recordedSeq shouldBe firstSeq
    }

    @Test
    fun `given same-day entries stored in turn, when the cash book is built from what the repository returns, then the rows and running balance follow the recorded order on every read`() {
        val w = world()
        val today = LocalDate.now()
        receipt(w, today, "100.00", "First").also { journalEntryRepository.save(it) }
        payment(w, today, "30.00", "Second").also { journalEntryRepository.save(it) }
        receipt(w, today, "5.00", "Third").also { journalEntryRepository.save(it) }
        val accounts = mapOf(w.cash.id to w.cash, w.sales.id to w.sales)

        repeat(3) {
            val book = CashBook.of(w.cash, journalEntryRepository.findAllByAccount(w.cash.id), today, today, gbp, accounts)
            book.rows.map { it.description } shouldBe listOf("First", "Second", "Third")
            book.rows.map { it.balance.amount.toPlainString() } shouldBe listOf("100.00", "70.00", "75.00")
        }
    }

    @Test
    fun `given an entry is stored, then it reads back with the time the database recorded it, apart from its accounting date`() {
        val w = world()
        val before = java.time.Instant.now().minusSeconds(5)
        val entry = receipt(w, LocalDate.now().minusDays(1), "10.00", "Stored now")

        journalEntryRepository.save(entry)
        val read = journalEntryRepository.findById(entry.id)!!

        val recordedAt = read.recordedAt!!
        (recordedAt.isAfter(before)) shouldBe true
        (recordedAt.isBefore(java.time.Instant.now().plusSeconds(60))) shouldBe true
        read.date shouldBe LocalDate.now().minusDays(1)
        (read.recordedSeq != null) shouldBe true
        // The time is the database's, written once: saving the same entry again does not move it.
        journalEntryRepository.save(entry)
        journalEntryRepository.findById(entry.id)!!.recordedAt shouldBe recordedAt
    }
}
