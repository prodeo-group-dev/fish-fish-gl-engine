package com.theprodeogroup.fish.domain.purchasing

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
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

    @Test
    fun `given a Creditor with a balance, when a payment is made, then it posts a JournalEntry debiting AP tagged with the Creditor and crediting Cash`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Acme Supplies Ltd", GBP, creditorId)
        creditor.recordCharge(Money(BigDecimal("150.00"), GBP))
        val apAccountId = AccountId.generate()
        val cashAccountId = AccountId.generate()

        val entry = requireNotNull(
            creditor.makePayment(Money(BigDecimal("100.00"), GBP), cashAccountId, apAccountId, PeriodId.generate(), LocalDate.of(2026, 2, 1))
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val apLine = entry.lines.first { it.accountId == apAccountId }
        apLine.side shouldBe TransactionSide.DEBIT
        apLine.dimensions[DimensionType.VENDOR] shouldBe creditorId.value.toString()
        val cashLine = entry.lines.first { it.accountId == cashAccountId }
        cashLine.side shouldBe TransactionSide.CREDIT
        cashLine.amount shouldBe Money(BigDecimal("100.00"), GBP)
        creditor.balance shouldBe Money(BigDecimal("50.00"), GBP)
    }

    @Test
    fun `given a zero or negative payment, when made, then it fails and no JournalEntry is returned`() {
        val creditor = Creditor.create(CompanyId.generate(), "Acme Supplies Ltd", GBP)
        creditor.recordCharge(Money(BigDecimal("150.00"), GBP))

        val entry = creditor.makePayment(Money(BigDecimal.ZERO, GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), LocalDate.of(2026, 2, 1))

        entry shouldBe null
        creditor.balance shouldBe Money(BigDecimal("150.00"), GBP)
    }
}
