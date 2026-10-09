package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * UAT v2.2: month-end and year-end packs need profit and loss "from - to", not just the open Period.
 * A date range is inclusive at both ends and may span several Periods.
 */
class ProfitAndLossForDatesTest {

    private val gbp: Currency = Currency.getInstance("GBP")
    private val company = CompanyId.generate()
    private val cash = Account.create(company, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash")
    private val sales = Account.create(company, AccountType.REVENUE, null, "4000", "Sales")
    private val rent = Account.create(company, AccountType.EXPENSE, null, "5000", "Rent")
    private val accounts = listOf(cash, sales, rent)

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun posted(period: PeriodId, date: LocalDate, vararg lines: JournalLine) =
        JournalEntry.create(period, date, lines.toList(), JournalSource.MANUAL).also { it.post() }

    private fun sale(period: PeriodId, date: String, amount: String) = posted(
        period, LocalDate.parse(date),
        JournalLine(cash.id, money(amount), TransactionSide.DEBIT), JournalLine(sales.id, money(amount), TransactionSide.CREDIT)
    )

    private fun rent(period: PeriodId, date: String, amount: String) = posted(
        period, LocalDate.parse(date),
        JournalLine(rent.id, money(amount), TransactionSide.DEBIT), JournalLine(cash.id, money(amount), TransactionSide.CREDIT)
    )

    private val janPeriod = PeriodId.generate()
    private val febPeriod = PeriodId.generate()

    private val entries = listOf(
        sale(janPeriod, "2026-01-31", "100.00"),
        rent(janPeriod, "2026-01-15", "30.00"),
        sale(febPeriod, "2026-02-01", "200.00"),
        rent(febPeriod, "2026-02-28", "50.00"),
        sale(febPeriod, "2026-03-01", "999.00")
    )

    @Test
    fun `a range includes both of its end dates`() {
        val pl = ProfitAndLossForDates.of(accounts, entries, LocalDate.parse("2026-01-31"), LocalDate.parse("2026-02-01"), gbp)

        pl.totalRevenue shouldBe money("300.00")
        pl.totalExpense shouldBe money("0.00")
        pl.netIncome shouldBe money("300.00")
    }

    @Test
    fun `a range spans Periods, which the single-Period report cannot`() {
        val pl = ProfitAndLossForDates.of(accounts, entries, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-28"), gbp)

        pl.totalRevenue shouldBe money("300.00")
        pl.totalExpense shouldBe money("80.00")
        pl.netIncome shouldBe money("220.00")
    }

    @Test
    fun `entries outside the range are excluded`() {
        val pl = ProfitAndLossForDates.of(accounts, entries, LocalDate.parse("2026-02-02"), LocalDate.parse("2026-02-27"), gbp)

        pl.totalRevenue shouldBe money("0.00")
        pl.totalExpense shouldBe money("0.00")
    }

    @Test
    fun `an unposted draft is excluded`() {
        val draft = JournalEntry.create(
            janPeriod, LocalDate.parse("2026-01-20"),
            listOf(JournalLine(cash.id, money("500.00"), TransactionSide.DEBIT), JournalLine(sales.id, money("500.00"), TransactionSide.CREDIT)),
            JournalSource.MANUAL
        )

        val pl = ProfitAndLossForDates.of(accounts, entries + draft, LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"), gbp)

        pl.totalRevenue shouldBe money("100.00")
    }

    @Test
    fun `a range ending before it starts is refused`() {
        shouldThrow<IllegalArgumentException> {
            ProfitAndLossForDates.of(accounts, entries, LocalDate.parse("2026-02-01"), LocalDate.parse("2026-01-01"), gbp)
        }
    }
}
