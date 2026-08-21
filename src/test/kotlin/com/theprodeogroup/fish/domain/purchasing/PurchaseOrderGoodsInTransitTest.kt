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
 * `docs/IFRS_GL_Posting_Matrix.md` Part A's Goods-in-Transit/GRNI paths
 * under `DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT` - `PurchaseOrderTest`
 * already covers `DeliveryTerms.CONTROL_TRANSFERS_AT_SHIPMENT` (the
 * default, original, unchanged `send()` behaviour) exhaustively, so
 * this file only covers the new paths.
 */
class PurchaseOrderGoodsInTransitTest {

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

    // --- send(): invoice-first path, CONTROL_TRANSFERS_AT_RECEIPT ---

    @Test
    fun `given CONTROL_TRANSFERS_AT_RECEIPT and a GOODS line, when sent with a Goods-in-Transit account, then it debits Goods-in-Transit not Inventory and does not yet receive stock`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Brazilian Sugar Exports Ltda", GBP, creditorId)
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val inventoryAccountId = AccountId.generate()
        val line = goodsLine(inventoryAccountId = inventoryAccountId, stockItem = stockItem)
        val order = PurchaseOrder.create(
            CompanyId.generate(), creditorId, TODAY, listOf(line), DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT
        )
        val apAccountId = AccountId.generate()
        val gitAccountId = AccountId.generate()

        val entry = requireNotNull(
            order.send(creditor, apAccountId, PeriodId.generate(), listOf(stockItem), goodsInTransitAccountId = gitAccountId)
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        entry.lines shouldHaveSize 2
        val gitLine = entry.lines.first { it.accountId == gitAccountId }
        gitLine.side shouldBe TransactionSide.DEBIT
        gitLine.amount shouldBe Money(BigDecimal("500.00"), GBP)
        entry.lines.none { it.accountId == inventoryAccountId } shouldBe true
        stockItem.quantityOnHand shouldBe BigDecimal.ZERO
        order.status shouldBe PurchaseOrderStatus.SENT
        order.goodsInTransitAccountId shouldBe gitAccountId
    }

    @Test
    fun `given CONTROL_TRANSFERS_AT_RECEIPT and a GOODS line, when sent without a Goods-in-Transit account, then it fails`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Brazilian Sugar Exports Ltda", GBP, creditorId)
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val order = PurchaseOrder.create(
            CompanyId.generate(), creditorId, TODAY, listOf(goodsLine(stockItem = stockItem)), DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT
        )

        val result = order.send(creditor, AccountId.generate(), PeriodId.generate(), listOf(stockItem))

