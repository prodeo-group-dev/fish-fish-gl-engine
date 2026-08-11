package com.theprodeogroup.fish.domain.payroll

import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.Money
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
private val USD: Currency = Currency.getInstance("USD")
private val TODAY = LocalDate.of(2026, 1, 31)

class PayslipTest {

    @Test
    fun `given gross pay and no deductions, when created, then net pay equals gross pay`() {
        val payslip = payslip(grossPay = Money(BigDecimal("2000.00"), GBP))

        payslip.totalDeductions shouldBe Money(BigDecimal.ZERO, GBP)
        payslip.netPay shouldBe Money(BigDecimal("2000.00"), GBP)
    }

    @Test
    fun `given deductions, when created, then net pay is gross pay minus total deductions`() {
        val payslip = payslip(
            grossPay = Money(BigDecimal("2000.00"), GBP),
            deductions = listOf(
                PayrollDeductionLine("PAYE", AccountId.generate(), Money(BigDecimal("300.00"), GBP)),
                PayrollDeductionLine("National Insurance", AccountId.generate(), Money(BigDecimal("150.00"), GBP))
            )
        )

        payslip.totalDeductions shouldBe Money(BigDecimal("450.00"), GBP)
        payslip.netPay shouldBe Money(BigDecimal("1550.00"), GBP)
    }

    @Test
    fun `given deductions exactly equal to gross pay, when created, then net pay is zero`() {
        val payslip = payslip(
            grossPay = Money(BigDecimal("2000.00"), GBP),
            deductions = listOf(
                PayrollDeductionLine("Salary sacrifice", AccountId.generate(), Money(BigDecimal("2000.00"), GBP))
            )
        )

        payslip.netPay shouldBe Money(BigDecimal.ZERO, GBP)
    }

