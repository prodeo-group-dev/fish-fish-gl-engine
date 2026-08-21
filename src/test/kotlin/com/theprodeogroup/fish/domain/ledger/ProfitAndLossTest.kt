package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 1, 15)

class ProfitAndLossTest {

    @Test
    fun `given Revenue and Expense postings in the Period, when computed, then totals and net income are correct`() {
        val companyId = CompanyId.generate()
        val periodId = PeriodId.generate()
        val cash = account(companyId, AccountType.ASSET)
        val revenue = account(companyId, AccountType.REVENUE)
        val rent = account(companyId, AccountType.EXPENSE)
        val entries = listOf(
            postedEntry(
                periodId,
                JournalLine(cash.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT),
                JournalLine(revenue.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)
            ),
            postedEntry(
                periodId,
                JournalLine(rent.id, Money(BigDecimal("300.00"), GBP), TransactionSide.DEBIT),
                JournalLine(cash.id, Money(BigDecimal("300.00"), GBP), TransactionSide.CREDIT)
            )
        )

        val pnl = ProfitAndLoss.of(listOf(cash, revenue, rent), entries, periodId, GBP)

        pnl.totalRevenue shouldBe Money(BigDecimal("1000.00"), GBP)
        pnl.totalExpense shouldBe Money(BigDecimal("300.00"), GBP)
        pnl.netIncome shouldBe Money(BigDecimal("700.00"), GBP)
    }

    @Test
    fun `given postings in a different Period, when computed for a specific Period, then they are excluded`() {
        val companyId = CompanyId.generate()
        val periodId = PeriodId.generate()
        val otherPeriodId = PeriodId.generate()
        val revenue = account(companyId, AccountType.REVENUE)
        val entry = postedEntry(
            otherPeriodId,
            JournalLine(AccountId.generate(), Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT),
            JournalLine(revenue.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)
        )

        val pnl = ProfitAndLoss.of(listOf(revenue), listOf(entry), periodId, GBP)

        pnl.totalRevenue shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given only Expense postings, when computed, then net income is negative`() {
        val companyId = CompanyId.generate()
        val periodId = PeriodId.generate()
        val rent = account(companyId, AccountType.EXPENSE)
        val entry = postedEntry(
            periodId,
            JournalLine(rent.id, Money(BigDecimal("300.00"), GBP), TransactionSide.DEBIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("300.00"), GBP), TransactionSide.CREDIT)
        )

        val pnl = ProfitAndLoss.of(listOf(rent), listOf(entry), periodId, GBP)

        pnl.netIncome shouldBe Money(BigDecimal("-300.00"), GBP)
    }

    @Test
    fun `given an unposted Draft entry in the Period, when computed, then it is excluded`() {
        val companyId = CompanyId.generate()
        val periodId = PeriodId.generate()
        val revenue = account(companyId, AccountType.REVENUE)
        val draft = JournalEntry.create(
            periodId, TODAY,
            listOf(
                JournalLine(AccountId.generate(), Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT),
                JournalLine(revenue.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )

        val pnl = ProfitAndLoss.of(listOf(revenue), listOf(draft), periodId, GBP)

        pnl.totalRevenue shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given no entries at all for the Period, when computed, then everything is zero`() {
        val companyId = CompanyId.generate()
        val periodId = PeriodId.generate()
        val revenue = account(companyId, AccountType.REVENUE)
        val rent = account(companyId, AccountType.EXPENSE)

        val pnl = ProfitAndLoss.of(listOf(revenue, rent), emptyList(), periodId, GBP)

        pnl.totalRevenue shouldBe Money(BigDecimal.ZERO, GBP)
        pnl.totalExpense shouldBe Money(BigDecimal.ZERO, GBP)
        pnl.netIncome shouldBe Money(BigDecimal.ZERO, GBP)
    }

    private fun account(companyId: CompanyId, type: AccountType): Account {
        val classification = if (type.requiresClassification()) AccountClassification.CURRENT else null
        return Account.create(companyId, type, classification, "4000", "Test Account")
    }

    private fun postedEntry(periodId: PeriodId, vararg lines: JournalLine): JournalEntry {
        val entry = JournalEntry.create(periodId, TODAY, lines.toList(), JournalSource.MANUAL)
        entry.post()
        return entry
    }
}
