package com.theprodeogroup.fish.domain.sales

import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test
import java.math.BigDecimal
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
}
