package com.theprodeogroup.fish.domain.purchasing

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.LineItemType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.inventory.StockItem
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 21)

/**
 * The GRNI reference material's own two follow-up cases, added 2026-08-21
 * alongside `PurchaseOrderGoodsInTransitTest`: invoice-vs-accrual
 * variance (`matchInvoice`'s new `invoicedGoodsAmount`/`varianceAccountId`
 * params) and goods returned before any invoice arrives
 * (`returnGoodsBeforeInvoice`).
 */
class PurchaseOrderGrniVarianceAndReturnsTest {

    private fun goodsLine(
        amount: String = "500.00",
        quantity: String = "100",
        inventoryAccountId: AccountId = AccountId.generate(),
        stockItem: StockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
    ) = PurchaseOrderLine(
        "Refined White Sugar, 100 MT", inventoryAccountId, Money(BigDecimal(amount), GBP),
        LineItemType.GOODS, BigDecimal(quantity), stockItem.id
    )

    private fun serviceLine(amount: String = "50.00", accountId: AccountId = AccountId.generate()) =
        PurchaseOrderLine("Freight", accountId, Money(BigDecimal(amount), GBP), LineItemType.SERVICE)

    private fun grniReceivedOrder(
        creditorId: CreditorId,
        stockItem: StockItem,
        inventoryAccountId: AccountId,
        grniAccountId: AccountId,
        extraLines: List<PurchaseOrderLine> = emptyList()
    ): PurchaseOrder {
        val order = PurchaseOrder.create(
            CompanyId.generate(), creditorId, TODAY,
            listOf(goodsLine(inventoryAccountId = inventoryAccountId, stockItem = stockItem)) + extraLines,
            DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT
        )
        order.receiveGoodsBeforeInvoice(listOf(stockItem), grniAccountId, PeriodId.generate())
        return order
    }

    // --- matchInvoice() variance handling ---

    @Test
    fun `given no invoicedGoodsAmount supplied, when the invoice is matched, then it behaves exactly as before - trusting the accrual`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Brazilian Sugar Exports Ltda", GBP, creditorId)
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val inventoryAccountId = AccountId.generate()
        val order = grniReceivedOrder(creditorId, stockItem, inventoryAccountId, AccountId.generate())

        val entry = requireNotNull(order.matchInvoice(creditor, AccountId.generate(), PeriodId.generate()))

