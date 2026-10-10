package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Undo (a reversing entry) for an entry shown in a cash or bank book (docs/GL_Cash_And_Bank_Books_SRS.md, FR-CB26). */
class UndoCashBookEntryUseCaseTest {

    private val gbp = Currency.getInstance("GBP")
    private val now = Instant.parse("2026-10-13T09:00:00Z")
    private val companies = FakeCompanyRepository()
    private val accounts = FakeAccountRepository()
    private val periods = FakePeriodRepository()
    private val entries = FakeJournalEntryRepository()
    private val useCase = UndoCashBookEntryUseCase(companies, accounts, entries, periods)

    private val company = Company.create(TenantId.generate(), "Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
    private val period = Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).also { it.open(); periods.save(it) }
    private val cash = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH).also { accounts.save(it) }
    private val bank = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank", cashBookKind = CashBookKind.BANK).also { accounts.save(it) }
    private val sales = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales").also { accounts.save(it) }

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun receipt(book: Account = cash, amount: String = "100.00", post: Boolean = true): JournalEntry =
        JournalEntry.create(
            period.id, LocalDate.of(2026, 10, 5),
            listOf(JournalLine(book.id, money(amount), TransactionSide.DEBIT), JournalLine(sales.id, money(amount), TransactionSide.CREDIT)),
            JournalSource.CASH_BOOK, "Takings"
        ).also { if (post) it.post(); entries.save(it) }

    @Test
    fun `given a posted receipt, when it is undone, then a reversing entry is posted, the original is marked reversed and the balance is back`() {
        val entry = receipt()

        val result = useCase.execute(company.id, cash.id, entry.id, now).shouldBeInstanceOf<UndoCashBookEntryUseCase.Result.Undone>()

        result.reversal.reversalOfEntryId shouldBe entry.id
        result.reversal.source shouldBe JournalSource.REVERSAL
        result.reversal.lines.map { it.accountId to it.side } shouldBe listOf(cash.id to TransactionSide.CREDIT, sales.id to TransactionSide.DEBIT)
        result.balanceAfter shouldBe money("0.00")
        entries.findById(entry.id)!!.status shouldBe PostingStatus.REVERSED
        entries.findById(result.reversal.id)!!.status shouldBe PostingStatus.POSTED
    }

    @Test
    fun `given an entry already undone, when it is undone again, then it is refused and no second reversal exists`() {
        val entry = receipt()
        useCase.execute(company.id, cash.id, entry.id, now)

        useCase.execute(company.id, cash.id, entry.id, now) shouldBe UndoCashBookEntryUseCase.Result.AlreadyUndone

        entries.findAllByAccount(cash.id).count { it.reversalOfEntryId == entry.id } shouldBe 1
    }

    @Test
    fun `given a reversing entry, when it is undone, then it is not undoable - a reversal is never itself reversed`() {
        val entry = receipt()
        val reversal = (useCase.execute(company.id, cash.id, entry.id, now) as UndoCashBookEntryUseCase.Result.Undone).reversal

        useCase.execute(company.id, cash.id, reversal.id, now) shouldBe UndoCashBookEntryUseCase.Result.NotUndoable
    }

    private fun entryFrom(source: JournalSource, counter: Account, amount: String = "75.00"): JournalEntry =
        JournalEntry.create(
            period.id, LocalDate.of(2026, 10, 6),
            listOf(JournalLine(cash.id, money(amount), TransactionSide.DEBIT), JournalLine(counter.id, money(amount), TransactionSide.CREDIT)),
            source, "From elsewhere"
        ).also { it.post(); entries.save(it) }

    @Test
    fun `given a sales collection, a supplier payment, a pay run, or a journal, when Undo is asked here, then it is refused with where to reverse it and nothing changes`() {
        val receivables = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Receivables").also { accounts.save(it) }
        val payables = Account.create(company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2000", "Payables").also { accounts.save(it) }
        val wages = Account.create(company.id, AccountType.EXPENSE, null, "5200", "Wages").also { accounts.save(it) }
        val collection = entryFrom(JournalSource.INTEGRATION, receivables)
        val payment = entryFrom(JournalSource.API, payables)
        val payRun = entryFrom(JournalSource.INTEGRATION, wages)
        val journal = entryFrom(JournalSource.MANUAL, sales)

        useCase.execute(company.id, cash.id, collection.id, now) shouldBe UndoCashBookEntryUseCase.Result.UndoElsewhere(com.theprodeogroup.fish.domain.ledger.UseInstead.SALES_COLLECTION)
        useCase.execute(company.id, cash.id, payment.id, now) shouldBe UndoCashBookEntryUseCase.Result.UndoElsewhere(com.theprodeogroup.fish.domain.ledger.UseInstead.PURCHASE_PAYMENT)
        useCase.execute(company.id, cash.id, payRun.id, now) shouldBe UndoCashBookEntryUseCase.Result.UndoElsewhere(com.theprodeogroup.fish.domain.ledger.UseInstead.PAYROLL)
        useCase.execute(company.id, cash.id, journal.id, now) shouldBe UndoCashBookEntryUseCase.Result.UndoElsewhere(com.theprodeogroup.fish.domain.ledger.UseInstead.ORIGINAL_SCREEN)
        listOf(collection, payment, payRun, journal).forEach {
            entries.findById(it.id)!!.status shouldBe PostingStatus.POSTED
        }
        entries.findAllByAccount(cash.id).none { it.reversalOfEntryId != null } shouldBe true
    }

    /** A repository that refuses any plain save: Undo must reach the database through its one atomic write, or this fails. */
    private class NoSeparateSave(private val delegate: FakeJournalEntryRepository) : com.theprodeogroup.fish.domain.ledger.JournalEntryRepository by delegate {
        override fun save(entry: JournalEntry) = throw IllegalStateException("Undo wrote the original on its own, outside the one transaction")
    }

    @Test
    fun `given Undo, then the reversal and the original's new status are one write - there is no moment with one but not the other`() {
        val entry = receipt()
        val strict = UndoCashBookEntryUseCase(companies, accounts, NoSeparateSave(entries), periods)

        strict.execute(company.id, cash.id, entry.id, now).shouldBeInstanceOf<UndoCashBookEntryUseCase.Result.Undone>()

        entries.findById(entry.id)!!.status shouldBe PostingStatus.REVERSED
        entries.findAllByAccount(cash.id).count { it.reversalOfEntryId == entry.id } shouldBe 1
    }

    @Test
    fun `given a draft entry, then it is not undoable`() {
        val draft = receipt(post = false)

        useCase.execute(company.id, cash.id, draft.id, now) shouldBe UndoCashBookEntryUseCase.Result.NotUndoable
    }

    @Test
    fun `given an entry whose Period is closed, then it is refused and nothing changes`() {
        val closed = Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).also { it.open(); it.close(); periods.save(it) }
        val entry = JournalEntry.create(
            closed.id, LocalDate.of(2026, 9, 10),
            listOf(JournalLine(cash.id, money("20.00"), TransactionSide.DEBIT), JournalLine(sales.id, money("20.00"), TransactionSide.CREDIT)),
            JournalSource.CASH_BOOK
        ).also { it.post(); entries.save(it) }

        useCase.execute(company.id, cash.id, entry.id, now) shouldBe UndoCashBookEntryUseCase.Result.PeriodNotOpen
        entries.findById(entry.id)!!.status shouldBe PostingStatus.POSTED
    }

    @Test
    fun `given an entry that is on a different book, then it is not found from this book`() {
        val onBank = receipt(book = bank)

        useCase.execute(company.id, cash.id, onBank.id, now) shouldBe UndoCashBookEntryUseCase.Result.EntryNotFound
        entries.findById(onBank.id)!!.status shouldBe PostingStatus.POSTED
    }

    @Test
    fun `given another Company's book or entry, then nothing is found and nothing is reversed`() {
        val other = Company.create(TenantId.generate(), "Other", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
        val otherCash = Account.create(other.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH).also { accounts.save(it) }
        val otherPeriod = Period.create(other.id, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).also { it.open(); periods.save(it) }
        val theirs = JournalEntry.create(
            otherPeriod.id, LocalDate.of(2026, 10, 5),
            listOf(JournalLine(otherCash.id, money("9.00"), TransactionSide.DEBIT), JournalLine(sales.id, money("9.00"), TransactionSide.CREDIT)),
            JournalSource.CASH_BOOK
        ).also { it.post(); entries.save(it) }

        useCase.execute(company.id, otherCash.id, theirs.id, now) shouldBe UndoCashBookEntryUseCase.Result.AccountNotFound
        useCase.execute(company.id, cash.id, theirs.id, now) shouldBe UndoCashBookEntryUseCase.Result.EntryNotFound
        entries.findById(theirs.id)!!.status shouldBe PostingStatus.POSTED
    }

    @Test
    fun `given an account that is not a cash book, or an unknown Company, then it is refused`() {
        val plain = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1500", "Land").also { accounts.save(it) }
        val entry = receipt()

        useCase.execute(company.id, plain.id, entry.id, now) shouldBe UndoCashBookEntryUseCase.Result.NotACashBook
        useCase.execute(CompanyId.generate(), cash.id, entry.id, now) shouldBe UndoCashBookEntryUseCase.Result.CompanyNotFound
    }

    /**
     * A repository that hands out a fresh copy on every read and stores a copy on every write, the way the database
     * does. The plain fake returns one shared object, so two threads would see each other's in-memory status change
     * and the race this guards against could not happen.
     */
    private class CopyingEntries(private val delegate: FakeJournalEntryRepository) : com.theprodeogroup.fish.domain.ledger.JournalEntryRepository by delegate {
        private fun JournalEntry.copy() = JournalEntry.reconstitute(id, periodId, date, lines, source, description, status, reversalOfEntryId)
        override fun findById(id: com.theprodeogroup.fish.domain.ledger.JournalEntryId) = delegate.findById(id)?.copy()
        override fun save(entry: JournalEntry) = delegate.save(entry.copy())
        override fun insertIfAbsent(entry: JournalEntry) = delegate.insertIfAbsent(entry.copy())
    }

    @Test
    fun `given several simultaneous undos of one entry read as separate copies, then exactly one reversal is posted`() {
        val entry = receipt()
        val copying = CopyingEntries(entries)
        val concurrent = UndoCashBookEntryUseCase(companies, accounts, copying, periods)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val futures = (1..8).map { pool.submit<UndoCashBookEntryUseCase.Result> { start.await(); concurrent.execute(company.id, cash.id, entry.id, now) } }
        start.countDown()
        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        results.count { it is UndoCashBookEntryUseCase.Result.Undone } shouldBe 1
        results.count { it == UndoCashBookEntryUseCase.Result.AlreadyUndone } shouldBe 7
        entries.findAllByAccount(cash.id).count { it.reversalOfEntryId == entry.id } shouldBe 1
    }

    @Test
    fun `given an entry with a description, when it is undone, then the reversal says Undo of that description in plain words`() {
        val entry = receipt()

        val result = useCase.execute(company.id, cash.id, entry.id, now).shouldBeInstanceOf<UndoCashBookEntryUseCase.Result.Undone>()

        result.reversal.description shouldBe "Undo of Takings"
    }

    @Test
    fun `given an entry with no description, when it is undone, then the reversal says Undo of an earlier entry`() {
        val entry = JournalEntry.create(
            period.id, LocalDate.of(2026, 10, 5),
            listOf(JournalLine(cash.id, money("5.00"), TransactionSide.DEBIT), JournalLine(sales.id, money("5.00"), TransactionSide.CREDIT)),
            JournalSource.CASH_BOOK
        ).also { it.post(); entries.save(it) }

        val result = useCase.execute(company.id, cash.id, entry.id, now).shouldBeInstanceOf<UndoCashBookEntryUseCase.Result.Undone>()

        result.reversal.description shouldBe "Undo of an earlier entry"
    }
}
