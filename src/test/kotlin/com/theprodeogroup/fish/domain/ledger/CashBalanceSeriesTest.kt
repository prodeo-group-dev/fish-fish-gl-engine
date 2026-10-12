package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency

/** The combined end-of-day balance of a Company's cash and bank books over the last N days (the Business Owner dashboard's cash trend). */
class CashBalanceSeriesTest {

    private val gbp = Currency.getInstance("GBP")
    private val companyId = CompanyId.generate()
    private val periodId = PeriodId.generate()
    private val cash = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH)
    private val bank = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank", cashBookKind = CashBookKind.BANK)
    private val receivable = Account.create(companyId, AccountType.ASSET, AccountClassification.CURRENT, "1100", "Receivables")
    private val sales = Account.create(companyId, AccountType.REVENUE, null, "4000", "Sales")
    private val rent = Account.create(companyId, AccountType.EXPENSE, null, "5000", "Rent")
    private val today = LocalDate.of(2026, 10, 12)

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun entry(date: LocalDate, debit: Account, credit: Account, amount: String, post: Boolean = true): JournalEntry =
        JournalEntry.create(
            periodId, date,
            listOf(JournalLine(debit.id, money(amount), TransactionSide.DEBIT), JournalLine(credit.id, money(amount), TransactionSide.CREDIT)),
            JournalSource.MANUAL, "Entry"
        ).also { if (post) it.post() }

    private fun series(entries: List<JournalEntry>, days: Int = 5, books: List<Account> = listOf(cash, bank)) =
        CashBalanceSeries.of(books, entries, today, days, gbp)

    private fun CashBalanceSeries.balances() = points.map { it.closingBalance.amount.toPlainString() }

    @Test
    fun `given receipts and payments, then each day closes at the running combined balance and quiet days carry it forward`() {
        val s = series(listOf(
            entry(today.minusDays(3), cash, sales, "100.00"),
            entry(today.minusDays(1), rent, bank, "30.00"),
            entry(today, bank, sales, "5.50")
        ))

        s.points.map { it.date } shouldBe (4 downTo 0).map { today.minusDays(it.toLong()) }
        s.balances() shouldBe listOf("0.00", "100.00", "100.00", "70.00", "75.50")
    }

    @Test
    fun `given entries before the window, then they form the opening balance of the first day`() {
        val s = series(listOf(entry(today.minusDays(40), cash, sales, "250.00"), entry(today.minusDays(1), cash, sales, "10.00")), days = 3)

        s.balances() shouldBe listOf("250.00", "260.00", "260.00")
    }

    @Test
    fun `given the window has exactly days points ending today and from is the first of them`() {
        val s = series(emptyList(), days = 30)

        s.points.size shouldBe 30
        s.to shouldBe today
        s.from shouldBe today.minusDays(29)
        s.points.first().date shouldBe s.from
        s.points.last().date shouldBe s.to
    }

    @Test
    fun `given one day, then there is a single point for today`() {
        val s = series(listOf(entry(today.minusDays(2), cash, sales, "40.00"), entry(today, cash, sales, "2.00")), days = 1)

        s.balances() shouldBe listOf("42.00")
    }

    @Test
    fun `given entries after today, then they are not counted`() {
        series(listOf(entry(today.plusDays(1), cash, sales, "999.00"), entry(today, cash, sales, "1.00")), days = 2)
            .balances() shouldBe listOf("0.00", "1.00")
    }

    @Test
    fun `given a transfer between two books, then the combined balance does not move`() {
        val s = series(listOf(
            entry(today.minusDays(2), cash, sales, "100.00"),
            entry(today.minusDays(1), bank, cash, "60.00")
        ), days = 3)

        s.balances() shouldBe listOf("100.00", "100.00", "100.00")
    }

    @Test
    fun `given entries that touch no cash or bank book, then they are ignored`() {
        series(listOf(entry(today, receivable, sales, "500.00")), days = 2).balances() shouldBe listOf("0.00", "0.00")
    }

    @Test
    fun `given a draft entry, then it does not count`() {
        series(listOf(entry(today, cash, sales, "500.00", post = false)), days = 1).balances() shouldBe listOf("0.00")
    }

    @Test
    fun `given an entry that was undone and its reversal, then they net to zero`() {
        val original = entry(today.minusDays(1), cash, sales, "75.00")
        val reversal = requireNotNull(original.reverse(Instant.parse("2026-10-12T09:00:00Z")))

        series(listOf(original, reversal), days = 2).balances() shouldBe listOf("75.00", "0.00")
    }

    @Test
    fun `given the same entry listed twice, then it counts once`() {
        val e = entry(today, cash, sales, "10.00")

        series(listOf(e, e), days = 1).balances() shouldBe listOf("10.00")
    }

    @Test
    fun `given a payment that takes the books below zero, then the balance is negative, as the cash book shows it`() {
        series(listOf(entry(today, rent, cash, "20.00")), days = 1).balances() shouldBe listOf("-20.00")
    }

    @Test
    fun `given no cash or bank books, then every point is zero`() {
        series(listOf(entry(today, cash, sales, "10.00")), days = 2, books = emptyList()).balances() shouldBe listOf("0.00", "0.00")
    }

    @Test
    fun `given a number of days outside 1 to 366, then it is refused`() {
        shouldThrow<IllegalArgumentException> { series(emptyList(), days = 0) }
        shouldThrow<IllegalArgumentException> { series(emptyList(), days = 367) }
        series(emptyList(), days = 366).points.size shouldBe 366
    }
}
