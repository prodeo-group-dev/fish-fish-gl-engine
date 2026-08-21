package com.theprodeogroup.fish.domain.payroll

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 1, 25)

class PayRunTest {

    @Test
    fun `given wages and salaries, when posted, then it debits each expense account and credits Cash for the total tagged Operating`() {
        val wagesAccountId = AccountId.generate()
        val salariesAccountId = AccountId.generate()
        val cashAccountId = AccountId.generate()
        val payRun = PayRun.create(
            CompanyId.generate(), TODAY, Money(BigDecimal("8000.00"), GBP), Money(BigDecimal("5000.00"), GBP)
        )

        val entry = payRun.post(wagesAccountId, salariesAccountId, cashAccountId, PeriodId.generate())

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        entry.source shouldBe JournalSource.INTEGRATION
        val wagesLine = entry.lines.first { it.accountId == wagesAccountId }
        wagesLine.side shouldBe TransactionSide.DEBIT
        wagesLine.amount shouldBe Money(BigDecimal("8000.00"), GBP)
        val salariesLine = entry.lines.first { it.accountId == salariesAccountId }
        salariesLine.side shouldBe TransactionSide.DEBIT
        salariesLine.amount shouldBe Money(BigDecimal("5000.00"), GBP)
        val cashLine = entry.lines.first { it.accountId == cashAccountId }
        cashLine.side shouldBe TransactionSide.CREDIT
        cashLine.amount shouldBe Money(BigDecimal("13000.00"), GBP)
        cashLine.dimensions[DimensionType.CASH_FLOW_ACTIVITY] shouldBe CashFlowActivity.OPERATING.name
    }

    @Test
    fun `given only salaries (an all-office pay run), when posted, then the wages line is omitted`() {
        val salariesAccountId = AccountId.generate()
        val cashAccountId = AccountId.generate()
        val payRun = PayRun.create(
            CompanyId.generate(), TODAY, Money(BigDecimal.ZERO, GBP), Money(BigDecimal("5000.00"), GBP)
        )

        val entry = payRun.post(AccountId.generate(), salariesAccountId, cashAccountId, PeriodId.generate())

        entry.lines shouldHaveSize 2
        entry.lines.first { it.accountId == cashAccountId }.amount shouldBe Money(BigDecimal("5000.00"), GBP)
    }

    @Test
    fun `given only wages (an all-production pay run), when posted, then the salaries line is omitted`() {
        val wagesAccountId = AccountId.generate()
        val cashAccountId = AccountId.generate()
        val payRun = PayRun.create(
            CompanyId.generate(), TODAY, Money(BigDecimal("8000.00"), GBP), Money(BigDecimal.ZERO, GBP)
        )

        val entry = payRun.post(wagesAccountId, AccountId.generate(), cashAccountId, PeriodId.generate())

        entry.lines shouldHaveSize 2
        entry.lines.first { it.accountId == cashAccountId }.amount shouldBe Money(BigDecimal("8000.00"), GBP)
    }

    @Test
    fun `given wages and salaries both zero, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            PayRun.create(CompanyId.generate(), TODAY, Money(BigDecimal.ZERO, GBP), Money(BigDecimal.ZERO, GBP))
        }
    }

    @Test
    fun `given a negative total, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            PayRun.create(CompanyId.generate(), TODAY, Money(BigDecimal("-100.00"), GBP), Money(BigDecimal("5000.00"), GBP))
        }
    }

    @Test
    fun `given mismatched currencies, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            PayRun.create(
                CompanyId.generate(), TODAY,
                Money(BigDecimal("8000.00"), GBP), Money(BigDecimal("5000.00"), Currency.getInstance("USD"))
            )
        }
    }

    @Test
    fun `given a PayRun, when posted, then the date and period are carried over onto the JournalEntry`() {
        val periodId = PeriodId.generate()
        val payRun = PayRun.create(
            CompanyId.generate(), TODAY, Money(BigDecimal("8000.00"), GBP), Money(BigDecimal("5000.00"), GBP)
        )

        val entry = payRun.post(AccountId.generate(), AccountId.generate(), AccountId.generate(), periodId)

        entry.date shouldBe TODAY
        entry.periodId shouldBe periodId
    }
}
