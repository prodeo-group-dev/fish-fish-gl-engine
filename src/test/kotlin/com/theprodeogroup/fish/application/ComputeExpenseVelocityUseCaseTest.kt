package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.ExpenseClassification
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 8, 27)

/** Fakes shared across this package's tests live in `LedgerRepositoryFakes.kt`/`TenancyRepositoryFakes.kt`. */
class ComputeExpenseVelocityUseCaseTest {

    private val companyRepository = FakeCompanyRepository()
    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = ComputeExpenseVelocityUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)

    private val company = Company.create(TenantId.generate(), "Purse UK", ClientType.NON_PROFIT, "GB", GBP)
        .also { companyRepository.save(it) }

    private fun openPeriod(startDate: LocalDate = TODAY.minusDays(10)): Period {
        val period = Period.create(company.id, PeriodType.MONTH, startDate, startDate.plusMonths(1))
        period.open()
        periodRepository.save(period)
        return period
    }

    private fun account(type: AccountType, expenseClassification: ExpenseClassification? = null): Account {
        val account = Account.create(company.id, type, null, "5000", "Test Account", expenseClassification = expenseClassification)
        accountRepository.save(account)
        return account
    }

    private fun postedEntry(periodId: PeriodId, vararg lines: JournalLine): JournalEntry {
        val entry = JournalEntry.create(periodId, TODAY, lines.toList(), JournalSource.MANUAL)
        entry.post()
        return entry
    }

    @Test
    fun `given operating expenses only, when executed, then the daily rate is the full total divided by days elapsed`() {
        val period = openPeriod(startDate = TODAY.minusDays(10))
        val admin = account(AccountType.EXPENSE, ExpenseClassification.ADMINISTRATIVE)
        journalEntryRepository.save(
            postedEntry(
                period.id,
                JournalLine(admin.id, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
            )
        )

        val result = useCase.execute(company.id, asOf = TODAY)

        val success = result.shouldBeInstanceOf<ComputeExpenseVelocityUseCase.Result.Success>()
        success.operatingExpense shouldBe Money(BigDecimal("500.00"), GBP)
        success.daysElapsed shouldBe 10L
        success.dailyRate shouldBe Money(BigDecimal("50.00"), GBP)
    }

    @Test
    fun `given both Cost of Goods Sold and operating expenses, when executed, then COGS is excluded from the rate`() {
        val period = openPeriod(startDate = TODAY.minusDays(5))
        val cogs = account(AccountType.EXPENSE, ExpenseClassification.COST_OF_GOODS_SOLD)
        val sellingDistribution = account(AccountType.EXPENSE, ExpenseClassification.SELLING_DISTRIBUTION)
        journalEntryRepository.save(
            postedEntry(
                period.id,
                JournalLine(cogs.id, Money(BigDecimal("2000.00"), GBP), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("2000.00"), GBP), TransactionSide.CREDIT)
            )
        )
        journalEntryRepository.save(
            postedEntry(
                period.id,
                JournalLine(sellingDistribution.id, Money(BigDecimal("250.00"), GBP), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("250.00"), GBP), TransactionSide.CREDIT)
            )
        )

        val result = useCase.execute(company.id, asOf = TODAY)

        val success = result.shouldBeInstanceOf<ComputeExpenseVelocityUseCase.Result.Success>()
        success.operatingExpense shouldBe Money(BigDecimal("250.00"), GBP)
        success.dailyRate shouldBe Money(BigDecimal("50.00"), GBP)
    }

    @Test
    fun `given an unclassified Expense account, when executed, then it counts as an operating expense`() {
        val period = openPeriod(startDate = TODAY.minusDays(5))
        val unclassified = account(AccountType.EXPENSE)
        journalEntryRepository.save(
            postedEntry(
                period.id,
                JournalLine(unclassified.id, Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT)
            )
        )

        val result = useCase.execute(company.id, asOf = TODAY)

        val success = result.shouldBeInstanceOf<ComputeExpenseVelocityUseCase.Result.Success>()
        success.operatingExpense shouldBe Money(BigDecimal("100.00"), GBP)
    }

    @Test
    fun `given day one of the Period, when executed, then daysElapsed is clamped to 1 - not divided by zero`() {
        val period = openPeriod(startDate = TODAY)
        val admin = account(AccountType.EXPENSE, ExpenseClassification.ADMINISTRATIVE)
        journalEntryRepository.save(
            postedEntry(
                period.id,
                JournalLine(admin.id, Money(BigDecimal("30.00"), GBP), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("30.00"), GBP), TransactionSide.CREDIT)
            )
        )

        val result = useCase.execute(company.id, asOf = TODAY)

        val success = result.shouldBeInstanceOf<ComputeExpenseVelocityUseCase.Result.Success>()
        success.daysElapsed shouldBe 1L
        success.dailyRate shouldBe Money(BigDecimal("30.00"), GBP)
    }

    @Test
    fun `given a nonexistent Company, when executed, then it returns CompanyNotFound`() {
        val result = useCase.execute(CompanyId.generate())

        result.shouldBeInstanceOf<ComputeExpenseVelocityUseCase.Result.CompanyNotFound>()
    }

    @Test
    fun `given a Company with no open Period, when executed, then it returns NoOpenPeriod`() {
        val result = useCase.execute(company.id)

        result.shouldBeInstanceOf<ComputeExpenseVelocityUseCase.Result.NoOpenPeriod>()
    }

    @Test
    fun `given a Company with an open Period but no Accounts at all, when executed, then it returns NoAccountsForCompany`() {
        openPeriod()

        val result = useCase.execute(company.id)

        result.shouldBeInstanceOf<ComputeExpenseVelocityUseCase.Result.NoAccountsForCompany>()
    }
}