    @Test
    fun `given deductions greater than gross pay, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            payslip(
                grossPay = Money(BigDecimal("2000.00"), GBP),
                deductions = listOf(
                    PayrollDeductionLine("Over-deduction", AccountId.generate(), Money(BigDecimal("2000.01"), GBP))
                )
            )
        }
    }

    @Test
    fun `given non-positive gross pay, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            payslip(grossPay = Money(BigDecimal.ZERO, GBP))
        }
    }

    @Test
    fun `given a deduction in a different currency than gross pay, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            payslip(
                grossPay = Money(BigDecimal("2000.00"), GBP),
                deductions = listOf(
                    PayrollDeductionLine("Mismatched currency", AccountId.generate(), Money(BigDecimal("100.00"), USD))
                )
            )
        }
    }

    @Test
    fun `given an employer cost in a different currency than gross pay, when created, then it fails`() {
        shouldThrow<IllegalArgumentException> {
            payslip(
                grossPay = Money(BigDecimal("2000.00"), GBP),
                employerCosts = listOf(
                    EmployerCostLine("Employer NI", AccountId.generate(), AccountId.generate(), Money(BigDecimal("100.00"), USD))
                )
            )
        }
    }

    @Test
    fun `given a Payslip with no deductions, when posted, then it debits earnings and credits net pay for the full amount`() {
        val earningsAccountId = AccountId.generate()
        val netPayAccountId = AccountId.generate()
        val payslip = payslip(
            grossPay = Money(BigDecimal("2000.00"), GBP),
            earningsAccountId = earningsAccountId,
            netPayAccountId = netPayAccountId
        )

        val entry = payslip.post(PeriodId.generate())

        entry.lines shouldHaveSize 2
        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val earningsLine = entry.lines.first { it.accountId == earningsAccountId }
        earningsLine.side shouldBe TransactionSide.DEBIT
        earningsLine.amount shouldBe Money(BigDecimal("2000.00"), GBP)
        val netPayLine = entry.lines.first { it.accountId == netPayAccountId }
        netPayLine.side shouldBe TransactionSide.CREDIT
        netPayLine.amount shouldBe Money(BigDecimal("2000.00"), GBP)
    }

    @Test
    fun `given a Payslip with deductions, when posted, then each deduction is credited to its own payable account`() {
        val payeAccountId = AccountId.generate()
        val niAccountId = AccountId.generate()
        val payslip = payslip(
            grossPay = Money(BigDecimal("2000.00"), GBP),
            deductions = listOf(
                PayrollDeductionLine("PAYE", payeAccountId, Money(BigDecimal("300.00"), GBP)),
                PayrollDeductionLine("National Insurance", niAccountId, Money(BigDecimal("150.00"), GBP))
            )
        )

        val entry = payslip.post(PeriodId.generate())

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val payeLine = entry.lines.first { it.accountId == payeAccountId }
        payeLine.side shouldBe TransactionSide.CREDIT
        payeLine.amount shouldBe Money(BigDecimal("300.00"), GBP)
        val niLine = entry.lines.first { it.accountId == niAccountId }
        niLine.side shouldBe TransactionSide.CREDIT
        niLine.amount shouldBe Money(BigDecimal("150.00"), GBP)
    }

    @Test
    fun `given deductions equal to gross pay, when posted, then no zero-amount net pay line is included`() {
        val netPayAccountId = AccountId.generate()
        val payslip = payslip(
            grossPay = Money(BigDecimal("2000.00"), GBP),
            deductions = listOf(
                PayrollDeductionLine("Salary sacrifice", AccountId.generate(), Money(BigDecimal("2000.00"), GBP))
            ),
            netPayAccountId = netPayAccountId
        )

        val entry = payslip.post(PeriodId.generate())

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        entry.lines.none { it.accountId == netPayAccountId } shouldBe true
    }

    @Test
    fun `given a Payslip with employer costs, when posted, then each cost debits its expense account and credits its payable account`() {
        val expenseAccountId = AccountId.generate()
        val payableAccountId = AccountId.generate()
        val payslip = payslip(
            grossPay = Money(BigDecimal("2000.00"), GBP),
            employerCosts = listOf(
                EmployerCostLine("Employer NI", expenseAccountId, payableAccountId, Money(BigDecimal("180.00"), GBP))
            )
        )

        val entry = payslip.post(PeriodId.generate())

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val expenseLine = entry.lines.first { it.accountId == expenseAccountId }
        expenseLine.side shouldBe TransactionSide.DEBIT
        expenseLine.amount shouldBe Money(BigDecimal("180.00"), GBP)
        val payableLine = entry.lines.first { it.accountId == payableAccountId }
        payableLine.side shouldBe TransactionSide.CREDIT
        payableLine.amount shouldBe Money(BigDecimal("180.00"), GBP)
    }

    @Test
    fun `given deductions and employer costs together, when posted, then the entry still balances`() {
        val payslip = payslip(
            grossPay = Money(BigDecimal("2000.00"), GBP),
            deductions = listOf(
                PayrollDeductionLine("PAYE", AccountId.generate(), Money(BigDecimal("300.00"), GBP)),
                PayrollDeductionLine("National Insurance", AccountId.generate(), Money(BigDecimal("150.00"), GBP))
            ),
            employerCosts = listOf(
                EmployerCostLine("Employer NI", AccountId.generate(), AccountId.generate(), Money(BigDecimal("180.00"), GBP))
            )
        )

        val entry = payslip.post(PeriodId.generate())

        entry.lines shouldHaveSize 6
        JournalEntry.validateLines(entry.lines).isValid shouldBe true
    }

    private fun payslip(
        grossPay: Money,
        earningsAccountId: AccountId = AccountId.generate(),
        netPayAccountId: AccountId = AccountId.generate(),
        deductions: List<PayrollDeductionLine> = emptyList(),
        employerCosts: List<EmployerCostLine> = emptyList()
    ): Payslip = Payslip.create(
        CompanyId.generate(), EmployeeId.generate(), TODAY,
        grossPay, earningsAccountId, netPayAccountId, deductions, employerCosts
    )
}
