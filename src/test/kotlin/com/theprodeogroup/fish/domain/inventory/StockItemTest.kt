package com.theprodeogroup.fish.domain.inventory

import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 2, 15)

class StockItemTest {

    @Test
    fun `given a StockItem is created, then it starts with zero quantity and zero cost`() {
        val item = readyItem()

        item.quantityOnHand shouldBe BigDecimal.ZERO
        item.unitCost shouldBe Money(BigDecimal.ZERO, GBP)
        item.totalValue shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given no stock, when a first receipt is recorded, then the unit cost is simply the received cost`() {
        val item = readyItem()

        val result = item.recordReceipt(BigDecimal("10"), Money(BigDecimal("2.00"), GBP))

        result.isValid shouldBe true
        item.quantityOnHand shouldBe BigDecimal("10")
        item.unitCost shouldBe Money(BigDecimal("2.00"), GBP)
        item.totalValue shouldBe Money(BigDecimal("20.00"), GBP)
    }

    @Test
    fun `given existing stock at one cost, when a second receipt arrives at a different cost, then the unit cost blends to a weighted average`() {
        val item = readyItem()
        item.recordReceipt(BigDecimal("10"), Money(BigDecimal("2.00"), GBP))

        val result = item.recordReceipt(BigDecimal("10"), Money(BigDecimal("4.00"), GBP))

        result.isValid shouldBe true
        item.quantityOnHand shouldBe BigDecimal("20")
        item.unitCost shouldBe Money(BigDecimal("3.00"), GBP)
        item.totalValue shouldBe Money(BigDecimal("60.00"), GBP)
    }

    @Test
    fun `given a non-positive receipt quantity, when recorded, then it fails and nothing changes`() {
        val item = readyItem()

        val result = item.recordReceipt(BigDecimal.ZERO, Money(BigDecimal("2.00"), GBP))

        result.isValid shouldBe false
        item.quantityOnHand shouldBe BigDecimal.ZERO
    }

    @Test
    fun `given a receipt in a different currency, when recorded, then it fails`() {
        val item = readyItem()
        val usd = Currency.getInstance("USD")

        val result = item.recordReceipt(BigDecimal("10"), Money(BigDecimal("2.00"), usd))

        result.isValid shouldBe false
        item.quantityOnHand shouldBe BigDecimal.ZERO
    }

    @Test
    fun `given stock on hand, when an issue is recorded, then quantity decreases and unit cost is unchanged`() {
        val item = readyItem()
        item.recordReceipt(BigDecimal("10"), Money(BigDecimal("2.00"), GBP))

        val result = item.recordIssue(BigDecimal("4"))

        result.isValid shouldBe true
        item.quantityOnHand shouldBe BigDecimal("6")
        item.unitCost shouldBe Money(BigDecimal("2.00"), GBP)
        item.totalValue shouldBe Money(BigDecimal("12.00"), GBP)
    }

    @Test
    fun `given an issue quantity greater than what's on hand, when recorded, then it fails and nothing changes`() {
        val item = readyItem()
        item.recordReceipt(BigDecimal("10"), Money(BigDecimal("2.00"), GBP))

        val result = item.recordIssue(BigDecimal("11"))

        result.isValid shouldBe false
        item.quantityOnHand shouldBe BigDecimal("10")
    }

    @Test
    fun `given a non-positive issue quantity, when recorded, then it fails`() {
        val item = readyItem()
        item.recordReceipt(BigDecimal("10"), Money(BigDecimal("2.00"), GBP))

        val result = item.recordIssue(BigDecimal.ZERO)

        result.isValid shouldBe false
        item.quantityOnHand shouldBe BigDecimal("10")
    }

    @Test
    fun `given no stage is specified, when a StockItem is created, then it defaults to Finished Goods`() {
        val item = readyItem()

        item.stage shouldBe InventoryStage.FINISHED_GOODS
    }

