package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.BankReconciliation
import com.theprodeogroup.fish.domain.ledger.BankStatementLine
import com.theprodeogroup.fish.domain.ledger.BankStatementLineId
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.CashDirection
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
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

class ComputeCashBookUseCaseTest {

    private val gbp = Currency.getInstance("GBP")
    private val today = LocalDate.of(2026, 10, 20)
    private val companies = FakeCompanyRepository()
    private val accounts = FakeAccountRepository()
    private val periods = FakePeriodRepository()
    private val entries = FakeJournalEntryRepository()
    private val reconciliations = FakeBankReconciliationRepository()
    private val useCase = ComputeCashBookUseCase(companies, accounts, entries, periods, reconciliations)

    private val company = Company.create(TenantId.generate(), "Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
    private val period = Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).also { it.open(); periods.save(it) }
    private val cash = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH).also { accounts.save(it) }
    private val sales = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales").also { accounts.save(it) }

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun receipt(date: String, amount: String, periodId: PeriodId = period.id, source: JournalSource = JournalSource.CASH_BOOK) =
        JournalEntry.create(
            periodId, LocalDate.parse(date),
            listOf(JournalLine(cash.id, money(amount), TransactionSide.DEBIT), JournalLine(sales.id, money(amount), TransactionSide.CREDIT)),
            source, "Takings"
        ).also { it.post(); entries.save(it) }

    @Test
    fun `given no range, then the book is this month to date`() {
        receipt("2026-10-05", "100.00")
        receipt("2026-09-28", "40.00")
        receipt("2026-10-25", "7.00") // after today

        val result = useCase.execute(company.id, cash.id, today = today).shouldBeInstanceOf<ComputeCashBookUseCase.Result.Success>()

        result.book.from shouldBe LocalDate.of(2026, 10, 1)
        result.book.to shouldBe today
        result.book.openingBalance shouldBe money("40.00")
        result.book.closingBalance shouldBe money("140.00")
    }

    @Test
    fun `given an account of another Company, then it is not found and the book never confirms it exists`() {
        val other = Company.create(TenantId.generate(), "Other", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
        val foreign = Account.create(other.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH).also { accounts.save(it) }

        useCase.execute(company.id, foreign.id, today = today) shouldBe ComputeCashBookUseCase.Result.AccountNotFound
    }

    @Test
    fun `given an account without a kind, then it has no book`() {
        val plain = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Receivables").also { accounts.save(it) }

        useCase.execute(company.id, plain.id, today = today) shouldBe ComputeCashBookUseCase.Result.NotACashBook
    }

    @Test
    fun `given an unknown Company, then it is not found`() {
        useCase.execute(CompanyId.generate(), cash.id, today = today) shouldBe ComputeCashBookUseCase.Result.CompanyNotFound
    }

    @Test
    fun `given a range that ends before it starts, then it is invalid`() {
        useCase.execute(company.id, cash.id, LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 1), today) shouldBe ComputeCashBookUseCase.Result.InvalidRange
    }

    @Test
    fun `given more rows than the limit in range, then it is refused with the count and the limit`() {
        repeat(ComputeCashBookUseCase.ROW_LIMIT + 1) { receipt("2026-10-05", "1.00") }

        val result = useCase.execute(company.id, cash.id, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), today)

        result shouldBe ComputeCashBookUseCase.Result.RangeTooLarge(ComputeCashBookUseCase.ROW_LIMIT + 1, ComputeCashBookUseCase.ROW_LIMIT)
    }

    @Test
    fun `given a posted entry in an open Period, then Undo is allowed`() {
        val entry = receipt("2026-10-05", "100.00")

        val result = useCase.execute(company.id, cash.id, today = today).shouldBeInstanceOf<ComputeCashBookUseCase.Result.Success>()

        result.canUndo.getValue(entry.id) shouldBe true
    }

    @Test
    fun `given entries that did not start in a cash or bank book, then Undo is not offered - they are reversed in their own module`() {
        val journal = receipt("2026-10-05", "10.00", source = JournalSource.MANUAL)
        val collection = receipt("2026-10-06", "20.00", source = JournalSource.INTEGRATION)
        val api = receipt("2026-10-07", "30.00", source = JournalSource.API)
        val imported = receipt("2026-10-08", "40.00", source = JournalSource.IMPORT)
        val system = receipt("2026-10-09", "50.00", source = JournalSource.SYSTEM)

        val result = useCase.execute(company.id, cash.id, today = today).shouldBeInstanceOf<ComputeCashBookUseCase.Result.Success>()

        listOf(journal, collection, api, imported, system).forEach { result.canUndo.getValue(it.id) shouldBe false }
    }

    @Test
    fun `given an entry that was reversed, then neither the original nor the reversal can be undone`() {
        val original = receipt("2026-10-05", "100.00")
        val reversal = original.reverse(Instant.parse("2026-10-06T09:00:00Z"))!!.also { entries.save(it) }
        entries.save(original)

        val result = useCase.execute(company.id, cash.id, today = today).shouldBeInstanceOf<ComputeCashBookUseCase.Result.Success>()

        result.canUndo.getValue(original.id) shouldBe false
        result.canUndo.getValue(reversal.id) shouldBe false
    }

    @Test
    fun `given an entry whose Period is closed, then Undo is not allowed`() {
        val closed = Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).also { it.open(); it.close(); periods.save(it) }
        val entry = receipt("2026-09-10", "55.00", closed.id)

        val result = useCase.execute(company.id, cash.id, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), today).shouldBeInstanceOf<ComputeCashBookUseCase.Result.Success>()

        result.canUndo.getValue(entry.id) shouldBe false
    }

    // ---- reconciled flag (T8) ----

    private val bank = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank", cashBookKind = CashBookKind.BANK).also { accounts.save(it) }

    private fun bankReceipt(date: String, amount: String): JournalEntry =
        JournalEntry.create(
            period.id, LocalDate.parse(date),
            listOf(JournalLine(bank.id, money(amount), TransactionSide.DEBIT), JournalLine(sales.id, money(amount), TransactionSide.CREDIT)),
            JournalSource.CASH_BOOK, "Bank receipt"
        ).also { it.post(); entries.save(it) }

    /** A reconciliation of the bank account that matches [matched] to statement lines, completed or left open. */
    private fun reconcile(matched: JournalEntry, complete: Boolean, cancel: Boolean = false) {
        val line = BankStatementLine(BankStatementLineId.generate(), matched.date, money("100.00"), CashDirection.RECEIVED, "Statement line")
        val rec = BankReconciliation.create(bank.id, LocalDate.of(2026, 10, 31), money("100.00"), listOf(line), listOf(matched), gbp)
        rec.match(line.id, matched.id)
        if (complete) rec.complete()
        if (cancel) rec.cancel()
        reconciliations.save(rec, company.id)
    }

    @Test
    fun `given a bank account with one entry in a completed reconciliation and one not, then only the matched one is reconciled`() {
        val matched = bankReceipt("2026-10-05", "100.00")
        val loose = bankReceipt("2026-10-06", "30.00")
        reconcile(matched, complete = true)

        val result = useCase.execute(company.id, bank.id, today = today).shouldBeInstanceOf<ComputeCashBookUseCase.Result.Success>()

        result.reconciledEntryIds shouldBe setOf(matched.id)
        (loose.id in result.reconciledEntryIds!!) shouldBe false
    }

    @Test
    fun `given matches in a reconciliation that is still open, or cancelled, then nothing is reconciled yet`() {
        val a = bankReceipt("2026-10-05", "100.00")
        reconcile(a, complete = false)

        useCase.execute(company.id, bank.id, today = today).shouldBeInstanceOf<ComputeCashBookUseCase.Result.Success>().reconciledEntryIds shouldBe emptySet()
    }

    @Test
    fun `given a cash book, then it has no reconciled information at all`() {
        useCase.execute(company.id, cash.id, today = today).shouldBeInstanceOf<ComputeCashBookUseCase.Result.Success>().reconciledEntryIds shouldBe null
    }

    @Test
    fun `given a completed reconciliation of another bank account, then it does not mark this account's entries`() {
        val other = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1011", "Second bank", cashBookKind = CashBookKind.BANK).also { accounts.save(it) }
        val mine = bankReceipt("2026-10-05", "100.00")
        val theirs = JournalEntry.create(
            period.id, LocalDate.of(2026, 10, 5),
            listOf(JournalLine(other.id, money("100.00"), TransactionSide.DEBIT), JournalLine(sales.id, money("100.00"), TransactionSide.CREDIT)),
            JournalSource.CASH_BOOK
        ).also { it.post(); entries.save(it) }
        val line = BankStatementLine(BankStatementLineId.generate(), theirs.date, money("100.00"), CashDirection.RECEIVED, "x")
        val rec = BankReconciliation.create(other.id, LocalDate.of(2026, 10, 31), money("100.00"), listOf(line), listOf(theirs), gbp)
        rec.match(line.id, theirs.id)
        rec.complete()
        reconciliations.save(rec, company.id)

        useCase.execute(company.id, bank.id, today = today).shouldBeInstanceOf<ComputeCashBookUseCase.Result.Success>().reconciledEntryIds shouldBe emptySet()
        (mine.id in setOf<JournalEntryId>()) shouldBe false
    }
}
