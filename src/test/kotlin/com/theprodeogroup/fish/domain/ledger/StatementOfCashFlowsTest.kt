package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val PERIOD_START = LocalDate.of(2026, 2, 1)
private val PERIOD_END = LocalDate.of(2026, 2, 28)

class StatementOfCashFlowsTest {

    @Test
    fun `given no entries, when computed, then opening, closing, and net cash flow are all zero`() {
        val cashAccount = cashAccount()

        val statement = StatementOfCashFlows.of(cashAccount, emptyList(), PERIOD_START, PERIOD_END, GBP)

        statement.openingBalance shouldBe zero()
        statement.closingBalance shouldBe zero()
        statement.netCashFlow shouldBe zero()
    }

    @Test
    fun `given an entry before the period, when computed, then it contributes to opening balance but not net cash flow`() {
        val cashAccount = cashAccount()
        val entry = cashEntry(cashAccount.id, Money(BigDecimal("500.00"), GBP), PERIOD_START.minusDays(10))

        val statement = StatementOfCashFlows.of(cashAccount, listOf(entry), PERIOD_START, PERIOD_END, GBP)

        statement.openingBalance shouldBe Money(BigDecimal("500.00"), GBP)
        statement.closingBalance shouldBe Money(BigDecimal("500.00"), GBP)
        statement.netCashFlow shouldBe zero()
    }

    @Test
    fun `given entries within the period, when computed, then they contribute to net cash flow but not opening balance`() {
        val cashAccount = cashAccount()
        val entry = cashEntry(cashAccount.id, Money(BigDecimal("300.00"), GBP), PERIOD_START.plusDays(5))

        val statement = StatementOfCashFlows.of(cashAccount, listOf(entry), PERIOD_START, PERIOD_END, GBP)

        statement.openingBalance shouldBe zero()
        statement.closingBalance shouldBe Money(BigDecimal("300.00"), GBP)
        statement.netCashFlow shouldBe Money(BigDecimal("300.00"), GBP)
    }

    @Test
    fun `given entries both before and within the period, when computed, then opening plus net cash flow equals closing`() {
        val cashAccount = cashAccount()
        val before = cashEntry(cashAccount.id, Money(BigDecimal("500.00"), GBP), PERIOD_START.minusDays(10))
        val within = cashEntry(cashAccount.id, Money(BigDecimal("300.00"), GBP), PERIOD_START.plusDays(5))

        val statement = StatementOfCashFlows.of(cashAccount, listOf(before, within), PERIOD_START, PERIOD_END, GBP)

        statement.openingBalance shouldBe Money(BigDecimal("500.00"), GBP)
        statement.closingBalance shouldBe Money(BigDecimal("800.00"), GBP)
        statement.netCashFlow shouldBe Money(BigDecimal("300.00"), GBP)
    }

    @Test
    fun `given an entry on the end date, when computed, then it is included in the closing balance`() {
        val cashAccount = cashAccount()
        val entry = cashEntry(cashAccount.id, Money(BigDecimal("100.00"), GBP), PERIOD_END)

        val statement = StatementOfCashFlows.of(cashAccount, listOf(entry), PERIOD_START, PERIOD_END, GBP)

        statement.closingBalance shouldBe Money(BigDecimal("100.00"), GBP)
    }

    @Test
    fun `given an entry after the end date, when computed, then it is excluded entirely`() {
        val cashAccount = cashAccount()
        val entry = cashEntry(cashAccount.id, Money(BigDecimal("100.00"), GBP), PERIOD_END.plusDays(1))

        val statement = StatementOfCashFlows.of(cashAccount, listOf(entry), PERIOD_START, PERIOD_END, GBP)

        statement.closingBalance shouldBe zero()
    }

    @Test
    fun `given an unposted Draft entry within the period, when computed, then it is excluded`() {
        val cashAccount = cashAccount()
        val draft = JournalEntry.create(
            PeriodId.generate(), PERIOD_START.plusDays(5),
            listOf(
                JournalLine(cashAccount.id, Money(BigDecimal("300.00"), GBP), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("300.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )

        val statement = StatementOfCashFlows.of(cashAccount, listOf(draft), PERIOD_START, PERIOD_END, GBP)

        statement.netCashFlow shouldBe zero()
    }

    @Test
    fun `given an end date before the start date, when computed, then it fails`() {
        val cashAccount = cashAccount()

        shouldThrow<IllegalArgumentException> {
            StatementOfCashFlows.of(cashAccount, emptyList(), PERIOD_END, PERIOD_START, GBP)
        }
    }

    private fun cashAccount(): Account = Account.create(
        CompanyId.generate(), AccountType.ASSET, AccountClassification.CURRENT, "1000", "Bank Current Account"
    )

    private fun cashEntry(cashAccountId: AccountId, amount: Money, date: LocalDate): JournalEntry {
        val entry = JournalEntry.create(
            PeriodId.generate(), date,
            listOf(
                JournalLine(cashAccountId, amount, TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), amount, TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        entry.post()
        return entry
    }

    private fun zero(): Money = Money(BigDecimal.ZERO, GBP)
}
