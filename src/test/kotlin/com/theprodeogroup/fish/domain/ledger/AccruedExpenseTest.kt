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
private val PERIOD_END = LocalDate.of(2026, 1, 31)
private val NEXT_PERIOD_START = LocalDate.of(2026, 2, 1)

class AccruedExpenseTest {

    @Test
    fun `given a new AccruedExpense, when created, then it is not yet posted, not reversed, and not outstanding`() {
        val accrual = utilityBill()

        accrual.isPosted shouldBe false
        accrual.isReversed shouldBe false
        accrual.isOutstanding shouldBe false
    }

    @Test
    fun `given a non-positive amount, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            AccruedExpense.create(CompanyId.generate(), "Electricity", Money(BigDecimal.ZERO, GBP), PERIOD_END)
        }
    }

    @Test
    fun `given an AccruedExpense, when the accrual is posted, then it debits Expense and credits Accrued Liability for the full amount`() {
        val expenseAccountId = AccountId.generate()
        val accruedLiabilityAccountId = AccountId.generate()
        val accrual = utilityBill()

        val entry = requireNotNull(accrual.postAccrual(expenseAccountId, accruedLiabilityAccountId, PeriodId.generate(), PERIOD_END))

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val expenseLine = entry.lines.first { it.accountId == expenseAccountId }
        expenseLine.side shouldBe TransactionSide.DEBIT
        expenseLine.amount shouldBe Money(BigDecimal("450.00"), GBP)
        val liabilityLine = entry.lines.first { it.accountId == accruedLiabilityAccountId }
        liabilityLine.side shouldBe TransactionSide.CREDIT
        liabilityLine.amount shouldBe Money(BigDecimal("450.00"), GBP)
        accrual.isPosted shouldBe true
        accrual.isOutstanding shouldBe true
    }

    @Test
    fun `given an already-posted AccruedExpense, when the accrual is posted again, then it returns null`() {
        val accrual = utilityBill()
        accrual.postAccrual(AccountId.generate(), AccountId.generate(), PeriodId.generate(), PERIOD_END)

        val result = accrual.postAccrual(AccountId.generate(), AccountId.generate(), PeriodId.generate(), PERIOD_END)

        result shouldBe null
    }

    @Test
    fun `given a posted AccruedExpense, when reversed, then it debits Accrued Liability and credits Expense for the full amount`() {
        val expenseAccountId = AccountId.generate()
        val accruedLiabilityAccountId = AccountId.generate()
        val accrual = utilityBill()
        accrual.postAccrual(expenseAccountId, accruedLiabilityAccountId, PeriodId.generate(), PERIOD_END)

        val entry = requireNotNull(
            accrual.reverseAccrual(expenseAccountId, accruedLiabilityAccountId, PeriodId.generate(), NEXT_PERIOD_START)
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val liabilityLine = entry.lines.first { it.accountId == accruedLiabilityAccountId }
        liabilityLine.side shouldBe TransactionSide.DEBIT
        liabilityLine.amount shouldBe Money(BigDecimal("450.00"), GBP)
        val expenseLine = entry.lines.first { it.accountId == expenseAccountId }
        expenseLine.side shouldBe TransactionSide.CREDIT
        expenseLine.amount shouldBe Money(BigDecimal("450.00"), GBP)
        accrual.isReversed shouldBe true
        accrual.isOutstanding shouldBe false
    }

    @Test
    fun `given an AccruedExpense not yet posted, when reversed, then it returns null`() {
        val accrual = utilityBill()

        val result = accrual.reverseAccrual(AccountId.generate(), AccountId.generate(), PeriodId.generate(), NEXT_PERIOD_START)

        result shouldBe null
    }

    @Test
    fun `given an already-reversed AccruedExpense, when reversed again, then it returns null`() {
        val accrual = utilityBill()
        accrual.postAccrual(AccountId.generate(), AccountId.generate(), PeriodId.generate(), PERIOD_END)
        accrual.reverseAccrual(AccountId.generate(), AccountId.generate(), PeriodId.generate(), NEXT_PERIOD_START)

        val result = accrual.reverseAccrual(AccountId.generate(), AccountId.generate(), PeriodId.generate(), NEXT_PERIOD_START)

        result shouldBe null
    }

    @Test
    fun `given a list of accruals in various states, when filtered for outstanding, then only posted-and-not-reversed accruals are returned`() {
        val neverPosted = utilityBill()
        val postedNotReversed = utilityBill()
        postedNotReversed.postAccrual(AccountId.generate(), AccountId.generate(), PeriodId.generate(), PERIOD_END)
        val postedAndReversed = utilityBill()
        postedAndReversed.postAccrual(AccountId.generate(), AccountId.generate(), PeriodId.generate(), PERIOD_END)
        postedAndReversed.reverseAccrual(AccountId.generate(), AccountId.generate(), PeriodId.generate(), NEXT_PERIOD_START)

        val outstanding = listOf(neverPosted, postedNotReversed, postedAndReversed).filter { it.isOutstanding }

        outstanding shouldBe listOf(postedNotReversed)
    }

    private fun utilityBill(): AccruedExpense = AccruedExpense.create(
        CompanyId.generate(), "Electricity - January estimate", Money(BigDecimal("450.00"), GBP), PERIOD_END
    )
}
