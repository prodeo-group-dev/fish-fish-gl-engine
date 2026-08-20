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
class UtilizeLeaveAccrualUseCaseTest {

    private val leaveAccrualRepository = FakeLeaveAccrualRepository()
    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = UtilizeLeaveAccrualUseCase(
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

    private fun accrualWithBalance(period: Period, expense: Account, liability: Account, amount: String): LeaveAccrual {
        val accrual = LeaveAccrual.create(companyId, EmployeeId.generate(), GBP)
        accrual.remeasure(Money(BigDecimal(amount), GBP), expense.id, liability.id, period.id, TODAY)
        leaveAccrualRepository.save(accrual)
        return accrual
    }

    @Test
    fun `given a valid utilization within balance, when executed, then it returns a Posted JournalEntry and decreases the balance`() {
        val period = openPeriod()
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)
        val cash = account("1000", AccountType.ASSET)
        val accrual = accrualWithBalance(period, expense, liability, "500.00")

        val result = useCase.execute(
            UtilizeLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("200.00"), GBP), cash.id, liability.id, period.id, TODAY)
        )

        val success = result.shouldBeInstanceOf<UtilizeLeaveAccrualResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        success.leaveAccrual.balance shouldBe Money(BigDecimal("300.00"), GBP)
    }

    @Test
    fun `given success, then the LeaveAccrual and JournalEntry are saved, and both Accounts are marked posted`() {
        val period = openPeriod()
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)
        val cash = account("1000", AccountType.ASSET)
        val accrual = accrualWithBalance(period, expense, liability, "500.00")

        val result = useCase.execute(
            UtilizeLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("200.00"), GBP), cash.id, liability.id, period.id, TODAY)
        )

        val success = result.shouldBeInstanceOf<UtilizeLeaveAccrualResult.Success>()
        leaveAccrualRepository.saveCalls shouldContain accrual.id
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        accountRepository.saveCalls shouldContain cash.id
        accountRepository.saveCalls shouldContain liability.id
    }

    @Test
    fun `given a utilization exceeding the balance, when executed, then it is capped at the balance`() {
        val period = openPeriod()
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)
        val cash = account("1000", AccountType.ASSET)
        val accrual = accrualWithBalance(period, expense, liability, "500.00")

        val result = useCase.execute(
            UtilizeLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("800.00"), GBP), cash.id, liability.id, period.id, TODAY)
        )

        val success = result.shouldBeInstanceOf<UtilizeLeaveAccrualResult.Success>()
        success.leaveAccrual.balance shouldBe Money(BigDecimal("0.00"), GBP)
    }

    @Test
    fun `given a nonexistent LeaveAccrual id, when executed, then it returns LeaveAccrualNotFound`() {
        val period = openPeriod()
        val liability = account("2400", AccountType.LIABILITY)
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(
            UtilizeLeaveAccrualUseCase.Request(LeaveAccrualId.generate(), Money(BigDecimal("200.00"), GBP), cash.id, liability.id, period.id, TODAY)
        )

        result.shouldBeInstanceOf<UtilizeLeaveAccrualResult.LeaveAccrualNotFound>()
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)
        val cash = account("1000", AccountType.ASSET)
        val period = openPeriod()
        val accrual = accrualWithBalance(period, expense, liability, "500.00")

        val result = useCase.execute(
            UtilizeLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("200.00"), GBP), cash.id, liability.id, PeriodId.generate(), TODAY)
        )

        result.shouldBeInstanceOf<UtilizeLeaveAccrualResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val openedPeriod = openPeriod()
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)
        val cash = account("1000", AccountType.ASSET)
        val accrual = accrualWithBalance(openedPeriod, expense, liability, "500.00")
        val draftPeriod = Period.create(companyId, PeriodType.MONTH, TODAY.plusMonths(1), TODAY.plusMonths(2))
        periodRepository.save(draftPeriod)

        val result = useCase.execute(
            UtilizeLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("200.00"), GBP), cash.id, liability.id, draftPeriod.id, TODAY)
        )

        result.shouldBeInstanceOf<UtilizeLeaveAccrualResult.PeriodNotOpen>()
    }

    @Test
    fun `given a cash Account that does not exist, when executed, then it returns CashAccountNotFound`() {
        val period = openPeriod()
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)
        val accrual = accrualWithBalance(period, expense, liability, "500.00")

        val result = useCase.execute(
            UtilizeLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("200.00"), GBP), AccountId.generate(), liability.id, period.id, TODAY)
        )

        result.shouldBeInstanceOf<UtilizeLeaveAccrualResult.CashAccountNotFound>()
    }

    @Test
    fun `given an accrued leave liability Account that does not exist, when executed, then it returns AccruedLeaveLiabilityAccountNotFound`() {
        val period = openPeriod()
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)
        val cash = account("1000", AccountType.ASSET)
        val accrual = accrualWithBalance(period, expense, liability, "500.00")

        val result = useCase.execute(
            UtilizeLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("200.00"), GBP), cash.id, AccountId.generate(), period.id, TODAY)
        )

        result.shouldBeInstanceOf<UtilizeLeaveAccrualResult.AccruedLeaveLiabilityAccountNotFound>()
    }

    @Test
    fun `given an amount in a different currency, when executed, then it returns CurrencyMismatch`() {
        val period = openPeriod()
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)
        val cash = account("1000", AccountType.ASSET)
        val accrual = accrualWithBalance(period, expense, liability, "500.00")

        val result = useCase.execute(
            UtilizeLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("200.00"), USD), cash.id, liability.id, period.id, TODAY)
        )

        result.shouldBeInstanceOf<UtilizeLeaveAccrualResult.CurrencyMismatch>()
    }

    @Test
    fun `given a non-positive amount, when executed, then it returns NonPositiveAmount`() {
        val period = openPeriod()
        val expense = account("6100", AccountType.EXPENSE)
        val liability = account("2400", AccountType.LIABILITY)
        val cash = account("1000", AccountType.ASSET)
        val accrual = accrualWithBalance(period, expense, liability, "500.00")

        val result = useCase.execute(
            UtilizeLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("0.00"), GBP), cash.id, liability.id, period.id, TODAY)
        )

        result.shouldBeInstanceOf<UtilizeLeaveAccrualResult.NonPositiveAmount>()
    }

    @Test
    fun `given a LeaveAccrual with a zero balance, when executed, then it returns NothingToUtilize`() {
        val period = openPeriod()
        val liability = account("2400", AccountType.LIABILITY)
        val cash = account("1000", AccountType.ASSET)
        val accrual = LeaveAccrual.create(companyId, EmployeeId.generate(), GBP)
        leaveAccrualRepository.save(accrual)

        val result = useCase.execute(
            UtilizeLeaveAccrualUseCase.Request(accrual.id, Money(BigDecimal("200.00"), GBP), cash.id, liability.id, period.id, TODAY)
        )

        result.shouldBeInstanceOf<UtilizeLeaveAccrualResult.NothingToUtilize>()
    }
}
