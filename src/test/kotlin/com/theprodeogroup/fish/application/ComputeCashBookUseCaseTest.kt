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
import com.theprodeogroup.fish.domain.ledger.CashBookKind
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
    private val useCase = ComputeCashBookUseCase(companies, accounts, entries, periods)

    private val company = Company.create(TenantId.generate(), "Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
    private val period = Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).also { it.open(); periods.save(it) }
    private val cash = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH).also { accounts.save(it) }
    private val sales = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales").also { accounts.save(it) }

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun receipt(date: String, amount: String, periodId: PeriodId = period.id) =
        JournalEntry.create(
            periodId, LocalDate.parse(date),
            listOf(JournalLine(cash.id, money(amount), TransactionSide.DEBIT), JournalLine(sales.id, money(amount), TransactionSide.CREDIT)),
            JournalSource.MANUAL, "Takings"
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
}
