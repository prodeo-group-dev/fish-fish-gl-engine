package com.theprodeogroup.fish.domain.purchasing

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

class PurchaseOrderTest {

    @Test
    fun `given lines, when a PurchaseOrder is created, then it starts Draft with the correct total`() {
        val order = readyOrder()

        order.status shouldBe PurchaseOrderStatus.DRAFT
        order.totalAmount shouldBe Money(BigDecimal("300.00"), GBP)
    }

    @Test
    fun `given no lines, when create is called, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            PurchaseOrder.create(CompanyId.generate(), CreditorId.generate(), TODAY, emptyList())
        }
    }

    @Test
    fun `given a Draft order, when sent, then it returns a balanced JournalEntry debiting each line and crediting the AP control account`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Acme Supplies Ltd", GBP, creditorId)
        val apAccountId = AccountId.generate()
        val order = readyOrder(creditorId = creditorId)

        val entry = requireNotNull(order.send(creditor, apAccountId, PeriodId.generate()))

        entry.lines shouldHaveSize 3
        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val apLine = entry.lines.first { it.accountId == apAccountId }
        apLine.side shouldBe TransactionSide.CREDIT
        apLine.amount shouldBe Money(BigDecimal("300.00"), GBP)
    }

    @Test
    fun `given a Draft order, when sent, then the Creditor's balance increases by the order total`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Acme Supplies Ltd", GBP, creditorId)
        val order = readyOrder(creditorId = creditorId)

        order.send(creditor, AccountId.generate(), PeriodId.generate())

        creditor.balance shouldBe Money(BigDecimal("300.00"), GBP)
    }

    @Test
    fun `given a Draft order, when sent, then its status becomes Sent`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Acme Supplies Ltd", GBP, creditorId)
        val order = readyOrder(creditorId = creditorId)

        order.send(creditor, AccountId.generate(), PeriodId.generate())

        order.status shouldBe PurchaseOrderStatus.SENT
    }

    @Test
    fun `given an order already sent, when sent again, then it fails and the Creditor is not double-charged`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Acme Supplies Ltd", GBP, creditorId)
        val order = readyOrder(creditorId = creditorId)
        order.send(creditor, AccountId.generate(), PeriodId.generate())

        val secondAttempt = order.send(creditor, AccountId.generate(), PeriodId.generate())

        secondAttempt shouldBe null
        creditor.balance shouldBe Money(BigDecimal("300.00"), GBP)
    }

    @Test
    fun `given a Creditor that does not match the order's creditorId, when sent, then it fails`() {
        val order = readyOrder()
        val wrongCreditor = Creditor.create(CompanyId.generate(), "Someone Else Ltd", GBP)

        order.send(wrongCreditor, AccountId.generate(), PeriodId.generate()) shouldBe null
    }

    private fun readyOrder(creditorId: CreditorId = CreditorId.generate()): PurchaseOrder {
        val lines = listOf(
            PurchaseOrderLine("Office supplies", AccountId.generate(), Money(BigDecimal("200.00"), GBP)),
            PurchaseOrderLine("Delivery charge", AccountId.generate(), Money(BigDecimal("100.00"), GBP))
        )
        return PurchaseOrder.create(CompanyId.generate(), creditorId, TODAY, lines)
    }
}
