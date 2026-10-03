package com.theprodeogroup.fish.domain.purchasing

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.common.Money
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

class SupplierTest {

    @Test
    fun `given a new Supplier, when created, then its balance is zero`() {
        val supplier = Supplier.create(CompanyId.generate(), "Acme Supplies Ltd", GBP)

        supplier.balance shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a Supplier, when a charge is recorded, then the balance increases by that amount`() {
        val supplier = Supplier.create(CompanyId.generate(), "Acme Supplies Ltd", GBP)

        val result = supplier.recordCharge(Money(BigDecimal("150.00"), GBP))

        result.isValid shouldBe true
        supplier.balance shouldBe Money(BigDecimal("150.00"), GBP)
    }

    @Test
    fun `given a Supplier with a balance, when a payment is recorded, then the balance decreases by that amount`() {
        val supplier = Supplier.create(CompanyId.generate(), "Acme Supplies Ltd", GBP)
        supplier.recordCharge(Money(BigDecimal("150.00"), GBP))

        val result = supplier.recordPayment(Money(BigDecimal("100.00"), GBP))

        result.isValid shouldBe true
        supplier.balance shouldBe Money(BigDecimal("50.00"), GBP)
    }

    @Test
    fun `given a charge in a different currency, when recorded, then it fails`() {
        val supplier = Supplier.create(CompanyId.generate(), "Acme Supplies Ltd", GBP)

        shouldThrow<IllegalArgumentException> {
            supplier.recordCharge(Money(BigDecimal("100.00"), USD))
        }
    }

    @Test
    fun `given a zero or negative charge, when recorded, then it fails`() {
        val supplier = Supplier.create(CompanyId.generate(), "Acme Supplies Ltd", GBP)

        supplier.recordCharge(Money(BigDecimal.ZERO, GBP)).isValid shouldBe false
        supplier.recordCharge(Money(BigDecimal("-10.00"), GBP)).isValid shouldBe false
    }

    @Test
    fun `given a zero or negative payment, when recorded, then it fails`() {
        val supplier = Supplier.create(CompanyId.generate(), "Acme Supplies Ltd", GBP)

        supplier.recordPayment(Money(BigDecimal.ZERO, GBP)).isValid shouldBe false
        supplier.recordPayment(Money(BigDecimal("-10.00"), GBP)).isValid shouldBe false
    }

    @Test
    fun `given a Supplier with a balance, when a payment is made, then it posts a JournalEntry debiting AP tagged with the Supplier and crediting Cash`() {
        val supplierId = SupplierId.generate()
        val supplier = Supplier.create(CompanyId.generate(), "Acme Supplies Ltd", GBP, supplierId)
        supplier.recordCharge(Money(BigDecimal("150.00"), GBP))
        val apAccountId = AccountId.generate()
        val cashAccountId = AccountId.generate()

        val entry = requireNotNull(
            supplier.makePayment(Money(BigDecimal("100.00"), GBP), cashAccountId, apAccountId, PeriodId.generate(), LocalDate.of(2026, 2, 1))
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val apLine = entry.lines.first { it.accountId == apAccountId }
        apLine.side shouldBe TransactionSide.DEBIT
        apLine.dimensions[DimensionType.VENDOR] shouldBe supplierId.value.toString()
        val cashLine = entry.lines.first { it.accountId == cashAccountId }
        cashLine.side shouldBe TransactionSide.CREDIT
        cashLine.amount shouldBe Money(BigDecimal("100.00"), GBP)
        cashLine.dimensions[DimensionType.CASH_FLOW_ACTIVITY] shouldBe CashFlowActivity.OPERATING.name
        supplier.balance shouldBe Money(BigDecimal("50.00"), GBP)
    }

    @Test
    fun `given a zero or negative payment, when made, then it fails and no JournalEntry is returned`() {
        val supplier = Supplier.create(CompanyId.generate(), "Acme Supplies Ltd", GBP)
        supplier.recordCharge(Money(BigDecimal("150.00"), GBP))

        val entry = supplier.makePayment(Money(BigDecimal.ZERO, GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), LocalDate.of(2026, 2, 1))

        entry shouldBe null
        supplier.balance shouldBe Money(BigDecimal("150.00"), GBP)
    }
}
