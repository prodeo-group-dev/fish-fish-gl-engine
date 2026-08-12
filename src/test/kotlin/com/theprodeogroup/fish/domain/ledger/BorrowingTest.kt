package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.DimensionType
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

class BorrowingTest {

    @Test
    fun `given a new Borrowing, when created, then outstanding principal equals the principal and nothing is accrued`() {
        val borrowing = wholesaleLoan()

        borrowing.outstandingPrincipal shouldBe Money(BigDecimal("10000.00"), GBP)
        borrowing.accruedInterestPayable shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a non-positive principal, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            Borrowing.create(CompanyId.generate(), "Community Bank", Money(BigDecimal.ZERO, GBP), BigDecimal("0.05"), TODAY)
        }
    }

    @Test
    fun `given a negative interest rate, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            Borrowing.create(CompanyId.generate(), "Community Bank", Money(BigDecimal("10000.00"), GBP), BigDecimal("-0.01"), TODAY)
        }
    }

    @Test
    fun `given a Borrowing, when interest is accrued, then it posts a charge for principal times the annual rate`() {
        val expenseAccountId = AccountId.generate()
        val payableAccountId = AccountId.generate()
        val borrowing = wholesaleLoan()

        val entry = requireNotNull(borrowing.recordInterestAccrual(expenseAccountId, payableAccountId, PeriodId.generate(), TODAY))

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val expenseLine = entry.lines.first { it.accountId == expenseAccountId }
        expenseLine.side shouldBe TransactionSide.DEBIT
        expenseLine.amount shouldBe Money(BigDecimal("500.00"), GBP)
        val payableLine = entry.lines.first { it.accountId == payableAccountId }
        payableLine.side shouldBe TransactionSide.CREDIT
        payableLine.amount shouldBe Money(BigDecimal("500.00"), GBP)
        borrowing.accruedInterestPayable shouldBe Money(BigDecimal("500.00"), GBP)
    }

    @Test
    fun `given a fully repaid Borrowing, when interest is accrued, then it returns null`() {
        val borrowing = wholesaleLoan()
        borrowing.recordPrincipalRepayment(
            Money(BigDecimal("10000.00"), GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        val result = borrowing.recordInterestAccrual(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)

        result shouldBe null
    }

    @Test
    fun `given a zero-interest Borrowing, when interest is accrued, then it returns null`() {
        val borrowing = Borrowing.create(CompanyId.generate(), "Directors Loan", Money(BigDecimal("5000.00"), GBP), BigDecimal.ZERO, TODAY)

        val result = borrowing.recordInterestAccrual(AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY)

        result shouldBe null
    }

    @Test
    fun `given accrued interest, when an interest payment is recorded, then it debits the payable and credits Cash tagged Operating`() {
        val cashAccountId = AccountId.generate()
        val payableAccountId = AccountId.generate()
        val borrowing = wholesaleLoan()
        borrowing.recordInterestAccrual(AccountId.generate(), payableAccountId, PeriodId.generate(), TODAY)

        val entry = requireNotNull(
            borrowing.recordInterestPayment(
                Money(BigDecimal("500.00"), GBP), cashAccountId, payableAccountId, PeriodId.generate(), TODAY
            )
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val payableLine = entry.lines.first { it.accountId == payableAccountId }
        payableLine.side shouldBe TransactionSide.DEBIT
        val cashLine = entry.lines.first { it.accountId == cashAccountId }
        cashLine.side shouldBe TransactionSide.CREDIT
        cashLine.dimensions[DimensionType.CASH_FLOW_ACTIVITY] shouldBe CashFlowActivity.OPERATING.name
        borrowing.accruedInterestPayable shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given an interest payment larger than what is owed, when recorded, then it is capped at the accrued amount`() {
        val payableAccountId = AccountId.generate()
        val borrowing = wholesaleLoan()
        borrowing.recordInterestAccrual(AccountId.generate(), payableAccountId, PeriodId.generate(), TODAY)

        val entry = requireNotNull(
            borrowing.recordInterestPayment(
                Money(BigDecimal("999.00"), GBP), AccountId.generate(), payableAccountId, PeriodId.generate(), TODAY
            )
        )

        entry.lines.first { it.side == TransactionSide.DEBIT }.amount shouldBe Money(BigDecimal("500.00"), GBP)
        borrowing.accruedInterestPayable shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given nothing owed, when an interest payment is recorded, then it returns null`() {
        val borrowing = wholesaleLoan()

        val result = borrowing.recordInterestPayment(
            Money(BigDecimal("100.00"), GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        result shouldBe null
    }

    @Test
    fun `given a Borrowing, when principal is repaid, then it debits the Loan Payable account and credits Cash tagged Financing`() {
        val cashAccountId = AccountId.generate()
        val loanPayableAccountId = AccountId.generate()
        val borrowing = wholesaleLoan()

        val entry = requireNotNull(
            borrowing.recordPrincipalRepayment(
                Money(BigDecimal("4000.00"), GBP), cashAccountId, loanPayableAccountId, PeriodId.generate(), TODAY
            )
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val loanLine = entry.lines.first { it.accountId == loanPayableAccountId }
        loanLine.side shouldBe TransactionSide.DEBIT
        loanLine.amount shouldBe Money(BigDecimal("4000.00"), GBP)
        val cashLine = entry.lines.first { it.accountId == cashAccountId }
        cashLine.side shouldBe TransactionSide.CREDIT
        cashLine.dimensions[DimensionType.CASH_FLOW_ACTIVITY] shouldBe CashFlowActivity.FINANCING.name
        borrowing.outstandingPrincipal shouldBe Money(BigDecimal("6000.00"), GBP)
    }

    @Test
    fun `given a repayment larger than what is outstanding, when recorded, then it is capped at the outstanding principal`() {
        val borrowing = wholesaleLoan()

        val entry = requireNotNull(
            borrowing.recordPrincipalRepayment(
                Money(BigDecimal("50000.00"), GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
            )
        )

        entry.lines.first { it.side == TransactionSide.DEBIT }.amount shouldBe Money(BigDecimal("10000.00"), GBP)
        borrowing.outstandingPrincipal shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given a fully repaid Borrowing, when principal is repaid again, then it returns null`() {
        val borrowing = wholesaleLoan()
        borrowing.recordPrincipalRepayment(
            Money(BigDecimal("10000.00"), GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        val result = borrowing.recordPrincipalRepayment(
            Money(BigDecimal("100.00"), GBP), AccountId.generate(), AccountId.generate(), PeriodId.generate(), TODAY
        )

        result shouldBe null
    }

    private fun wholesaleLoan(): Borrowing = Borrowing.create(
        CompanyId.generate(), "Community Bank", Money(BigDecimal("10000.00"), GBP), BigDecimal("0.05"), TODAY
    )
}