        result shouldBe null
        order.status shouldBe PurchaseOrderStatus.DRAFT
    }

    @Test
    fun `given CONTROL_TRANSFERS_AT_RECEIPT with only SERVICE lines, when sent, then no Goods-in-Transit account is required and it posts as normal`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Freight Forwarder Ltd", GBP, creditorId)
        val serviceAccountId = AccountId.generate()
        val order = PurchaseOrder.create(
            CompanyId.generate(), creditorId, TODAY, listOf(serviceLine(accountId = serviceAccountId)), DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT
        )

        val entry = requireNotNull(order.send(creditor, AccountId.generate(), PeriodId.generate()))

        val serviceEntryLine = entry.lines.first { it.accountId == serviceAccountId }
        serviceEntryLine.side shouldBe TransactionSide.DEBIT
        serviceEntryLine.amount shouldBe Money(BigDecimal("50.00"), GBP)
        order.status shouldBe PurchaseOrderStatus.SENT
    }

    @Test
    fun `given a GOODS line missing its StockItem under CONTROL_TRANSFERS_AT_SHIPMENT, when sent, then it still fails as before`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Acme Supplies Ltd", GBP, creditorId)
        val order = PurchaseOrder.create(
            CompanyId.generate(), creditorId, TODAY, listOf(goodsLine()), DeliveryTerms.CONTROL_TRANSFERS_AT_SHIPMENT
        )

        val result = order.send(creditor, AccountId.generate(), PeriodId.generate())

        result shouldBe null
    }

    // --- receiveGoods(): invoice-first path's second step ---

    @Test
    fun `given a SENT order under CONTROL_TRANSFERS_AT_RECEIPT, when goods are received, then it debits Inventory, credits Goods-in-Transit, receives stock, and becomes Received`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Brazilian Sugar Exports Ltda", GBP, creditorId)
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val inventoryAccountId = AccountId.generate()
        val line = goodsLine(inventoryAccountId = inventoryAccountId, stockItem = stockItem)
        val order = PurchaseOrder.create(
            CompanyId.generate(), creditorId, TODAY, listOf(line), DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT
        )
        val gitAccountId = AccountId.generate()
        order.send(creditor, AccountId.generate(), PeriodId.generate(), listOf(stockItem), goodsInTransitAccountId = gitAccountId)

        val entry = requireNotNull(order.receiveGoods(listOf(stockItem), PeriodId.generate()))

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        entry.lines shouldHaveSize 2
        val inventoryLine = entry.lines.first { it.accountId == inventoryAccountId }
        inventoryLine.side shouldBe TransactionSide.DEBIT
        inventoryLine.amount shouldBe Money(BigDecimal("500.00"), GBP)
        val gitLine = entry.lines.first { it.accountId == gitAccountId }
        gitLine.side shouldBe TransactionSide.CREDIT
        gitLine.amount shouldBe Money(BigDecimal("500.00"), GBP)
        stockItem.quantityOnHand shouldBe BigDecimal("100")
        stockItem.unitCost shouldBe Money(BigDecimal("5.00"), GBP)
        order.status shouldBe PurchaseOrderStatus.RECEIVED
    }

    @Test
    fun `given an order not yet Sent, when goods are received, then it fails`() {
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val order = PurchaseOrder.create(
            CompanyId.generate(), CreditorId.generate(), TODAY, listOf(goodsLine(stockItem = stockItem)), DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT
        )

        val result = order.receiveGoods(listOf(stockItem), PeriodId.generate())

        result shouldBe null
    }

    @Test
    fun `given an order under CONTROL_TRANSFERS_AT_SHIPMENT, when goods are received, then it fails - nothing to reclassify`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Acme Supplies Ltd", GBP, creditorId)
        val stockItem = StockItem.create(CompanyId.generate(), "Widget", GBP)
        val order = PurchaseOrder.create(
            CompanyId.generate(), creditorId, TODAY, listOf(goodsLine(stockItem = stockItem)), DeliveryTerms.CONTROL_TRANSFERS_AT_SHIPMENT
        )
        order.send(creditor, AccountId.generate(), PeriodId.generate(), listOf(stockItem))

        val result = order.receiveGoods(listOf(stockItem), PeriodId.generate())

        result shouldBe null
    }

    @Test
    fun `given goods received with a missing StockItem, when receiveGoods is called, then it fails`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Brazilian Sugar Exports Ltda", GBP, creditorId)
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val order = PurchaseOrder.create(
            CompanyId.generate(), creditorId, TODAY, listOf(goodsLine(stockItem = stockItem)), DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT
        )
        order.send(creditor, AccountId.generate(), PeriodId.generate(), listOf(stockItem), goodsInTransitAccountId = AccountId.generate())

        val result = order.receiveGoods(emptyList(), PeriodId.generate())

        result shouldBe null
    }

    // --- receiveGoodsBeforeInvoice() / matchInvoice(): GRNI path ---

    @Test
    fun `given a Draft order under CONTROL_TRANSFERS_AT_RECEIPT, when goods are received before the invoice, then it debits Inventory, credits GRNI, receives stock, and becomes GoodsReceivedPendingInvoice`() {
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val inventoryAccountId = AccountId.generate()
        val line = goodsLine(inventoryAccountId = inventoryAccountId, stockItem = stockItem)
        val order = PurchaseOrder.create(
            CompanyId.generate(), CreditorId.generate(), TODAY, listOf(line), DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT
        )
        val grniAccountId = AccountId.generate()

        val entry = requireNotNull(order.receiveGoodsBeforeInvoice(listOf(stockItem), grniAccountId, PeriodId.generate()))

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val inventoryLine = entry.lines.first { it.accountId == inventoryAccountId }
        inventoryLine.side shouldBe TransactionSide.DEBIT
        inventoryLine.amount shouldBe Money(BigDecimal("500.00"), GBP)
        val grniLine = entry.lines.first { it.accountId == grniAccountId }
        grniLine.side shouldBe TransactionSide.CREDIT
        grniLine.amount shouldBe Money(BigDecimal("500.00"), GBP)
        stockItem.quantityOnHand shouldBe BigDecimal("100")
        order.status shouldBe PurchaseOrderStatus.GOODS_RECEIVED_PENDING_INVOICE
        order.grniAccountId shouldBe grniAccountId
    }

    @Test
    fun `given CONTROL_TRANSFERS_AT_SHIPMENT, when goods are received before invoice, then it fails - GRNI does not apply`() {
        val stockItem = StockItem.create(CompanyId.generate(), "Widget", GBP)
        val order = PurchaseOrder.create(
            CompanyId.generate(), CreditorId.generate(), TODAY, listOf(goodsLine(stockItem = stockItem)), DeliveryTerms.CONTROL_TRANSFERS_AT_SHIPMENT
        )

        val result = order.receiveGoodsBeforeInvoice(listOf(stockItem), AccountId.generate(), PeriodId.generate())

        result shouldBe null
    }

    @Test
    fun `given an order with no GOODS lines, when goods are received before invoice, then it fails`() {
        val order = PurchaseOrder.create(
            CompanyId.generate(), CreditorId.generate(), TODAY, listOf(serviceLine()), DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT
        )

        val result = order.receiveGoodsBeforeInvoice(emptyList(), AccountId.generate(), PeriodId.generate())

        result shouldBe null
    }

    @Test
    fun `given goods already received before invoice, when the invoice is matched, then it debits GRNI, credits AP, charges the Creditor, and becomes Received`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Brazilian Sugar Exports Ltda", GBP, creditorId)
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val order = PurchaseOrder.create(
            CompanyId.generate(), creditorId, TODAY, listOf(goodsLine(stockItem = stockItem)), DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT
        )
        val grniAccountId = AccountId.generate()
        order.receiveGoodsBeforeInvoice(listOf(stockItem), grniAccountId, PeriodId.generate())
        val apAccountId = AccountId.generate()

        val entry = requireNotNull(order.matchInvoice(creditor, apAccountId, PeriodId.generate()))

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        entry.lines shouldHaveSize 2
        val grniLine = entry.lines.first { it.accountId == grniAccountId }
        grniLine.side shouldBe TransactionSide.DEBIT
        grniLine.amount shouldBe Money(BigDecimal("500.00"), GBP)
        val apLine = entry.lines.first { it.accountId == apAccountId }
        apLine.side shouldBe TransactionSide.CREDIT
        apLine.amount shouldBe Money(BigDecimal("500.00"), GBP)
        apLine.dimensions[DimensionType.VENDOR] shouldBe creditorId.value.toString()
        creditor.balance shouldBe Money(BigDecimal("500.00"), GBP)
        order.status shouldBe PurchaseOrderStatus.RECEIVED
    }

    @Test
    fun `given a mixed GOODS and SERVICE order under CONTROL_TRANSFERS_AT_RECEIPT, when goods arrive before invoice and the invoice is later matched, then SERVICE lines are recognised only at match time alongside the GRNI clearing`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Brazilian Sugar Exports Ltda", GBP, creditorId)
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val serviceAccountId = AccountId.generate()
        val order = PurchaseOrder.create(
            CompanyId.generate(), creditorId, TODAY,
            listOf(goodsLine(stockItem = stockItem), serviceLine(accountId = serviceAccountId)),
            DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT
        )
        val grniAccountId = AccountId.generate()
        val receiveEntry = requireNotNull(order.receiveGoodsBeforeInvoice(listOf(stockItem), grniAccountId, PeriodId.generate()))
        receiveEntry.lines.none { it.accountId == serviceAccountId } shouldBe true

        val entry = requireNotNull(order.matchInvoice(creditor, AccountId.generate(), PeriodId.generate()))

        entry.lines shouldHaveSize 3
        val serviceEntryLine = entry.lines.first { it.accountId == serviceAccountId }
        serviceEntryLine.side shouldBe TransactionSide.DEBIT
        serviceEntryLine.amount shouldBe Money(BigDecimal("50.00"), GBP)
        creditor.balance shouldBe Money(BigDecimal("550.00"), GBP)
    }

    @Test
    fun `given an order not yet in GoodsReceivedPendingInvoice, when the invoice is matched, then it fails`() {
        val creditorId = CreditorId.generate()
        val creditor = Creditor.create(CompanyId.generate(), "Acme Supplies Ltd", GBP, creditorId)
        val order = PurchaseOrder.create(
            CompanyId.generate(), creditorId, TODAY, listOf(serviceLine()), DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT
        )

        val result = order.matchInvoice(creditor, AccountId.generate(), PeriodId.generate())

        result shouldBe null
    }

    @Test
    fun `given a Creditor that does not match the order's creditorId, when the invoice is matched, then it fails and the real Creditor is not charged`() {
        val creditorId = CreditorId.generate()
        val realCreditor = Creditor.create(CompanyId.generate(), "Brazilian Sugar Exports Ltda", GBP, creditorId)
        val wrongCreditor = Creditor.create(CompanyId.generate(), "Someone Else Ltd", GBP)
        val stockItem = StockItem.create(CompanyId.generate(), "Refined White Sugar", GBP)
        val order = PurchaseOrder.create(
            CompanyId.generate(), creditorId, TODAY, listOf(goodsLine(stockItem = stockItem)), DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT
        )
        order.receiveGoodsBeforeInvoice(listOf(stockItem), AccountId.generate(), PeriodId.generate())

        val result = order.matchInvoice(wrongCreditor, AccountId.generate(), PeriodId.generate())

        result shouldBe null
        realCreditor.balance shouldBe Money(BigDecimal.ZERO, GBP)
    }
}
