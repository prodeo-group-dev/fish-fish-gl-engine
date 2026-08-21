package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.payroll.EmployeeId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")

/**
 * HR/Payroll calls this once per Employee it needs a `LeaveAccrualId`
 * for, safe to call repeatedly - the `LeaveAccrual`-side counterpart to
 * `RecordPayRunUseCase`, closing the identical "no route exists to
 * create the aggregate `RemeasureLeaveAccrualUseCase`/
 * `UtilizeLeaveAccrualUseCase` need to look up by ID" gap.
 */
class GetOrCreateLeaveAccrualUseCaseTest {

    private val leaveAccrualRepository = FakeLeaveAccrualRepository()
    private val useCase = GetOrCreateLeaveAccrualUseCase(leaveAccrualRepository)

    private val companyId = CompanyId.generate()
    private val employeeId = EmployeeId.generate()

    @Test
    fun `given no existing LeaveAccrual for this Employee, when executed, then it creates and saves a new one with a zero balance`() {
        val result = useCase.execute(GetOrCreateLeaveAccrualUseCase.Request(companyId, employeeId, GBP))

        result.companyId shouldBe companyId
        result.employeeId shouldBe employeeId
        result.balance.amount shouldBe BigDecimal("0.00")
        result.balance.currency shouldBe GBP
        leaveAccrualRepository.saveCalls shouldContain result.id
    }

    @Test
    fun `given an existing LeaveAccrual for this Employee, when executed again, then it returns the same one without creating a second`() {
        val first = useCase.execute(GetOrCreateLeaveAccrualUseCase.Request(companyId, employeeId, GBP))

        val second = useCase.execute(GetOrCreateLeaveAccrualUseCase.Request(companyId, employeeId, GBP))

        second.id shouldBe first.id
        leaveAccrualRepository.saveCalls.size shouldBe 1
    }

    @Test
    fun `given LeaveAccruals for other Employees in the same Company, when executed, then it does not match them`() {
        useCase.execute(GetOrCreateLeaveAccrualUseCase.Request(companyId, EmployeeId.generate(), GBP))
        useCase.execute(GetOrCreateLeaveAccrualUseCase.Request(companyId, EmployeeId.generate(), GBP))

        val result = useCase.execute(GetOrCreateLeaveAccrualUseCase.Request(companyId, employeeId, GBP))

        leaveAccrualRepository.saveCalls.size shouldBe 3
        result.employeeId shouldBe employeeId
    }

    @Test
    fun `given the same Employee in a different Company, when executed, then it creates a separate LeaveAccrual`() {
        val otherCompanyId = CompanyId.generate()
        val first = useCase.execute(GetOrCreateLeaveAccrualUseCase.Request(companyId, employeeId, GBP))

        val second = useCase.execute(GetOrCreateLeaveAccrualUseCase.Request(otherCompanyId, employeeId, GBP))

        second.id shouldBe second.id
        (second.id == first.id) shouldBe false
    }
}