        entry.lines shouldHaveSize 2
        creditor.balance shouldBe Money(BigDecimal("500.00"), GBP)
    }

    @Test
    fun `given the invoice is higher than the GRNI accrual, when matched with a variance account, then the difference debits the variance account and AP reflects the actual invoice`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Brazilian Sugar Exports Ltda", GBP, creditorId)
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val grniAccountId = AccountId.generate()
        val order = grniReceivedOrder(creditorId, stockItem, AccountId.generate(), grniAccountId)
        val apAccountId = AccountId.generate()
        val varianceAccountId = AccountId.generate()

        val entry = requireNotNull(
            order.matchInvoice(
                creditor, apAccountId, PeriodId.generate(),
                invoicedGoodsAmount = Money(BigDecimal("550.00"), GBP), varianceAccountId = varianceAccountId
            )
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val grniLine = entry.lines.first { it.accountId == grniAccountId }
        grniLine.side shouldBe TransactionSide.DEBIT
        grniLine.amount shouldBe Money(BigDecimal("500.00"), GBP)
        val varianceLine = entry.lines.first { it.accountId == varianceAccountId }
        varianceLine.side shouldBe TransactionSide.DEBIT
        varianceLine.amount shouldBe Money(BigDecimal("50.00"), GBP)
        val apLine = entry.lines.first { it.accountId == apAccountId }
        apLine.side shouldBe TransactionSide.CREDIT
        apLine.amount shouldBe Money(BigDecimal("550.00"), GBP)
        creditor.balance shouldBe Money(BigDecimal("550.00"), GBP)
    }

    @Test
    fun `given the invoice is lower than the GRNI accrual, when matched with a variance account, then the difference credits the variance account and AP reflects the actual invoice`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Brazilian Sugar Exports Ltda", GBP, creditorId)
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val grniAccountId = AccountId.generate()
        val order = grniReceivedOrder(creditorId, stockItem, AccountId.generate(), grniAccountId)
        val varianceAccountId = AccountId.generate()

        val entry = requireNotNull(
            order.matchInvoice(
                creditor, AccountId.generate(), PeriodId.generate(),
                invoicedGoodsAmount = Money(BigDecimal("450.00"), GBP), varianceAccountId = varianceAccountId
            )
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val varianceLine = entry.lines.first { it.accountId == varianceAccountId }
        varianceLine.side shouldBe TransactionSide.CREDIT
        varianceLine.amount shouldBe Money(BigDecimal("50.00"), GBP)
        creditor.balance shouldBe Money(BigDecimal("450.00"), GBP)
    }

    @Test
    fun `given a variance with no variance account supplied, when matched, then it fails`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Brazilian Sugar Exports Ltda", GBP, creditorId)
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val order = grniReceivedOrder(creditorId, stockItem, AccountId.generate(), AccountId.generate())

        val result = order.matchInvoice(
            creditor, AccountId.generate(), PeriodId.generate(), invoicedGoodsAmount = Money(BigDecimal("550.00"), GBP)
        )

        result shouldBe null
        order.status shouldBe PurchaseOrderStatus.GOODS_RECEIVED_PENDING_INVOICE
        creditor.balance shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given an invoiced amount in a different currency, when matched, then it fails`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Brazilian Sugar Exports Ltda", GBP, creditorId)
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val order = grniReceivedOrder(creditorId, stockItem, AccountId.generate(), AccountId.generate())

        val result = order.matchInvoice(
            creditor, AccountId.generate(), PeriodId.generate(),
            invoicedGoodsAmount = Money(BigDecimal("550.00"), Currency.getInstance("USD")), varianceAccountId = AccountId.generate()
        )

        result shouldBe null
    }

    @Test
    fun `given a mixed GOODS and SERVICE order with a GOODS invoice variance, when matched, then SERVICE lines and the variance both post correctly`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Brazilian Sugar Exports Ltda", GBP, creditorId)
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val serviceAccountId = AccountId.generate()
        val grniAccountId = AccountId.generate()
        val order = grniReceivedOrder(
            creditorId, stockItem, AccountId.generate(), grniAccountId,
            extraLines = listOf(serviceLine(accountId = serviceAccountId))
        )
        val apAccountId = AccountId.generate()
        val varianceAccountId = AccountId.generate()

        val entry = requireNotNull(
            order.matchInvoice(
                creditor, apAccountId, PeriodId.generate(),
                invoicedGoodsAmount = Money(BigDecimal("550.00"), GBP), varianceAccountId = varianceAccountId
            )
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val serviceEntryLine = entry.lines.first { it.accountId == serviceAccountId }
        serviceEntryLine.amount shouldBe Money(BigDecimal("50.00"), GBP)
        val apLine = entry.lines.first { it.accountId == apAccountId }
        apLine.amount shouldBe Money(BigDecimal("600.00"), GBP)
        creditor.balance shouldBe Money(BigDecimal("600.00"), GBP)
    }

    // --- returnGoodsBeforeInvoice() ---

    @Test
    fun `given goods received before invoice, when returned before the invoice arrives, then it debits GRNI, credits Inventory, reverses the stock, and returns to Draft`() {
        val creditorId = CreditorId.generate()
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val inventoryAccountId = AccountId.generate()
        val grniAccountId = AccountId.generate()
        val order = grniReceivedOrder(creditorId, stockItem, inventoryAccountId, grniAccountId)

        val entry = requireNotNull(order.returnGoodsBeforeInvoice(listOf(stockItem), PeriodId.generate()))

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        entry.lines shouldHaveSize 2
        val grniLine = entry.lines.first { it.accountId == grniAccountId }
        grniLine.side shouldBe TransactionSide.DEBIT
        grniLine.amount shouldBe Money(BigDecimal("500.00"), GBP)
        val inventoryLine = entry.lines.first { it.accountId == inventoryAccountId }
        inventoryLine.side shouldBe TransactionSide.CREDIT
        inventoryLine.amount shouldBe Money(BigDecimal("500.00"), GBP)
        stockItem.quantityOnHand shouldBe BigDecimal.ZERO
        order.status shouldBe PurchaseOrderStatus.DRAFT
        order.grniAccountId shouldBe null
    }

    @Test
    fun `given a returned order back in Draft, when goods are received again, then it can re-enter the GRNI path with no special casing`() {
        val creditorId = CreditorId.generate()
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val order = grniReceivedOrder(creditorId, stockItem, AccountId.generate(), AccountId.generate())
        order.returnGoodsBeforeInvoice(listOf(stockItem), PeriodId.generate())

        val newGrniAccountId = AccountId.generate()
        val entry = requireNotNull(order.receiveGoodsBeforeInvoice(listOf(stockItem), newGrniAccountId, PeriodId.generate()))

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        order.status shouldBe PurchaseOrderStatus.GOODS_RECEIVED_PENDING_INVOICE
        stockItem.quantityOnHand shouldBe BigDecimal("100")
    }

    @Test
    fun `given an order not in GoodsReceivedPendingInvoice, when goods are returned before invoice, then it fails`() {
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val order = PurchaseOrder.create(
            CompanyId.generate(), CreditorId.generate(), TODAY, listOf(goodsLine(stockItem = stockItem)), DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT
        )

        val result = order.returnGoodsBeforeInvoice(listOf(stockItem), PeriodId.generate())

        result shouldBe null
    }

    @Test
    fun `given a missing StockItem, when goods are returned before invoice, then it fails`() {
        val creditorId = CreditorId.generate()
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val order = grniReceivedOrder(creditorId, stockItem, AccountId.generate(), AccountId.generate())

        val result = order.returnGoodsBeforeInvoice(emptyList(), PeriodId.generate())

        result shouldBe null
        order.status shouldBe PurchaseOrderStatus.GOODS_RECEIVED_PENDING_INVOICE
    }
}