    @Test
    fun `given Raw Material stock, when consumed into a Work in Progress item, then the source decreases and the destination blends in the consumed cost`() {
        val companyId = CompanyId.generate()
        val steel = StockItem.create(companyId, "Steel Sheet", GBP, InventoryStage.RAW_MATERIAL)
        steel.recordReceipt(BigDecimal("100"), Money(BigDecimal("5.00"), GBP))
        val frame = StockItem.create(companyId, "Steel Frame", GBP, InventoryStage.WORK_IN_PROGRESS)

        val result = steel.consumeInto(frame, BigDecimal("30"))

        result.isValid shouldBe true
        steel.quantityOnHand shouldBe BigDecimal("70")
        frame.quantityOnHand shouldBe BigDecimal("30")
        frame.unitCost shouldBe Money(BigDecimal("5.00"), GBP)
        frame.totalValue shouldBe Money(BigDecimal("150.00"), GBP)
    }

    @Test
    fun `given the source is not Raw Material, when consumeInto is called, then it fails and nothing changes`() {
        val companyId = CompanyId.generate()
        val finishedItem = StockItem.create(companyId, "Widget", GBP, InventoryStage.FINISHED_GOODS)
        finishedItem.recordReceipt(BigDecimal("10"), Money(BigDecimal("2.00"), GBP))
        val wip = StockItem.create(companyId, "In Progress", GBP, InventoryStage.WORK_IN_PROGRESS)

        val result = finishedItem.consumeInto(wip, BigDecimal("5"))

        result.isValid shouldBe false
        finishedItem.quantityOnHand shouldBe BigDecimal("10")
        wip.quantityOnHand shouldBe BigDecimal.ZERO
    }

    @Test
    fun `given the destination is not Work in Progress, when consumeInto is called, then it fails and nothing changes`() {
        val companyId = CompanyId.generate()
        val steel = StockItem.create(companyId, "Steel Sheet", GBP, InventoryStage.RAW_MATERIAL)
        steel.recordReceipt(BigDecimal("10"), Money(BigDecimal("2.00"), GBP))
        val finishedItem = StockItem.create(companyId, "Widget", GBP, InventoryStage.FINISHED_GOODS)

        val result = steel.consumeInto(finishedItem, BigDecimal("5"))

        result.isValid shouldBe false
        steel.quantityOnHand shouldBe BigDecimal("10")
    }

    @Test
    fun `given Raw Material and Work in Progress in different currencies, when consumeInto is called, then it fails and neither side changes`() {
        val companyId = CompanyId.generate()
        val steel = StockItem.create(companyId, "Steel Sheet", GBP, InventoryStage.RAW_MATERIAL)
        steel.recordReceipt(BigDecimal("10"), Money(BigDecimal("2.00"), GBP))
        val frame = StockItem.create(companyId, "Steel Frame", Currency.getInstance("USD"), InventoryStage.WORK_IN_PROGRESS)

        val result = steel.consumeInto(frame, BigDecimal("5"))

        result.isValid shouldBe false
        steel.quantityOnHand shouldBe BigDecimal("10")
        frame.quantityOnHand shouldBe BigDecimal.ZERO
    }

    @Test
    fun `given Work in Progress with units in production, when production cost is added, then quantity is unchanged and unit cost rises`() {
        val companyId = CompanyId.generate()
        val frame = StockItem.create(companyId, "Steel Frame", GBP, InventoryStage.WORK_IN_PROGRESS)
        frame.recordReceipt(BigDecimal("30"), Money(BigDecimal("5.00"), GBP))

        val result = frame.addProductionCost(Money(BigDecimal("150.00"), GBP))

        result.isValid shouldBe true
        frame.quantityOnHand shouldBe BigDecimal("30")
        frame.unitCost shouldBe Money(BigDecimal("10.00"), GBP)
        frame.totalValue shouldBe Money(BigDecimal("300.00"), GBP)
    }

    @Test
    fun `given a non-Work-in-Progress item, when production cost is added, then it fails`() {
        val item = readyItem()

        val result = item.addProductionCost(Money(BigDecimal("100.00"), GBP))

        result.isValid shouldBe false
    }

