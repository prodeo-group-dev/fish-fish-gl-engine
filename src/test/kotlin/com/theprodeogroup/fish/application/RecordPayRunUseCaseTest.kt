package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 21)

/**
 * HR/Payroll's `PayrollBatch` computes `totalWages`/`totalSalaries` and
 * calls this - the `PayRun`-side counterpart to `RecordSaleUseCaseTest`,
 * closing the identical blocker `RecordSaleUseCase` already closed for
 * Sales Order Processing (`PostPayRunUseCase` needs an already-persisted
 * `PayRun`; this needs none at all).
 */
class RecordPayRunUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = RecordPayRunUseCase(periodRepository, accountRepository, journalEntryRepository)

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

    private fun request(
        periodId: PeriodId,
        wagesAccountId: AccountId,
        salariesAccountId: AccountId,
        cashAccountId: AccountId,
        totalWages: String = "5000.00",
        totalSalaries: String = "8000.00"
    ) = RecordPayRunUseCase.Request(
        companyId, periodId, TODAY, Money(BigDecimal(totalWages), GBP), Money(BigDecimal(totalSalaries), GBP),
        wagesAccountId, salariesAccountId, cashAccountId
    )

    @Test
    fun `given a valid request in an Open Period, when executed, then it posts a JournalEntry debiting both expense Accounts and crediting Cash`() {
        val period = openPeriod()
        val wages = account("6000", AccountType.EXPENSE)
        val salaries = account("6010", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(request(period.id, wages.id, salaries.id, cash.id))

        val success = result.shouldBeInstanceOf<RecordPayRunResult.Success>()
        success.journalEntry.status shouldBe PostingStatus.POSTED
        success.journalEntry.lines.single { it.accountId == wages.id }.amount shouldBe Money(BigDecimal("5000.00"), GBP)
        success.journalEntry.lines.single { it.accountId == salaries.id }.amount shouldBe Money(BigDecimal("8000.00"), GBP)
        success.journalEntry.lines.single { it.accountId == cash.id }.amount shouldBe Money(BigDecimal("13000.00"), GBP)
    }

    @Test
    fun `given success, then the JournalEntry is saved and every touched Account is marked posted and saved`() {
        val period = openPeriod()
        val wages = account("6000", AccountType.EXPENSE)
        val salaries = account("6010", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(request(period.id, wages.id, salaries.id, cash.id))

        val success = result.shouldBeInstanceOf<RecordPayRunResult.Success>()
        journalEntryRepository.saveCalls shouldContain success.journalEntry.id
        accountRepository.saveCalls shouldContain wages.id
        accountRepository.saveCalls shouldContain salaries.id
        accountRepository.saveCalls shouldContain cash.id
    }

    @Test
    fun `given only totalWages is positive, when executed, then the Salaries Account is not touched`() {
        val period = openPeriod()
        val wages = account("6000", AccountType.EXPENSE)
        val salaries = account("6010", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)
        val saveCallsBeforeExecute = accountRepository.saveCalls.size

        val result = useCase.execute(request(period.id, wages.id, salaries.id, cash.id, totalWages = "5000.00", totalSalaries = "0.00"))

        val success = result.shouldBeInstanceOf<RecordPayRunResult.Success>()
        success.journalEntry.lines.none { it.accountId == salaries.id } shouldBe true
        accountRepository.saveCalls.drop(saveCallsBeforeExecute).none { it == salaries.id } shouldBe true
    }

    @Test
    fun `given both totals are zero, when executed, then it returns InvalidAmounts`() {
        val period = openPeriod()
        val wages = account("6000", AccountType.EXPENSE)
        val salaries = account("6010", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(request(period.id, wages.id, salaries.id, cash.id, totalWages = "0.00", totalSalaries = "0.00"))

        result.shouldBeInstanceOf<RecordPayRunResult.InvalidAmounts>()
    }

    @Test
    fun `given a negative total, when executed, then it returns InvalidAmounts`() {
        val period = openPeriod()
        val wages = account("6000", AccountType.EXPENSE)
        val salaries = account("6010", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(request(period.id, wages.id, salaries.id, cash.id, totalWages = "-1.00"))

        result.shouldBeInstanceOf<RecordPayRunResult.InvalidAmounts>()
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val wages = account("6000", AccountType.EXPENSE)
        val salaries = account("6010", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(request(PeriodId.generate(), wages.id, salaries.id, cash.id))

        result.shouldBeInstanceOf<RecordPayRunResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val wages = account("6000", AccountType.EXPENSE)
        val salaries = account("6010", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(request(period.id, wages.id, salaries.id, cash.id))

        result.shouldBeInstanceOf<RecordPayRunResult.PeriodNotOpen>()
    }

    @Test
    fun `given a Wages Expense Account that does not exist, when executed, then it returns WagesExpenseAccountNotFound`() {
        val period = openPeriod()
        val salaries = account("6010", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(request(period.id, AccountId.generate(), salaries.id, cash.id))

        result.shouldBeInstanceOf<RecordPayRunResult.WagesExpenseAccountNotFound>()
    }

    @Test
    fun `given a Salaries Expense Account that does not exist, when executed, then it returns SalariesExpenseAccountNotFound`() {
        val period = openPeriod()
        val wages = account("6000", AccountType.EXPENSE)
        val cash = account("1000", AccountType.ASSET)

        val result = useCase.execute(request(period.id, wages.id, AccountId.generate(), cash.id))

        result.shouldBeInstanceOf<RecordPayRunResult.SalariesExpenseAccountNotFound>()
    }

    @Test
    fun `given a Cash Account that does not exist, when executed, then it returns CashAccountNotFound`() {
        val period = openPeriod()
        val wages = account("6000", AccountType.EXPENSE)
        val salaries = account("6010", AccountType.EXPENSE)

        val result = useCase.execute(request(period.id, wages.id, salaries.id, AccountId.generate()))

        result.shouldBeInstanceOf<RecordPayRunResult.CashAccountNotFound>()
    }
}
