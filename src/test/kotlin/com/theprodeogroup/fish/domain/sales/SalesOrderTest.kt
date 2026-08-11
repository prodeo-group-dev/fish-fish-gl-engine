package com.theprodeogroup.fish.domain.sales

import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 1, 15)

class SalesOrderTest {

    @Test
    fun `given lines, when a SalesOrder is created, then it starts Draft with no lines delivered and the correct total`() {
        val order = readyOrder()

        order.status shouldBe SalesOrderStatus.DRAFT
        order.totalAmount shouldBe Money(BigDecimal("300.00"), GBP)
    }

    @Test
    fun `given no lines, when create is called, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            SalesOrder.create(CompanyId.generate(), CustomerId.generate(), TODAY, emptyList())
        }
    }

    @Test
    fun `given a Draft order, when a line is delivered, then it returns a JournalEntry debiting AR and crediting that line's revenue account`() {
        val customerId = CustomerId.generate()
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP, customerId)
        val arAccountId = AccountId.generate()
        val order = readyOrder(customerId = customerId)

        val entry = requireNotNull(order.deliverLine(0, customer, arAccountId, PeriodId.generate()))

        entry.lines shouldHaveSize 2
        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val arLine = entry.lines.first { it.accountId == arAccountId }
        arLine.side shouldBe TransactionSide.DEBIT
        arLine.amount shouldBe Money(BigDecimal("200.00"), GBP)
        val revenueLine = entry.lines.first { it.accountId == order.lines[0].accountId }
        revenueLine.side shouldBe TransactionSide.CREDIT
    }

    @Test
    fun `given a line delivered, then the Customer's balance increases by that line's amount`() {
        val customerId = CustomerId.generate()
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP, customerId)
        val order = readyOrder(customerId = customerId)

        order.deliverLine(0, customer, AccountId.generate(), PeriodId.generate())

        customer.balance shouldBe Money(BigDecimal("200.00"), GBP)
    }

    @Test
    fun `given one of two lines delivered, then the order status is PartiallyDelivered`() {
        val customerId = CustomerId.generate()
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP, customerId)
        val order = readyOrder(customerId = customerId)

        order.deliverLine(0, customer, AccountId.generate(), PeriodId.generate())

        order.status shouldBe SalesOrderStatus.PARTIALLY_DELIVERED
    }

    @Test
    fun `given every line delivered, then the order status is Fulfilled`() {
        val customerId = CustomerId.generate()
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP, customerId)
        val order = readyOrder(customerId = customerId)

        order.deliverLine(0, customer, AccountId.generate(), PeriodId.generate())
        order.deliverLine(1, customer, AccountId.generate(), PeriodId.generate())

        order.status shouldBe SalesOrderStatus.FULFILLED
        customer.balance shouldBe Money(BigDecimal("300.00"), GBP)
    }

    @Test
    fun `given a line already delivered, when delivered again, then it fails and the Customer is not double-charged`() {
        val customerId = CustomerId.generate()
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP, customerId)
        val order = readyOrder(customerId = customerId)
        order.deliverLine(0, customer, AccountId.generate(), PeriodId.generate())

        val secondAttempt = order.deliverLine(0, customer, AccountId.generate(), PeriodId.generate())

        secondAttempt shouldBe null
        customer.balance shouldBe Money(BigDecimal("200.00"), GBP)
    }

    @Test
    fun `given an invalid line index, when delivered, then it fails`() {
        val customerId = CustomerId.generate()
        val customer = Customer.create(CompanyId.generate(), "Beta Retail Ltd", GBP, customerId)
        val order = readyOrder(customerId = customerId)

        order.deliverLine(99, customer, AccountId.generate(), PeriodId.generate()) shouldBe null
    }

    @Test
    fun `given a Customer that does not match the order's customerId, when delivered, then it fails`() {
        val order = readyOrder()
        val wrongCustomer = Customer.create(CompanyId.generate(), "Someone Else Ltd", GBP)

        order.deliverLine(0, wrongCustomer, AccountId.generate(), PeriodId.generate()) shouldBe null
    }

    private fun readyOrder(customerId: CustomerId = CustomerId.generate()): SalesOrder {
        val lines = listOf(
            SalesOrderLine("Consulting - phase 1", AccountId.generate(), Money(BigDecimal("200.00"), GBP)),
            SalesOrderLine("Consulting - phase 2", AccountId.generate(), Money(BigDecimal("100.00"), GBP))
        )
        return SalesOrder.create(CompanyId.generate(), customerId, TODAY, lines)
    }
}