    @Test
    fun `given Work in Progress with no quantity, when production cost is added, then it fails`() {
        val wip = StockItem.create(CompanyId.generate(), "In Progress", GBP, InventoryStage.WORK_IN_PROGRESS)

        val result = wip.addProductionCost(Money(BigDecimal("100.00"), GBP))

        result.isValid shouldBe false
    }

    @Test
    fun `given Work in Progress, when completed into Finished Goods, then the source decreases and the destination blends in the completed cost`() {
        val companyId = CompanyId.generate()
        val frame = StockItem.create(companyId, "Steel Frame", GBP, InventoryStage.WORK_IN_PROGRESS)
        frame.recordReceipt(BigDecimal("30"), Money(BigDecimal("5.00"), GBP))
        frame.addProductionCost(Money(BigDecimal("150.00"), GBP))
        val finishedFrame = StockItem.create(companyId, "Finished Steel Frame", GBP, InventoryStage.FINISHED_GOODS)

        val result = frame.completeInto(finishedFrame, BigDecimal("20"))

        result.isValid shouldBe true
        frame.quantityOnHand shouldBe BigDecimal("10")
        finishedFrame.quantityOnHand shouldBe BigDecimal("20")
        finishedFrame.unitCost shouldBe Money(BigDecimal("10.00"), GBP)
        finishedFrame.totalValue shouldBe Money(BigDecimal("200.00"), GBP)
    }

    @Test
    fun `given the destination is not Finished Goods, when completeInto is called, then it fails and nothing changes`() {
        val companyId = CompanyId.generate()
        val frame = StockItem.create(companyId, "Steel Frame", GBP, InventoryStage.WORK_IN_PROGRESS)
        frame.recordReceipt(BigDecimal("10"), Money(BigDecimal("5.00"), GBP))
        val otherWip = StockItem.create(companyId, "Other WIP", GBP, InventoryStage.WORK_IN_PROGRESS)

        val result = frame.completeInto(otherWip, BigDecimal("5"))

        result.isValid shouldBe false
        frame.quantityOnHand shouldBe BigDecimal("10")
    }

    @Test
    fun `given no NRV assessment has been made, when checked, then carrying value equals cost`() {
        val item = readyItem()
        item.recordReceipt(BigDecimal("100"), Money(BigDecimal("5.00"), GBP))

        item.nrvWriteDownPerUnit shouldBe Money(BigDecimal.ZERO, GBP)
        item.carryingValuePerUnit shouldBe Money(BigDecimal("5.00"), GBP)
        item.totalCarryingValue shouldBe item.totalValue
    }

