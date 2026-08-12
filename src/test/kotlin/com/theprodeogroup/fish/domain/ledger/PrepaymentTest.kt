package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 1, 15)

class PrepaymentTest {

    @Test
    fun `given a new Prepayment, when created, then remaining balance equals the amount paid and nothing is released`() {
        val prepayment = insurance()

        prepayment.remainingBalance shouldBe Money(BigDecimal("1200.00"), GBP)
        prepayment.amountReleased shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a non-positive amount, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            Prepayment.create(CompanyId.generate(), "Insurance", Money(BigDecimal.ZERO, GBP), TODAY, 12)
        }
    }

    @Test
    fun `given a non-positive number of periods, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            Prepayment.create(CompanyId.generate(), "Insurance", Money(BigDecimal("1200.00"), GBP), TODAY, 0)
        }
    }

    @Test
    fun `given a Prepayment, when a release is recorded, then it posts a straight-line charge and reduces the remaining balance`() {
        val expenseAccountId = AccountId.generate()
        val prepaidAssetAccountId = AccountId.generate()
        val prepayment = insurance()

        val entry = requireNotNull(prepayment.recordRelease(expenseAccountId, prepaidAssetAccountId, PeriodId.generate(), TODAY))

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val expenseLine = entry.lines.first { it.accountId == expenseAccountId }
        expenseLine.side shouldBe TransactionSide.DEBIT
        expenseLine.amount shouldBe Money(BigDecimal("100.00"), GBP)
        val assetLine = entry.lines.first { it.accountId == prepaidAssetAccountId }
        assetLine.side shouldBe TransactionSide.CREDIT
        assetLine.amount shouldBe Money(BigDecimal("100.00"), GBP)
        prepayment.amountReleased shouldBe Money(BigDecimal("100.00"), GBP)
        prepayment.remainingBalance shouldBe Money(BigDecimal("1100.00"), GBP)
    }

    @Test
    fun `given a rounding remainder after all periods, when the next release is recorded, then it captures exactly the remainder`() {
        val prepayment = Prepayment.create(CompanyId.generate(), "Software Licence", Money(BigDecimal("1000.00"), GBP), TODAY, 3)
        // 1000.00 / 3 = 333.33 (HALF_EVEN), so 3 releases leave a 0.01 remainder
        repeat(3) {
            prepayment.recordRelease(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)
        }
        prepayment.remainingBalance shouldBe Money(BigDecimal("0.01"), GBP)

        val entry = requireNotNull(prepayment.recordRelease(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY))

        entry.lines.first { it.side == TransactionSide.DEBIT }.amount shouldBe Money(BigDecimal("0.01"), GBP)
        prepayment.remainingBalance shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a fully released Prepayment, when a release is recorded again, then it returns null`() {
        val prepayment = insurance()
        repeat(12) {
            prepayment.recordRelease(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)
        }

        val result = prepayment.recordRelease(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)

        result shouldBe null
        prepayment.remainingBalance shouldBe Money(BigDecimal.ZERO, GBP)
    }

    private fun insurance(): Prepayment = Prepayment.create(
        CompanyId.generate(), "Annual Insurance", Money(BigDecimal("1200.00"), GBP), TODAY, 12
    )
}
