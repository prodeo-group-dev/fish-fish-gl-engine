package com.theprodeogroup.fish.domain.fixedassets

import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 1, 15)

class FixedAssetTest {

    @Test
    fun `given a new FixedAsset, when created, then net book value equals cost and no depreciation is accumulated`() {
        val asset = equipment()

        asset.netBookValue shouldBe Money(BigDecimal("1000.00"), GBP)
        asset.accumulatedDepreciation shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a non-positive cost, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            FixedAsset.create(
                CompanyId.generate(), "Delivery Van", AssetCategory.VEHICLES,
                Money(BigDecimal.ZERO, GBP), TODAY, 5
            )
        }
    }

    @Test
    fun `given a non-positive useful life, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            FixedAsset.create(
                CompanyId.generate(), "Delivery Van", AssetCategory.VEHICLES,
                Money(BigDecimal("1000.00"), GBP), TODAY, 0
            )
        }
    }

    @Test
    fun `given Land with a useful life, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            FixedAsset.create(
                CompanyId.generate(), "Farm Plot", AssetCategory.LAND,
                Money(BigDecimal("50000.00"), GBP), TODAY, 20
            )
        }
    }

    @Test
    fun `given Land with no useful life, when created, then it succeeds and cannot be depreciated`() {
        val land = FixedAsset.create(
            CompanyId.generate(), "Farm Plot", AssetCategory.LAND,
            Money(BigDecimal("50000.00"), GBP), TODAY
        )

        land.annualDepreciationCharge shouldBe null
        land.recordDepreciation(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY) shouldBe null
    }

    @Test
    fun `given a depreciable asset, when recordDepreciation is called, then it posts a straight-line charge and increases accumulated depreciation`() {
        val expenseAccountId = AccountId.generate()
        val accumulatedDepreciationAccountId = AccountId.generate()
        val asset = FixedAsset.create(
            CompanyId.generate(), "Delivery Van", AssetCategory.VEHICLES,
            Money(BigDecimal("1000.00"), GBP), TODAY, 4
        )

        val entry = requireNotNull(
            asset.recordDepreciation(expenseAccountId, accumulatedDepreciationAccountId, PeriodId.generate(), TODAY)
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val expenseLine = entry.lines.first { it.accountId == expenseAccountId }
        expenseLine.side shouldBe TransactionSide.DEBIT
        expenseLine.amount shouldBe Money(BigDecimal("250.00"), GBP)
        val accumulatedLine = entry.lines.first { it.accountId == accumulatedDepreciationAccountId }
        accumulatedLine.side shouldBe TransactionSide.CREDIT
        accumulatedLine.amount shouldBe Money(BigDecimal("250.00"), GBP)
        asset.accumulatedDepreciation shouldBe Money(BigDecimal("250.00"), GBP)
        asset.netBookValue shouldBe Money(BigDecimal("750.00"), GBP)
    }

    @Test
    fun `given an asset depreciated for its full useful life, when a rounding remainder is left, then the next charge captures exactly the remainder`() {
        val asset = FixedAsset.create(
            CompanyId.generate(), "Delivery Van", AssetCategory.VEHICLES,
            Money(BigDecimal("1000.00"), GBP), TODAY, 3
        )
        // 1000.00 / 3 = 333.33 (HALF_EVEN), so 3 charges leave a 0.01 remainder
        repeat(3) {
            asset.recordDepreciation(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)
        }
        asset.netBookValue shouldBe Money(BigDecimal("0.01"), GBP)

        val entry = requireNotNull(
            asset.recordDepreciation(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)
        )

        entry.lines.first { it.side == TransactionSide.DEBIT }.amount shouldBe Money(BigDecimal("0.01"), GBP)
        asset.netBookValue shouldBe Money(BigDecimal.ZERO, GBP)
        asset.accumulatedDepreciation shouldBe Money(BigDecimal("1000.00"), GBP)
    }

    @Test
    fun `given a fully depreciated asset, when recordDepreciation is called again, then it returns null`() {
        val asset = FixedAsset.create(
            CompanyId.generate(), "Delivery Van", AssetCategory.VEHICLES,
            Money(BigDecimal("1000.00"), GBP), TODAY, 4
        )
        repeat(4) {
            asset.recordDepreciation(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)
        }

        val result = asset.recordDepreciation(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)

        result shouldBe null
        asset.netBookValue shouldBe Money(BigDecimal.ZERO, GBP)
    }

    private fun equipment(): FixedAsset = FixedAsset.create(
        CompanyId.generate(), "Office Equipment", AssetCategory.EQUIPMENT,
        Money(BigDecimal("1000.00"), GBP), TODAY, 5
    )
}
