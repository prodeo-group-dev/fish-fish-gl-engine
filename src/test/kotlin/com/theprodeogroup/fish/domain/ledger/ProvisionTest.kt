package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 1, 15)

class ProvisionTest {

    @Test
    fun `given a new Provision, when created, then its balance is zero`() {
        val provision = warrantyProvision()

        provision.balance shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a new Provision, when remeasured to an initial estimate, then it posts the full amount as a charge`() {
        val expenseAccountId = AccountId.generate()
        val provisionAccountId = AccountId.generate()
        val provision = warrantyProvision()

        val entry = requireNotNull(
            provision.remeasure(Money(BigDecimal("5000.00"), GBP), expenseAccountId, provisionAccountId, PeriodId.generate(), TODAY)
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val expenseLine = entry.lines.first { it.accountId == expenseAccountId }
        expenseLine.side shouldBe TransactionSide.DEBIT
        expenseLine.amount shouldBe Money(BigDecimal("5000.00"), GBP)
        val provisionLine = entry.lines.first { it.accountId == provisionAccountId }
        provisionLine.side shouldBe TransactionSide.CREDIT
        provisionLine.amount shouldBe Money(BigDecimal("5000.00"), GBP)
        provision.balance shouldBe Money(BigDecimal("5000.00"), GBP)
    }

    @Test
    fun `given a Provision with a balance, when remeasured to a higher estimate, then it posts only the top-up delta`() {
        val expenseAccountId = AccountId.generate()
        val provisionAccountId = AccountId.generate()
        val provision = warrantyProvision()
        provision.remeasure(Money(BigDecimal("5000.00"), GBP), expenseAccountId, provisionAccountId, PeriodId.generate(), TODAY)

        val entry = requireNotNull(
            provision.remeasure(Money(BigDecimal("7000.00"), GBP), expenseAccountId, provisionAccountId, PeriodId.generate(), TODAY)
        )

        val expenseLine = entry.lines.first { it.accountId == expenseAccountId }
        expenseLine.side shouldBe TransactionSide.DEBIT
        expenseLine.amount shouldBe Money(BigDecimal("2000.00"), GBP)
        provision.balance shouldBe Money(BigDecimal("7000.00"), GBP)
    }

    @Test
    fun `given a Provision with a balance, when remeasured to a lower estimate, then it posts a reversal for the delta`() {
        val expenseAccountId = AccountId.generate()
        val provisionAccountId = AccountId.generate()
        val provision = warrantyProvision()
        provision.remeasure(Money(BigDecimal("5000.00"), GBP), expenseAccountId, provisionAccountId, PeriodId.generate(), TODAY)

        val entry = requireNotNull(
            provision.remeasure(Money(BigDecimal("3000.00"), GBP), expenseAccountId, provisionAccountId, PeriodId.generate(), TODAY)
        )

        val provisionLine = entry.lines.first { it.accountId == provisionAccountId }
        provisionLine.side shouldBe TransactionSide.DEBIT
        provisionLine.amount shouldBe Money(BigDecimal("2000.00"), GBP)
        val expenseLine = entry.lines.first { it.accountId == expenseAccountId }
        expenseLine.side shouldBe TransactionSide.CREDIT
        provision.balance shouldBe Money(BigDecimal("3000.00"), GBP)
    }

    @Test
    fun `given a Provision with a balance, when remeasured to zero, then it fully reverses the provision`() {
        val expenseAccountId = AccountId.generate()
        val provisionAccountId = AccountId.generate()
        val provision = warrantyProvision()
        provision.remeasure(Money(BigDecimal("5000.00"), GBP), expenseAccountId, provisionAccountId, PeriodId.generate(), TODAY)

        val entry = requireNotNull(
            provision.remeasure(Money(BigDecimal.ZERO, GBP), expenseAccountId, provisionAccountId, PeriodId.generate(), TODAY)
        )

        entry.lines.first { it.accountId == provisionAccountId }.amount shouldBe Money(BigDecimal("5000.00"), GBP)
        provision.balance shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a Provision remeasured to the same amount, when remeasured again, then it returns null`() {
        val provision = warrantyProvision()
        provision.remeasure(Money(BigDecimal("5000.00"), GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)

        val result = provision.remeasure(
            Money(BigDecimal("5000.00"), GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        result shouldBe null
    }

    @Test
    fun `given a Provision with a balance, when utilized, then it debits the provision and credits Cash tagged Operating`() {
        val cashAccountId = AccountId.generate()
        val provisionAccountId = AccountId.generate()
        val provision = warrantyProvision()
        provision.remeasure(Money(BigDecimal("5000.00"), GBP), AccountId.generate(), provisionAccountId, PeriodId.generate(), TODAY)

        val entry = requireNotNull(
            provision.utilize(Money(BigDecimal("1200.00"), GBP), cashAccountId, provisionAccountId, PeriodId.generate(), TODAY)
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val provisionLine = entry.lines.first { it.accountId == provisionAccountId }
        provisionLine.side shouldBe TransactionSide.DEBIT
        provisionLine.amount shouldBe Money(BigDecimal("1200.00"), GBP)
        val cashLine = entry.lines.first { it.accountId == cashAccountId }
        cashLine.side shouldBe TransactionSide.CREDIT
        cashLine.dimensions[DimensionType.CASH_FLOW_ACTIVITY] shouldBe CashFlowActivity.OPERATING.name
        provision.balance shouldBe Money(BigDecimal("3800.00"), GBP)
    }

    @Test
    fun `given a utilization larger than the provision balance, when recorded, then it is capped at the balance`() {
        val provisionAccountId = AccountId.generate()
        val provision = warrantyProvision()
        provision.remeasure(Money(BigDecimal("5000.00"), GBP), AccountId.generate(), provisionAccountId, PeriodId.generate(), TODAY)

        val entry = requireNotNull(
            provision.utilize(Money(BigDecimal("9999.00"), GBP), AccountId.generate(), provisionAccountId, PeriodId.generate(), TODAY)
        )

        entry.lines.first { it.side == TransactionSide.DEBIT }.amount shouldBe Money(BigDecimal("5000.00"), GBP)
        provision.balance shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a Provision with a zero balance, when utilized, then it returns null`() {
        val provision = warrantyProvision()

        val result = provision.utilize(
            Money(BigDecimal("100.00"), GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        result shouldBe null
    }

    @Test
    fun `given a zero or negative utilization amount, when recorded, then it returns null`() {
        val provisionAccountId = AccountId.generate()
        val provision = warrantyProvision()
        provision.remeasure(Money(BigDecimal("5000.00"), GBP), AccountId.generate(), provisionAccountId, PeriodId.generate(), TODAY)

        val result = provision.utilize(
            Money(BigDecimal.ZERO, GBP), AccountId.generate(), provisionAccountId, PeriodId.generate(), TODAY
        )

        result shouldBe null
        provision.balance shouldBe Money(BigDecimal("5000.00"), GBP)
    }

    private fun warrantyProvision(): Provision = Provision.create(
        CompanyId.generate(), "Product warranty claims", GBP
    )
}
