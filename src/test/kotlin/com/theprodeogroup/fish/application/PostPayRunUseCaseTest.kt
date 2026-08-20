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
import com.theprodeogroup.fish.domain.payroll.PayRun
import com.theprodeogroup.fish.domain.payroll.PayRunId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 24)

/** Fakes shared across this package's tests live in `LedgerRepositoryFakes.kt`/`PayrollRepositoryFakes.kt`. */
class PostPayRunUseCaseTest {

    private val payRunRepository = FakePayRunRepository()
    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = PostPayRunUseCase(
        payRunRepository, periodRepository, accountRepository, journalEntryRepository
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

    private fun payRun(totalWages: String, totalSalaries: String): PayRun {
        val payRun = PayRun.create(companyId, TODAY, Money(BigDecimal(totalWages), GBP), Money(BigDecimal(totalSalaries), GBP))
        payRunRepository.save(payRun)
        return payRun
    }

    @Test
    fun `given a PayRun with both wages and salaries in an Open Period, when executed, then it returns a Posted JournalEntry`() {
        val period = openPeriod()
        val wages = account("5100", AccountType.EXPENSE)
        val salaries = account("5200", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)
        val run = payRun("1000.00", "2000.00")

        val result = useCase.execute(PostPayRunUseCase.Request(run.id, period.id, wages.id, salaries.id, cash.id))

        val success = result.shouldBeInstanceOf<PostPayRunResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        val wagesLine = success.journalEntry.lines.single { it.accountId == wages.id }
        val salariesLine = success.journalEntry.lines.single { it.accountId == salaries.id }
        val cashLine = success.journalEntry.lines.single { it.accountId == cash.id }
        wagesLine.amount shouldBe Money(BigDecimal("1000.00"), GBP)
        salariesLine.amount shouldBe Money(BigDecimal("2000.00"), GBP)
        cashLine.amount shouldBe Money(BigDecimal("3000.00"), GBP)
    }

    @Test
    fun `given success, then the JournalEntry is saved and every touched Account is marked posted and saved`() {
        val period = openPeriod()
        val wages = account("5100", AccountType.EXPENSE)
        val salaries = account("5200", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)
        val run = payRun("1000.00", "2000.00")

        val result = useCase.execute(PostPayRunUseCase.Request(run.id, period.id, wages.id, salaries.id, cash.id))

        val success = result.shouldBeInstanceOf<PostPayRunResult.Success>()
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        accountRepository.saveCalls shouldContain wages.id
        accountRepository.saveCalls shouldContain salaries.id
        accountRepository.saveCalls shouldContain cash.id
        requireNotNull(accountRepository.findById(cash.id)).validateDeletion().isValid shouldBe false
    }

    @Test
    fun `given PayRun has no mutable state, when executed, then PayRun is never saved again`() {
        val period = openPeriod()
        val wages = account("5100", AccountType.EXPENSE)
        val salaries = account("5200", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)
        val run = payRun("1000.00", "2000.00")
        val saveCallsBeforeExecute = payRunRepository.saveCalls.toList()

        useCase.execute(PostPayRunUseCase.Request(run.id, period.id, wages.id, salaries.id, cash.id))

        payRunRepository.saveCalls shouldBe saveCallsBeforeExecute
    }

    @Test
    fun `given a PayRun with zero wages, when executed, then only the salaries Account is debited and marked posted`() {
        val period = openPeriod()
        val wages = account("5100", AccountType.EXPENSE)
        val salaries = account("5200", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)
        val run = payRun("0.00", "2000.00")
        val saveCallsBeforeExecute = accountRepository.saveCalls.toList()

        val result = useCase.execute(PostPayRunUseCase.Request(run.id, period.id, wages.id, salaries.id, cash.id))

        val success = result.shouldBeInstanceOf<PostPayRunResult.Success>()
        success.journalEntry.lines.map { it.accountId } shouldNotContain wages.id
        accountRepository.saveCalls.drop(saveCallsBeforeExecute.size) shouldNotContain wages.id
        accountRepository.saveCalls shouldContain salaries.id
    }

    @Test
    fun `given a nonexistent PayRun id, when executed, then it returns PayRunNotFound`() {
        val period = openPeriod()
        val wages = account("5100", AccountType.EXPENSE)
        val salaries = account("5200", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(PostPayRunUseCase.Request(PayRunId.generate(), period.id, wages.id, salaries.id, cash.id))

        result.shouldBeInstanceOf<PostPayRunResult.PayRunNotFound>()
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val wages = account("5100", AccountType.EXPENSE)
        val salaries = account("5200", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)
        val run = payRun("1000.00", "2000.00")

        val result = useCase.execute(PostPayRunUseCase.Request(run.id, PeriodId.generate(), wages.id, salaries.id, cash.id))

        result.shouldBeInstanceOf<PostPayRunResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val wages = account("5100", AccountType.EXPENSE)
        val salaries = account("5200", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)
        val run = payRun("1000.00", "2000.00")

        val result = useCase.execute(PostPayRunUseCase.Request(run.id, period.id, wages.id, salaries.id, cash.id))

        result.shouldBeInstanceOf<PostPayRunResult.PeriodNotOpen>()
    }

    @Test
    fun `given a wages expense Account that does not exist, when executed, then it returns WagesExpenseAccountNotFound`() {
        val period = openPeriod()
        val salaries = account("5200", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)
        val run = payRun("1000.00", "2000.00")

        val result = useCase.execute(PostPayRunUseCase.Request(run.id, period.id, AccountId.generate(), salaries.id, cash.id))

        result.shouldBeInstanceOf<PostPayRunResult.WagesExpenseAccountNotFound>()
    }

    @Test
    fun `given a salaries expense Account that does not exist, when executed, then it returns SalariesExpenseAccountNotFound`() {
        val period = openPeriod()
        val wages = account("5100", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)
        val run = payRun("1000.00", "2000.00")

        val result = useCase.execute(PostPayRunUseCase.Request(run.id, period.id, wages.id, AccountId.generate(), cash.id))

        result.shouldBeInstanceOf<PostPayRunResult.SalariesExpenseAccountNotFound>()
    }

    @Test
    fun `given a cash Account that does not exist, when executed, then it returns CashAccountNotFound`() {
        val period = openPeriod()
        val wages = account("5100", AccountType.EXPENSE)
        val salaries = account("5200", AccountType.EXPENSE)
        val run = payRun("1000.00", "2000.00")

        val result = useCase.execute(PostPayRunUseCase.Request(run.id, period.id, wages.id, salaries.id, AccountId.generate()))

        result.shouldBeInstanceOf<PostPayRunResult.CashAccountNotFound>()
    }
}
