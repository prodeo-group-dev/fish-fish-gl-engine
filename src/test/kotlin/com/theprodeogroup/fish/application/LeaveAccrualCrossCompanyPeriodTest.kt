package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.payroll.EmployeeId
import com.theprodeogroup.fish.domain.payroll.LeaveAccrual
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/** T19 / F9: a LeaveAccrual of Company A can never be posted into Company B's Period (RBAC finding F9). */
class LeaveAccrualCrossCompanyPeriodTest {

    private val gbp: Currency = Currency.getInstance("GBP")
    private val day = LocalDate.of(2026, 8, 25)

    private val leaveAccrualRepository = FakeLeaveAccrualRepository()
    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()

    private val companyA = CompanyId.generate()
    private val companyB = CompanyId.generate()

    private fun openPeriod(companyId: CompanyId) =
        Period.create(companyId, PeriodType.MONTH, day, day.plusDays(30)).also { it.open(); periodRepository.save(it) }

    private fun account(companyId: CompanyId, code: String, type: AccountType) =
        Account.create(companyId, type, if (type.requiresClassification()) AccountClassification.CURRENT else null, code, "Acct $code")
            .also { accountRepository.save(it) }

    @Test
    fun `remeasure refuses Company B's period for Company A's accrual`() {
        val accrual = LeaveAccrual.create(companyA, EmployeeId.generate(), gbp).also { leaveAccrualRepository.save(it) }
        val periodB = openPeriod(companyB)
        val expenseB = account(companyB, "6100", AccountType.EXPENSE)
        val liabilityB = account(companyB, "2400", AccountType.LIABILITY)

        val result = RemeasureLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
            .execute(RemeasureLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("500.00"), gbp), expenseB.id, liabilityB.id, periodB.id, day))

        result shouldBe RemeasureLeaveAccrualResult.PeriodNotFound
        journalEntryRepository.findAllByPeriod(periodB.id).shouldBeEmpty()
    }

    @Test
    fun `utilize refuses Company B's period for Company A's accrual`() {
        val accrual = LeaveAccrual.create(companyA, EmployeeId.generate(), gbp).also { leaveAccrualRepository.save(it) }
        val periodB = openPeriod(companyB)
        val cashB = account(companyB, "1000", AccountType.ASSET)
        val liabilityB = account(companyB, "2400", AccountType.LIABILITY)

        val result = UtilizeLeaveAccrualUseCase(leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository)
            .execute(UtilizeLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("200.00"), gbp), cashB.id, liabilityB.id, periodB.id, day))

        result shouldBe UtilizeLeaveAccrualResult.PeriodNotFound
        journalEntryRepository.findAllByPeriod(periodB.id).shouldBeEmpty()
    }
}
