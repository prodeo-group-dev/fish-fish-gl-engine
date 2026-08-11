package com.theprodeogroup.fish.domain.purchasing

import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val USD: Currency = Currency.getInstance("USD")

class CreditorTest {

    @Test
    fun `given a new Creditor, when created, then its balance is zero`() {
        val creditor = Creditor.create(CompanyId.generate(), "Acme Supplies Ltd", GBP)

        creditor.balance shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a Creditor, when a charge is recorded, then the balance increases by that amount`() {
        val creditor = Creditor.create(CompanyId.generate(), "Acme Supplies Ltd", GBP)

        val result = creditor.recordCharge(Money(BigDecimal("150.00"), GBP))

        result.isValid shouldBe true
        creditor.balance shouldBe Money(BigDecimal("150.00"), GBP)
    }

    @Test
    fun `given a Creditor with a balance, when a payment is recorded, then the balance decreases by that amount`() {
        val creditor = Creditor.create(CompanyId.generate(), "Acme Supplies Ltd", GBP)
        creditor.recordCharge(Money(BigDecimal("150.00"), GBP))

        val result = creditor.recordPayment(Money(BigDecimal("100.00"), GBP))

        result.isValid shouldBe true
        creditor.balance shouldBe Money(BigDecimal("50.00"), GBP)
    }

    @Test
    fun `given a charge in a different currency, when recorded, then it fails`() {
        val creditor = Creditor.create(CompanyId.generate(), "Acme Supplies Ltd", GBP)

        shouldThrow<IllegalArgumentException> {
            creditor.recordCharge(Money(BigDecimal("100.00"), USD))
        }
    }

    @Test
    fun `given a zero or negative charge, when recorded, then it fails`() {
        val creditor = Creditor.create(CompanyId.generate(), "Acme Supplies Ltd", GBP)

        creditor.recordCharge(Money(BigDecimal.ZERO, GBP)).isValid shouldBe false
        creditor.recordCharge(Money(BigDecimal("-10.00"), GBP)).isValid shouldBe false
    }

    @Test
    fun `given a zero or negative payment, when recorded, then it fails`() {
        val creditor = Creditor.create(CompanyId.generate(), "Acme Supplies Ltd", GBP)

        creditor.recordPayment(Money(BigDecimal.ZERO, GBP)).isValid shouldBe false
        creditor.recordPayment(Money(BigDecimal("-10.00"), GBP)).isValid shouldBe false
    }
}
