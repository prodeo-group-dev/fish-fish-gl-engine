package com.theprodeogroup.fish.domain.fixedassets

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
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

    @Test
    fun `given an already-disposed asset, when disposed again, then it fails`() {
        val asset = equipment()
        asset.dispose(
            Money(BigDecimal("1000.00"), GBP), AccountId.generate(), AccountId.generate(),
            AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        val result = asset.dispose(
            Money(BigDecimal("100.00"), GBP), AccountId.generate(), AccountId.generate(),
            AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        result shouldBe null
    }

    @Test
    fun `given a disposed asset, when recordDepreciation is called, then it fails`() {
        val asset = FixedAsset.create(
            CompanyId.generate(), "Delivery Van", AssetCategory.VEHICLES,
            Money(BigDecimal("1000.00"), GBP), TODAY, 4
        )
        asset.dispose(
            Money(BigDecimal("750.00"), GBP), AccountId.generate(), AccountId.generate(),
            AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        val result = asset.recordDepreciation(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)

        result shouldBe null
    }

    @Test
    fun `given recoverable amount falls below net book value, when impairment is assessed, then it posts a loss and reduces carrying amount`() {
        val expenseAccountId = AccountId.generate()
        val accumulatedImpairmentAccountId = AccountId.generate()
        val asset = machine()
        asset.recordDepreciation(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)
        // netBookValue is now 750.00

        val entry = requireNotNull(
            asset.assessImpairment(
                Money(BigDecimal("600.00"), GBP), expenseAccountId, accumulatedImpairmentAccountId, PeriodId.generate(), TODAY
            )
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val expenseLine = entry.lines.first { it.accountId == expenseAccountId }
        expenseLine.side shouldBe TransactionSide.DEBIT
        expenseLine.amount shouldBe Money(BigDecimal("150.00"), GBP)
        val impairmentLine = entry.lines.first { it.accountId == accumulatedImpairmentAccountId }
        impairmentLine.side shouldBe TransactionSide.CREDIT
        impairmentLine.amount shouldBe Money(BigDecimal("150.00"), GBP)
        asset.accumulatedImpairmentLoss shouldBe Money(BigDecimal("150.00"), GBP)
        asset.carryingAmount shouldBe Money(BigDecimal("600.00"), GBP)
    }

    @Test
    fun `given recoverable amount at or above net book value, when impairment is assessed, then it returns null`() {
        val asset = machine()

        val result = asset.assessImpairment(
            Money(BigDecimal("1200.00"), GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        result shouldBe null
        asset.accumulatedImpairmentLoss shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given an existing impairment, when recoverable amount partially recovers, then it posts a reversal for the delta only`() {
        val expenseAccountId = AccountId.generate()
        val accumulatedImpairmentAccountId = AccountId.generate()
        val asset = machine()
        asset.recordDepreciation(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)
        asset.assessImpairment(Money(BigDecimal("600.00"), GBP), expenseAccountId, accumulatedImpairmentAccountId, PeriodId.generate(), TODAY)

        val entry = requireNotNull(
            asset.assessImpairment(
                Money(BigDecimal("700.00"), GBP), expenseAccountId, accumulatedImpairmentAccountId, PeriodId.generate(), TODAY
            )
        )

        val impairmentLine = entry.lines.first { it.accountId == accumulatedImpairmentAccountId }
        impairmentLine.side shouldBe TransactionSide.DEBIT
        impairmentLine.amount shouldBe Money(BigDecimal("100.00"), GBP)
        asset.accumulatedImpairmentLoss shouldBe Money(BigDecimal("50.00"), GBP)
        asset.carryingAmount shouldBe Money(BigDecimal("700.00"), GBP)
    }

    @Test
    fun `given an existing impairment, when recoverable amount recovers above net book value, then the reversal is capped at net book value`() {
        val expenseAccountId = AccountId.generate()
        val accumulatedImpairmentAccountId = AccountId.generate()
        val asset = machine()
        asset.recordDepreciation(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)
        asset.assessImpairment(Money(BigDecimal("600.00"), GBP), expenseAccountId, accumulatedImpairmentAccountId, PeriodId.generate(), TODAY)

        val entry = requireNotNull(
            asset.assessImpairment(
                Money(BigDecimal("900.00"), GBP), expenseAccountId, accumulatedImpairmentAccountId, PeriodId.generate(), TODAY
            )
        )

        entry.lines.first { it.side == TransactionSide.DEBIT }.amount shouldBe Money(BigDecimal("150.00"), GBP)
        asset.accumulatedImpairmentLoss shouldBe Money(BigDecimal.ZERO, GBP)
        asset.carryingAmount shouldBe Money(BigDecimal("750.00"), GBP)
        asset.carryingAmount shouldBe asset.netBookValue
    }

    @Test
    fun `given a disposed asset, when impairment is assessed, then it returns null`() {
        val asset = machine()
        asset.dispose(
            Money(BigDecimal("750.00"), GBP), AccountId.generate(), AccountId.generate(),
            AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        val result = asset.assessImpairment(
            Money(BigDecimal("100.00"), GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        result shouldBe null
    }

    @Test
    fun `given an impaired asset, when disposed without an accumulated impairment account, then it returns null`() {
        val asset = machine()
        asset.assessImpairment(Money(BigDecimal("600.00"), GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)

        val result = asset.dispose(
            Money(BigDecimal("600.00"), GBP), AccountId.generate(), AccountId.generate(),
            AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        result shouldBe null
    }

    // --- disposal: the gain or loss is its own line on its own account, never left on a revenue clearing account
    // (tester finding 2026-10-18: an 800 loss had been booked as negative Sales Revenue) ---

    @Test
    fun `given proceeds above the carrying amount, when disposed, then the gain is credited to the gain account and nothing is left on a clearing account`() {
        val asset = FixedAsset.create(CompanyId.generate(), "Delivery Van", AssetCategory.VEHICLES, Money(BigDecimal("1000.00"), GBP), TODAY, 4)
        asset.recordDepreciation(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)
        // carrying amount is now 750.00
        val cash = AccountId.generate()
        val cost = AccountId.generate()
        val accDep = AccountId.generate()
        val gain = AccountId.generate()

        val entry = requireNotNull(asset.dispose(Money(BigDecimal("800.00"), GBP), cash, cost, accDep, gain, PeriodId.generate(), TODAY))

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        entry.lines shouldHaveSize 4
        entry.lines.single { it.accountId == cost }.let { it.side shouldBe TransactionSide.CREDIT; it.amount shouldBe Money(BigDecimal("1000.00"), GBP) }
        entry.lines.single { it.accountId == accDep }.let { it.side shouldBe TransactionSide.DEBIT; it.amount shouldBe Money(BigDecimal("250.00"), GBP) }
        entry.lines.single { it.accountId == cash }.let {
            it.side shouldBe TransactionSide.DEBIT
            it.amount shouldBe Money(BigDecimal("800.00"), GBP)
            it.dimensions[DimensionType.CASH_FLOW_ACTIVITY] shouldBe CashFlowActivity.INVESTING.name
        }
        entry.lines.single { it.accountId == gain }.let { it.side shouldBe TransactionSide.CREDIT; it.amount shouldBe Money(BigDecimal("50.00"), GBP) }
    }

    @Test
    fun `given proceeds below the carrying amount, when disposed, then the loss is debited to the loss account`() {
        val asset = FixedAsset.create(CompanyId.generate(), "Delivery Van", AssetCategory.VEHICLES, Money(BigDecimal("1000.00"), GBP), TODAY, 4)
        asset.recordDepreciation(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)
        val loss = AccountId.generate()

        val entry = requireNotNull(
            asset.dispose(Money(BigDecimal("500.00"), GBP), AccountId.generate(), AccountId.generate(), AccountId.generate(), loss, PeriodId.generate(), TODAY)
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        entry.lines.single { it.accountId == loss }.let { it.side shouldBe TransactionSide.DEBIT; it.amount shouldBe Money(BigDecimal("250.00"), GBP) }
    }

    @Test
    fun `given the tester's case, 1200 cost, 400 depreciated and nil proceeds, when disposed, then it is Dr accumulated depreciation 400, Dr loss 800, Cr cost 1200`() {
        val asset = FixedAsset.create(CompanyId.generate(), "UAT Laptop", AssetCategory.EQUIPMENT, Money(BigDecimal("1200.00"), GBP), TODAY, 3)
        asset.recordDepreciation(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)
        val cost = AccountId.generate()
        val accDep = AccountId.generate()
        val loss = AccountId.generate()

        val entry = requireNotNull(
            asset.dispose(Money(BigDecimal.ZERO, GBP), AccountId.generate(), cost, accDep, loss, PeriodId.generate(), TODAY)
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        entry.lines shouldHaveSize 3
        entry.lines.single { it.accountId == accDep }.let { it.side shouldBe TransactionSide.DEBIT; it.amount shouldBe Money(BigDecimal("400.00"), GBP) }
        entry.lines.single { it.accountId == loss }.let { it.side shouldBe TransactionSide.DEBIT; it.amount shouldBe Money(BigDecimal("800.00"), GBP) }
        entry.lines.single { it.accountId == cost }.let { it.side shouldBe TransactionSide.CREDIT; it.amount shouldBe Money(BigDecimal("1200.00"), GBP) }
    }

    @Test
    fun `given no depreciation and proceeds equal to cost, when disposed, then there is no gain or loss line and no account is needed`() {
        val asset = equipment()
        val accDep = AccountId.generate()

        val entry = requireNotNull(
            asset.dispose(Money(BigDecimal("1000.00"), GBP), AccountId.generate(), AccountId.generate(), accDep, null, PeriodId.generate(), TODAY)
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        entry.lines shouldHaveSize 2
        entry.lines.none { it.accountId == accDep } shouldBe true
    }

    @Test
    fun `given an impaired asset, when disposed with proceeds equal to the carrying amount, then the impairment is cleared and there is no gain or loss line`() {
        val accDep = AccountId.generate()
        val accImpairment = AccountId.generate()
        val asset = machine()
        asset.recordDepreciation(AccountId.generate(), accDep, PeriodId.generate(), TODAY)
        asset.assessImpairment(Money(BigDecimal("600.00"), GBP), AccountId.generate(), accImpairment, PeriodId.generate(), TODAY)
        // carrying amount is now 600.00

        val entry = requireNotNull(
            asset.dispose(
                Money(BigDecimal("600.00"), GBP), AccountId.generate(), AccountId.generate(), accDep, null, PeriodId.generate(), TODAY, accImpairment
            )
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        entry.lines.single { it.accountId == accImpairment }.let { it.side shouldBe TransactionSide.DEBIT; it.amount shouldBe Money(BigDecimal("150.00"), GBP) }
        entry.lines shouldHaveSize 4
    }

    @Test
    fun `given a gain or loss and no account to put it on, when disposed, then it is a programmer error, not a silent default`() {
        val asset = equipment()

        shouldThrow<IllegalArgumentException> {
            asset.dispose(Money(BigDecimal("400.00"), GBP), AccountId.generate(), AccountId.generate(), AccountId.generate(), null, PeriodId.generate(), TODAY)
        }
        asset.isDisposed shouldBe false
    }

    private fun machine(): FixedAsset = FixedAsset.create(
        CompanyId.generate(), "Factory Machine", AssetCategory.EQUIPMENT,
        Money(BigDecimal("1000.00"), GBP), TODAY, 4
    )

    private fun equipment(): FixedAsset = FixedAsset.create(
        CompanyId.generate(), "Office Equipment", AssetCategory.EQUIPMENT,
        Money(BigDecimal("1000.00"), GBP), TODAY, 5
    )
}
