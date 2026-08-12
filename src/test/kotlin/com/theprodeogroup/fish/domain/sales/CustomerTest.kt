package com.theprodeogroup.fish.domain.sales

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AgingBucketLabel
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val USD: Currency = Currency.getInstance("USD")

class CustomerTest {

    @Test
    fun `given a new Customer, when created, then its balance is zero`() {
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP)

        customer.balance shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a Customer, when a sale is recorded, then the balance increases by that amount`() {
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP)

        val result = customer.recordSale(Money(BigDecimal("200.00"), GBP))

        result.isValid shouldBe true
        customer.balance shouldBe Money(BigDecimal("200.00"), GBP)
    }

    @Test
    fun `given a Customer with a balance, when a receipt is recorded, then the balance decreases by that amount`() {
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP)
        customer.recordSale(Money(BigDecimal("200.00"), GBP))

        val result = customer.recordReceipt(Money(BigDecimal("150.00"), GBP))

        result.isValid shouldBe true
        customer.balance shouldBe Money(BigDecimal("50.00"), GBP)
    }

    @Test
    fun `given a sale in a different currency, when recorded, then it fails`() {
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP)

        shouldThrow<IllegalArgumentException> {
            customer.recordSale(Money(BigDecimal("100.00"), USD))
        }
    }

    @Test
    fun `given a zero or negative sale, when recorded, then it fails`() {
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP)

        customer.recordSale(Money(BigDecimal.ZERO, GBP)).isValid shouldBe false
        customer.recordSale(Money(BigDecimal("-10.00"), GBP)).isValid shouldBe false
    }

    @Test
    fun `given a zero or negative receipt, when recorded, then it fails`() {
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP)

        customer.recordReceipt(Money(BigDecimal.ZERO, GBP)).isValid shouldBe false
        customer.recordReceipt(Money(BigDecimal("-10.00"), GBP)).isValid shouldBe false
    }

    @Test
    fun `given a Customer with a balance, when a payment is received, then it posts a JournalEntry debiting Cash and crediting AR tagged with the Customer`() {
        val customerId = CustomerId.generate()
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP, customerId)
        customer.recordSale(Money(BigDecimal("200.00"), GBP))
        val cashAccountId = AccountId.generate()
        val arAccountId = AccountId.generate()

        val entry = requireNotNull(
            customer.receivePayment(Money(BigDecimal("150.00"), GBP), cashAccountId, arAccountId, PeriodId.generate(), LocalDate.of(2026, 2, 1))
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val cashLine = entry.lines.first { it.accountId == cashAccountId }
        cashLine.side shouldBe TransactionSide.DEBIT
        cashLine.amount shouldBe Money(BigDecimal("150.00"), GBP)
        val arLine = entry.lines.first { it.accountId == arAccountId }
        arLine.side shouldBe TransactionSide.CREDIT
        arLine.dimensions[DimensionType.CUSTOMER] shouldBe customerId.value.toString()
        customer.balance shouldBe Money(BigDecimal("50.00"), GBP)
    }

    @Test
    fun `given a zero or negative payment, when received, then it fails and no JournalEntry is returned`() {
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP)
        customer.recordSale(Money(BigDecimal("200.00"), GBP))

        val entry = customer.receivePayment(Money(BigDecimal.ZERO, GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), LocalDate.of(2026, 2, 1))

        entry shouldBe null
        customer.balance shouldBe Money(BigDecimal("200.00"), GBP)
    }

    @Test
    fun `given a Customer with an outstanding balance, when Expected Credit Loss is assessed, then it posts a charge for the target allowance`() {
        val customerId = CustomerId.generate()
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP, customerId)
        customer.recordSale(Money(BigDecimal("1000.00"), GBP))
        val aging = flatAging(customerId, AgingBucketLabel.CURRENT, Money(BigDecimal("1000.00"), GBP))
        val expenseAccountId = AccountId.generate()
        val allowanceAccountId = AccountId.generate()

        val entry = requireNotNull(
            customer.assessExpectedCreditLoss(
                aging, mapOf(AgingBucketLabel.CURRENT to BigDecimal("0.02")),
                expenseAccountId, allowanceAccountId, PeriodId.generate(), LocalDate.of(2026, 3, 1)
            )
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val expenseLine = entry.lines.first { it.accountId == expenseAccountId }
        expenseLine.side shouldBe TransactionSide.DEBIT
        expenseLine.amount shouldBe Money(BigDecimal("20.00"), GBP)
        customer.allowanceForExpectedCreditLoss shouldBe Money(BigDecimal("20.00"), GBP)
        customer.netReceivable shouldBe Money(BigDecimal("980.00"), GBP)
    }

    @Test
    fun `given a target allowance exceeding the balance, when assessed, then it is capped at the balance`() {
        val customerId = CustomerId.generate()
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP, customerId)
        customer.recordSale(Money(BigDecimal("100.00"), GBP))
        val aging = flatAging(customerId, AgingBucketLabel.OVER_90, Money(BigDecimal("100.00"), GBP))

        customer.assessExpectedCreditLoss(
            aging, mapOf(AgingBucketLabel.OVER_90 to BigDecimal("1.50")),
            AccountId.generate(), AccountId.generate(), PeriodId.generate(), LocalDate.of(2026, 3, 1)
        )

        customer.allowanceForExpectedCreditLoss shouldBe Money(BigDecimal("100.00"), GBP)
    }

    @Test
    fun `given a lower target on a later assessment, when assessed, then it posts a reversal`() {
        val customerId = CustomerId.generate()
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP, customerId)
        customer.recordSale(Money(BigDecimal("1000.00"), GBP))
        val highAging = flatAging(customerId, AgingBucketLabel.OVER_90, Money(BigDecimal("1000.00"), GBP))
        customer.assessExpectedCreditLoss(
            highAging, mapOf(AgingBucketLabel.OVER_90 to BigDecimal("0.50")),
            AccountId.generate(), AccountId.generate(), PeriodId.generate(), LocalDate.of(2026, 3, 1)
        )
        val lowAging = flatAging(customerId, AgingBucketLabel.CURRENT, Money(BigDecimal("1000.00"), GBP))
        val expenseAccountId = AccountId.generate()
        val allowanceAccountId = AccountId.generate()

        val entry = requireNotNull(
            customer.assessExpectedCreditLoss(
                lowAging, mapOf(AgingBucketLabel.CURRENT to BigDecimal("0.02")),
                expenseAccountId, allowanceAccountId, PeriodId.generate(), LocalDate.of(2026, 4, 1)
            )
        )

        val allowanceLine = entry.lines.first { it.accountId == allowanceAccountId }
        allowanceLine.side shouldBe TransactionSide.DEBIT
        allowanceLine.amount shouldBe Money(BigDecimal("480.00"), GBP)
        customer.allowanceForExpectedCreditLoss shouldBe Money(BigDecimal("20.00"), GBP)
    }

    @Test
    fun `given no change in target allowance, when assessed again, then no JournalEntry is posted`() {
        val customerId = CustomerId.generate()
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP, customerId)
        customer.recordSale(Money(BigDecimal("1000.00"), GBP))
        val aging = flatAging(customerId, AgingBucketLabel.CURRENT, Money(BigDecimal("1000.00"), GBP))
        val rates = mapOf(AgingBucketLabel.CURRENT to BigDecimal("0.02"))
        customer.assessExpectedCreditLoss(aging, rates, AccountId.generate(), AccountId.generate(), PeriodId.generate(), LocalDate.of(2026, 3, 1))

        val entry = customer.assessExpectedCreditLoss(
            aging, rates, AccountId.generate(), AccountId.generate(), PeriodId.generate(), LocalDate.of(2026, 4, 1)
        )

        entry shouldBe null
    }

    @Test
    fun `given an aging report for a different Customer, when assessed, then it fails`() {
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP)
        customer.recordSale(Money(BigDecimal("1000.00"), GBP))
        val mismatchedAging = flatAging(CustomerId.generate(), AgingBucketLabel.CURRENT, Money(BigDecimal("1000.00"), GBP))

        val entry = customer.assessExpectedCreditLoss(
            mismatchedAging, mapOf(AgingBucketLabel.CURRENT to BigDecimal("0.02")),
            AccountId.generate(), AccountId.generate(), PeriodId.generate(), LocalDate.of(2026, 3, 1)
        )

        entry shouldBe null
    }

    private fun flatAging(customerId: CustomerId, label: AgingBucketLabel, amount: Money): AccountsReceivableAging {
        val arAccountId = AccountId.generate()
        val entry = JournalEntry.create(
            PeriodId.generate(), LocalDate.of(2026, 1, 1),
            listOf(
                JournalLine(
                    arAccountId, amount, TransactionSide.DEBIT,
                    mapOf(DimensionType.CUSTOMER to customerId.value.toString())
                ),
                JournalLine(AccountId.generate(), amount, TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        entry.post()
        val asOfDate = when (label) {
            AgingBucketLabel.CURRENT -> LocalDate.of(2026, 1, 1)
            AgingBucketLabel.DAYS_31_TO_60 -> LocalDate.of(2026, 1, 1).plusDays(45)
            AgingBucketLabel.DAYS_61_TO_90 -> LocalDate.of(2026, 1, 1).plusDays(75)
            AgingBucketLabel.OVER_90 -> LocalDate.of(2026, 1, 1).plusDays(120)
        }
        return AccountsReceivableAging.of(customerId, arAccountId, listOf(entry), asOfDate, GBP)
    }
}
