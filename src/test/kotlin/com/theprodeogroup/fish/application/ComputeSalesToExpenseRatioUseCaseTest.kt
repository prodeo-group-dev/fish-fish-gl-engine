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
import com.theprodeogroup.fish.domain.common.Jurisdiction
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
class ComputeSalesToExpenseRatioUseCaseTest {

    private val companyRepository = FakeCompanyRepository()
    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = ComputeSalesToExpenseRatioUseCase(companyRepository, periodRepository, accountRepository, journalEntryRepository)

    private val company = Company.create(TenantId.generate(), "Purse UK", ClientType.NON_PROFIT, Jurisdiction.UK, GBP)
        .also { companyRepository.save(it) }

    private fun openPeriod(startDate: LocalDate = TODAY.minusDays(10)): Period {
        val period = Period.create(company.id, PeriodType.MONTH, startDate, startDate.plusMonths(1))
        period.open()
        periodRepository.save(period)
        return period
    }

    private fun account(type: AccountType, expenseClassification: ExpenseClassification? = null): Account {
        val account = Account.create(company.id, type, null, "4000", "Test Account", expenseClassification = expenseClassification)
        accountRepository.save(account)
        return account
    }

    private fun postedEntry(periodId: PeriodId, vararg lines: JournalLine): JournalEntry {
        val entry = JournalEntry.create(periodId, TODAY, lines.toList(), JournalSource.MANUAL)
        entry.post()
        return entry
    }

    @Test
    fun `given sales twice the operating expense, when executed, then the ratio is 2`() {
        val period = openPeriod()
        val revenue = account(AccountType.REVENUE)
        val admin = account(AccountType.EXPENSE, ExpenseClassification.ADMINISTRATIVE)
        journalEntryRepository.save(
            postedEntry(
                period.id,
                JournalLine(AccountId.generate(), Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT),
                JournalLine(revenue.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)
            )
        )
        journalEntryRepository.save(
            postedEntry(
                period.id,
                JournalLine(admin.id, Money(BigDecimal("500.00"), GBP), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("500.00"), GBP), TransactionSide.CREDIT)
            )
        )

        val result = useCase.execute(company.id)

        val success = result.shouldBeInstanceOf<ComputeSalesToExpenseRatioUseCase.Result.Success>()
        success.totalRevenue shouldBe Money(BigDecimal("1000.00"), GBP)
        success.operatingExpense shouldBe Money(BigDecimal("500.00"), GBP)
        success.ratio shouldBe BigDecimal("2")
    }

    @Test
    fun `given Cost of Goods Sold posted alongside operating expense, when executed, then COGS is excluded from the ratio`() {
        val period = openPeriod()
        val revenue = account(AccountType.REVENUE)
        val admin = account(AccountType.EXPENSE, ExpenseClassification.ADMINISTRATIVE)
        val cogs = account(AccountType.EXPENSE, ExpenseClassification.COST_OF_GOODS_SOLD)
        journalEntryRepository.save(
            postedEntry(
                period.id,
                JournalLine(AccountId.generate(), Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT),
                JournalLine(revenue.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)
            )
        )
        journalEntryRepository.save(
            postedEntry(
                period.id,
                JournalLine(admin.id, Money(BigDecimal("250.00"), GBP), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("250.00"), GBP), TransactionSide.CREDIT)
            )
        )
        journalEntryRepository.save(
            postedEntry(
                period.id,
                JournalLine(cogs.id, Money(BigDecimal("5000.00"), GBP), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("5000.00"), GBP), TransactionSide.CREDIT)
            )
        )

        val result = useCase.execute(company.id)

        val success = result.shouldBeInstanceOf<ComputeSalesToExpenseRatioUseCase.Result.Success>()
        success.operatingExpense shouldBe Money(BigDecimal("250.00"), GBP)
        success.ratio shouldBe BigDecimal("4")
    }

    @Test
    fun `given no operating expense posted yet, when executed, then it returns NoOperatingExpenseYet`() {
        val period = openPeriod()
        val revenue = account(AccountType.REVENUE)
        journalEntryRepository.save(
            postedEntry(
                period.id,
                JournalLine(AccountId.generate(), Money(BigDecimal("1000.00"), GBP), TransactionSide.DEBIT),
                JournalLine(revenue.id, Money(BigDecimal("1000.00"), GBP), TransactionSide.CREDIT)
            )
        )

        val result = useCase.execute(company.id)

        result.shouldBeInstanceOf<ComputeSalesToExpenseRatioUseCase.Result.NoOperatingExpenseYet>()
    }

    @Test
    fun `given a nonexistent Company, when executed, then it returns CompanyNotFound`() {
        val result = useCase.execute(CompanyId.generate())

        result.shouldBeInstanceOf<ComputeSalesToExpenseRatioUseCase.Result.CompanyNotFound>()
    }

    @Test
    fun `given a Company with no open Period, when executed, then it returns NoOpenPeriod`() {
        val result = useCase.execute(company.id)

        result.shouldBeInstanceOf<ComputeSalesToExpenseRatioUseCase.Result.NoOpenPeriod>()
    }

    @Test
    fun `given a Company with an open Period but no Accounts at all, when executed, then it returns NoAccountsForCompany`() {
        openPeriod()

        val result = useCase.execute(company.id)

        result.shouldBeInstanceOf<ComputeSalesToExpenseRatioUseCase.Result.NoAccountsForCompany>()
    }
}