    @Test
    fun `given net realisable value falls below cost, when assessed, then it posts a write-down and reduces carrying value`() {
        val expenseAccountId = AccountId.generate()
        val inventoryAssetAccountId = AccountId.generate()
        val item = readyItem()
        item.recordReceipt(BigDecimal("100"), Money(BigDecimal("5.00"), GBP))

        val entry = requireNotNull(
            item.assessNetRealisableValue(
                Money(BigDecimal("3.00"), GBP), expenseAccountId, inventoryAssetAccountId, PeriodId.generate(), TODAY
            )
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val expenseLine = entry.lines.first { it.accountId == expenseAccountId }
        expenseLine.side shouldBe TransactionSide.DEBIT
        expenseLine.amount shouldBe Money(BigDecimal("200.00"), GBP)
        val inventoryLine = entry.lines.first { it.accountId == inventoryAssetAccountId }
        inventoryLine.side shouldBe TransactionSide.CREDIT
        inventoryLine.amount shouldBe Money(BigDecimal("200.00"), GBP)
        item.nrvWriteDownPerUnit shouldBe Money(BigDecimal("2.00"), GBP)
        item.carryingValuePerUnit shouldBe Money(BigDecimal("3.00"), GBP)
        item.totalCarryingValue shouldBe Money(BigDecimal("300.00"), GBP)
    }

    @Test
    fun `given net realisable value at or above cost with no prior write-down, when assessed, then it returns null`() {
        val item = readyItem()
        item.recordReceipt(BigDecimal("100"), Money(BigDecimal("5.00"), GBP))

        val result = item.assessNetRealisableValue(
            Money(BigDecimal("6.00"), GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        result shouldBe null
        item.nrvWriteDownPerUnit shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given an existing write-down, when net realisable value partially recovers, then it posts a reversal for the delta only`() {
        val expenseAccountId = AccountId.generate()
        val inventoryAssetAccountId = AccountId.generate()
        val item = readyItem()
        item.recordReceipt(BigDecimal("100"), Money(BigDecimal("5.00"), GBP))
        item.assessNetRealisableValue(Money(BigDecimal("3.00"), GBP), expenseAccountId, inventoryAssetAccountId, PeriodId.generate(), TODAY)

        val entry = requireNotNull(
            item.assessNetRealisableValue(
                Money(BigDecimal("4.00"), GBP), expenseAccountId, inventoryAssetAccountId, PeriodId.generate(), TODAY
            )
        )

        val inventoryLine = entry.lines.first { it.accountId == inventoryAssetAccountId }
        inventoryLine.side shouldBe TransactionSide.DEBIT
        inventoryLine.amount shouldBe Money(BigDecimal("100.00"), GBP)
        val expenseLine = entry.lines.first { it.accountId == expenseAccountId }
        expenseLine.side shouldBe TransactionSide.CREDIT
        item.nrvWriteDownPerUnit shouldBe Money(BigDecimal("1.00"), GBP)
        item.carryingValuePerUnit shouldBe Money(BigDecimal("4.00"), GBP)
    }

    @Test
    fun `given an existing write-down, when net realisable value recovers above cost, then the write-down fully reverses and never exceeds cost`() {
        val expenseAccountId = AccountId.generate()
        val inventoryAssetAccountId = AccountId.generate()
        val item = readyItem()
        item.recordReceipt(BigDecimal("100"), Money(BigDecimal("5.00"), GBP))
        item.assessNetRealisableValue(Money(BigDecimal("3.00"), GBP), expenseAccountId, inventoryAssetAccountId, PeriodId.generate(), TODAY)

        val entry = requireNotNull(
            item.assessNetRealisableValue(
                Money(BigDecimal("9.00"), GBP), expenseAccountId, inventoryAssetAccountId, PeriodId.generate(), TODAY
            )
        )

        entry.lines.first { it.side == TransactionSide.DEBIT }.amount shouldBe Money(BigDecimal("200.00"), GBP)
        item.nrvWriteDownPerUnit shouldBe Money(BigDecimal.ZERO, GBP)
        item.carryingValuePerUnit shouldBe Money(BigDecimal("5.00"), GBP)
        item.carryingValuePerUnit shouldBe item.unitCost
    }

    @Test
    fun `given the same net realisable value re-assessed, when computed, then it returns null - nothing changed`() {
        val item = readyItem()
        item.recordReceipt(BigDecimal("100"), Money(BigDecimal("5.00"), GBP))
        item.assessNetRealisableValue(
            Money(BigDecimal("3.00"), GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        val result = item.assessNetRealisableValue(
            Money(BigDecimal("3.00"), GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        result shouldBe null
    }

    @Test
    fun `given zero quantity on hand, when net realisable value is assessed, then it returns null`() {
        val item = readyItem()

        val result = item.assessNetRealisableValue(
            Money(BigDecimal("3.00"), GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        result shouldBe null
    }

    @Test
    fun `given a net realisable value in a different currency, when assessed, then it returns null`() {
        val item = readyItem()
        item.recordReceipt(BigDecimal("100"), Money(BigDecimal("5.00"), GBP))

        val result = item.assessNetRealisableValue(
            Money(BigDecimal("3.00"), Currency.getInstance("USD")), AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        result shouldBe null
    }

    private fun readyItem(): StockItem =
        StockItem.create(CompanyId.generate(), "Widget", GBP)
}
