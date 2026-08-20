package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.payroll.EmployeeId
import com.theprodeogroup.fish.domain.payroll.LeaveAccrual
import com.theprodeogroup.fish.domain.payroll.LeaveAccrualId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val USD: Currency = Currency.getInstance("USD")
private val TODAY = LocalDate.of(2026, 8, 25)

/** Fakes shared across this package's tests live in `LedgerRepositoryFakes.kt`/`PayrollRepositoryFakes.kt`. */
class RemeasureLeaveAccrualUseCaseTest {

    private val leaveAccrualRepository = FakeLeaveAccrualRepository()
    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = RemeasureLeaveAccrualUseCase(
        leaveAccrualRepository, periodRepository, accountRepository, journalEntryRepository
    )

    private val companyId = CompanyId.generate()

    private fun openPeriod(): Period {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
        return period
    }

    private fun account(code: String, type: AccountType): Account {
        val classification = if (type.requiresClassification()) AccountClassification.CURRENT else null
        val account = Account.create(companyId, type, classification, code, "Test Account")
        accountRepository.save(account)
        return account
    }

    private fun leaveAccrual(): LeaveAccrual {
        val leaveAccrual = LeaveAccrual.create(companyId, EmployeeId.generate(), GBP)
        leaveAccrualRepository.save(leaveAccrual)
        return leaveAccrual
    }

    @Test
    fun `given a first remeasurement to a positive target, when executed, then it returns a Posted JournalEntry and updates the balance`() {
        val period = openPeriod()
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)
        val accrual = leaveAccrual()

        val result = useCase.execute(
            RemeasureLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("500.00"), GBP), expense.id, liability.id, period.id, TODAY)
        )

        val success = result.shouldBeInstanceOf<RemeasureLeaveAccrualResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        success.leaveAccrual.balance shouldBe Money(BigDecimal("500.00"), GBP)
    }

    @Test
    fun `given success, then the LeaveAccrual and JournalEntry are saved, and both Accounts are marked posted`() {
        val period = openPeriod()
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)
        val accrual = leaveAccrual()

        val result = useCase.execute(
            RemeasureLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("500.00"), GBP), expense.id, liability.id, period.id, TODAY)
        )

        val success = result.shouldBeInstanceOf<RemeasureLeaveAccrualResult.Success>()
        leaveAccrualRepository.saveCalls shouldContain accrual.id
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        accountRepository.saveCalls shouldContain expense.id
        accountRepository.saveCalls shouldContain liability.id
        requireNotNull(accountRepository.findById(expense.id)).validateDeletion().isValid shouldBe false
    }

    @Test
    fun `given a target equal to the current balance, when executed, then it returns NoChangeNeeded`() {
        val period = openPeriod()
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)
        val accrual = leaveAccrual()
        accrual.remeasure(Money(BigDecimal("500.00"), GBP), expense.id, liability.id, period.id, TODAY)
        leaveAccrualRepository.save(accrual)

        val result = useCase.execute(
            RemeasureLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("500.00"), GBP), expense.id, liability.id, period.id, TODAY)
        )

        result.shouldBeInstanceOf<RemeasureLeaveAccrualResult.NoChangeNeeded>()
    }

    @Test
    fun `given a lower target than the current balance, when executed, then it posts a reversal`() {
        val period = openPeriod()
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)
        val accrual = leaveAccrual()
        accrual.remeasure(Money(BigDecimal("500.00"), GBP), expense.id, liability.id, period.id, TODAY)
        leaveAccrualRepository.save(accrual)

        val result = useCase.execute(
            RemeasureLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("300.00"), GBP), expense.id, liability.id, period.id, TODAY)
        )

        val success = result.shouldBeInstanceOf<RemeasureLeaveAccrualResult.Success>()
        success.leaveAccrual.balance shouldBe Money(BigDecimal("300.00"), GBP)
    }

    @Test
    fun `given a nonexistent LeaveAccrual id, when executed, then it returns LeaveAccrualNotFound`() {
        val period = openPeriod()
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)

        val result = useCase.execute(
            RemeasureLeaveAccrualUseCase.Request(LeaveAccrualId.generate(), Money(BigDecimal("500.00"), GBP), expense.id, liability.id, period.id, TODAY)
        )

        result.shouldBeInstanceOf<RemeasureLeaveAccrualResult.LeaveAccrualNotFound>()
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)
        val accrual = leaveAccrual()

        val result = useCase.execute(
            RemeasureLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("500.00"), GBP), expense.id, liability.id, PeriodId.generate(), TODAY)
        )

        result.shouldBeInstanceOf<RemeasureLeaveAccrualResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)
        val accrual = leaveAccrual()

        val result = useCase.execute(
            RemeasureLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("500.00"), GBP), expense.id, liability.id, period.id, TODAY)
        )

        result.shouldBeInstanceOf<RemeasureLeaveAccrualResult.PeriodNotOpen>()
    }

    @Test
    fun `given a leave expense Account that does not exist, when executed, then it returns LeaveExpenseAccountNotFound`() {
        val period = openPeriod()
        val liability = account("2400", AccountType.LIABILITY)
        val accrual = leaveAccrual()

        val result = useCase.execute(
            RemeasureLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("500.00"), GBP), AccountId.generate(), liability.id, period.id, TODAY)
        )

        result.shouldBeInstanceOf<RemeasureLeaveAccrualResult.LeaveExpenseAccountNotFound>()
    }

    @Test
    fun `given an accrued leave liability Account that does not exist, when executed, then it returns AccruedLeaveLiabilityAccountNotFound`() {
        val period = openPeriod()
        val expense = account("6100", AccountType.EXPENSE)
        val accrual = leaveAccrual()

        val result = useCase.execute(
            RemeasureLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("500.00"), GBP), expense.id, AccountId.generate(), period.id, TODAY)
        )

        result.shouldBeInstanceOf<RemeasureLeaveAccrualResult.AccruedLeaveLiabilityAccountNotFound>()
    }

    @Test
    fun `given a target amount in a different currency, when executed, then it returns CurrencyMismatch`() {
        val period = openPeriod()
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)
        val accrual = leaveAccrual()

        val result = useCase.execute(
            RemeasureLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("500.00"), USD), expense.id, liability.id, period.id, TODAY)
        )

        result.shouldBeInstanceOf<RemeasureLeaveAccrualResult.CurrencyMismatch>()
    }
}
