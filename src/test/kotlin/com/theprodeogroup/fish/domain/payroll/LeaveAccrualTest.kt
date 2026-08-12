package com.theprodeogroup.fish.domain.payroll

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 1, 15)

/**
 * LeaveAccrual is a thin delegating wrapper over Provision - these
 * tests confirm the delegation is wired correctly (posting shape,
 * balance tracking), not every Provision edge case (capping, null on
 * zero delta, etc.), which is already covered by ProvisionTest.
 */
class LeaveAccrualTest {

    @Test
    fun `given a new LeaveAccrual, when created, then its balance is zero and it is scoped to the employee`() {
        val employeeId = EmployeeId.generate()
        val accrual = LeaveAccrual.create(CompanyId.generate(), employeeId, GBP)

        accrual.balance shouldBe Money(BigDecimal.ZERO, GBP)
        accrual.employeeId shouldBe employeeId
    }

    @Test
    fun `given a LeaveAccrual, when remeasured to an estimate, then it debits leave expense and credits the accrued liability`() {
        val leaveExpenseAccountId = AccountId.generate()
        val liabilityAccountId = AccountId.generate()
        val accrual = LeaveAccrual.create(CompanyId.generate(), EmployeeId.generate(), GBP)

        val entry = requireNotNull(
            accrual.remeasure(Money(BigDecimal("240.00"), GBP), leaveExpenseAccountId, liabilityAccountId, PeriodId.generate(), TODAY)
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val expenseLine = entry.lines.first { it.accountId == leaveExpenseAccountId }
        expenseLine.side shouldBe TransactionSide.DEBIT
        expenseLine.amount shouldBe Money(BigDecimal("240.00"), GBP)
        val liabilityLine = entry.lines.first { it.accountId == liabilityAccountId }
        liabilityLine.side shouldBe TransactionSide.CREDIT
        accrual.balance shouldBe Money(BigDecimal("240.00"), GBP)
    }

    @Test
    fun `given accrued leave, when utilized, then it debits the accrued liability and credits Cash tagged Operating`() {
        val liabilityAccountId = AccountId.generate()
        val cashAccountId = AccountId.generate()
        val accrual = LeaveAccrual.create(CompanyId.generate(), EmployeeId.generate(), GBP)
        accrual.remeasure(Money(BigDecimal("240.00"), GBP), AccountId.generate(), liabilityAccountId, PeriodId.generate(), TODAY)

        val entry = requireNotNull(
            accrual.utilizeLeave(Money(BigDecimal("80.00"), GBP), cashAccountId, liabilityAccountId, PeriodId.generate(), TODAY)
        )

        JournalEntry.validateLines(entry.lines).isValid shouldBe true
        val liabilityLine = entry.lines.first { it.accountId == liabilityAccountId }
        liabilityLine.side shouldBe TransactionSide.DEBIT
        liabilityLine.amount shouldBe Money(BigDecimal("80.00"), GBP)
        val cashLine = entry.lines.first { it.accountId == cashAccountId }
        cashLine.side shouldBe TransactionSide.CREDIT
        cashLine.dimensions[DimensionType.CASH_FLOW_ACTIVITY] shouldBe CashFlowActivity.OPERATING.name
        accrual.balance shouldBe Money(BigDecimal("160.00"), GBP)
    }

    @Test
    fun `given a LeaveAccrual remeasured to its current balance, when remeasured again, then it returns null`() {
        val liabilityAccountId = AccountId.generate()
        val accrual = LeaveAccrual.create(CompanyId.generate(), EmployeeId.generate(), GBP)
        accrual.remeasure(Money(BigDecimal("240.00"), GBP), AccountId.generate(), liabilityAccountId, PeriodId.generate(), TODAY)

        val result = accrual.remeasure(
            Money(BigDecimal("240.00"), GBP), AccountId.generate(), liabilityAccountId, PeriodId.generate(), TODAY
        )

        result shouldBe null
    }
}
