package com.theprodeogroup.fish.domain.inventory

import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")

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

    private fun readyItem(): StockItem =
        StockItem.create(CompanyId.generate(), "Widget", GBP)
}
