package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntryPosted
import com.theprodeogroup.fish.domain.ledger.JournalLine
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
private val TODAY = LocalDate.of(2026, 8, 20)

/** Fakes shared across this package's tests live in `LedgerRepositoryFakes.kt`/`TenancyRepositoryFakes.kt`. */
class PostJournalEntryUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = PostJournalEntryUseCase(periodRepository, accountRepository, journalEntryRepository)

    private val companyId = CompanyId.generate()

    private fun openPeriod(): Period {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
        return period
    }

    private fun account(code: String, name: String, type: AccountType = AccountType.ASSET): Account {
        val classification = if (type.requiresClassification()) AccountClassification.CURRENT else null
        val account = Account.create(companyId, type, classification, code, name)
        accountRepository.save(account)
        return account
    }

    private fun balancedLines(cash: Account, revenue: Account) = listOf(
        JournalLine(cash.id, Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
        JournalLine(revenue.id, Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT)
    )

    @Test
    fun `given a valid request against an Open Period, when executed, then it returns a Posted JournalEntry`() {
        val period = openPeriod()
        val cash = account("1000", "Cash")
        val revenue = account("4000", "Sales", AccountType.REVENUE)

        val result = useCase.execute(
            PostJournalEntryUseCase.Request(period.id, TODAY, balancedLines(cash, revenue), JournalSource.MANUAL)
        )

        val success = result.shouldBeInstanceOf<PostJournalEntryResult.Success>()
        success.entry.status shouldBe PostingStatus.POSTED
        success.entry.periodId shouldBe period.id
        journalEntryRepository.saveCalls shouldContain success.entry.id
    }

    @Test
    fun `given a valid request, when executed, then every referenced Account is marked as having posted activity and saved`() {
        val period = openPeriod()
        val cash = account("1000", "Cash")
        val revenue = account("4000", "Sales", AccountType.REVENUE)

        useCase.execute(PostJournalEntryUseCase.Request(period.id, TODAY, balancedLines(cash, revenue), JournalSource.MANUAL))

        accountRepository.saveCalls shouldContain cash.id
        accountRepository.saveCalls shouldContain revenue.id
        requireNotNull(accountRepository.findById(cash.id)).validateDeletion().isValid shouldBe false
    }

    @Test
    fun `given a valid request, when executed, then JournalEntryPosted is among the returned events`() {
        val period = openPeriod()
        val cash = account("1000", "Cash")
        val revenue = account("4000", "Sales", AccountType.REVENUE)

        val result = useCase.execute(
            PostJournalEntryUseCase.Request(period.id, TODAY, balancedLines(cash, revenue), JournalSource.MANUAL)
        )

        val success = result.shouldBeInstanceOf<PostJournalEntryResult.Success>()
        success.events.map { it::class } shouldContain JournalEntryPosted::class
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val cash = account("1000", "Cash")
        val revenue = account("4000", "Sales", AccountType.REVENUE)

        val result = useCase.execute(
            PostJournalEntryUseCase.Request(PeriodId.generate(), TODAY, balancedLines(cash, revenue), JournalSource.MANUAL)
        )

        result.shouldBeInstanceOf<PostJournalEntryResult.PeriodNotFound>()
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)
        val cash = account("1000", "Cash")
        val revenue = account("4000", "Sales", AccountType.REVENUE)

        val result = useCase.execute(
            PostJournalEntryUseCase.Request(period.id, TODAY, balancedLines(cash, revenue), JournalSource.MANUAL)
        )

        result.shouldBeInstanceOf<PostJournalEntryResult.PeriodNotOpen>()
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given unbalanced lines, when executed, then it returns InvalidLines with a non-empty errors list`() {
        val period = openPeriod()
        val cash = account("1000", "Cash")
        val revenue = account("4000", "Sales", AccountType.REVENUE)
        val unbalanced = listOf(
            JournalLine(cash.id, Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
            JournalLine(revenue.id, Money(BigDecimal("50.00"), GBP), TransactionSide.CREDIT)
        )

        val result = useCase.execute(PostJournalEntryUseCase.Request(period.id, TODAY, unbalanced, JournalSource.MANUAL))

        val invalid = result.shouldBeInstanceOf<PostJournalEntryResult.InvalidLines>()
        invalid.errors.isEmpty() shouldBe false
        journalEntryRepository.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given a line referencing a nonexistent Account, when executed, then it returns AccountNotFound and saves nothing`() {
        val period = openPeriod()
        val cash = account("1000", "Cash")
        val missingAccountId = AccountId.generate()
        val lines = listOf(
            JournalLine(cash.id, Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
            JournalLine(missingAccountId, Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT)
        )
        // account("1000", "Cash") above already called accountRepository.save() once, as
        // fixture setup - snapshot the count here so the assertion below only checks what
        // the use case itself did, not the test's own setup.
        val saveCallsBeforeExecute = accountRepository.saveCalls.size

        val result = useCase.execute(PostJournalEntryUseCase.Request(period.id, TODAY, lines, JournalSource.MANUAL))

        val notFound = result.shouldBeInstanceOf<PostJournalEntryResult.AccountNotFound>()
        notFound.accountId shouldBe missingAccountId
        journalEntryRepository.saveCalls shouldBe emptyList()
        accountRepository.saveCalls.size shouldBe saveCallsBeforeExecute
    }

    @Test
    fun `given a line referencing another Company's Account, when executed, then it returns AccountNotFound and posts nothing`() {
        val period = openPeriod()
        val cash = account("1000", "Cash")

        val otherCompanyId = CompanyId.generate()
        val otherCompanysRevenue = Account.create(otherCompanyId, AccountType.REVENUE, null, "4000", "Sales")
        accountRepository.save(otherCompanysRevenue)

        val lines = listOf(
            JournalLine(cash.id, Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
            JournalLine(otherCompanysRevenue.id, Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT)
        )
        val saveCallsBeforeExecute = accountRepository.saveCalls.size

        val result = useCase.execute(PostJournalEntryUseCase.Request(period.id, TODAY, lines, JournalSource.MANUAL))

        val notFound = result.shouldBeInstanceOf<PostJournalEntryResult.AccountNotFound>()
        notFound.accountId shouldBe otherCompanysRevenue.id
        journalEntryRepository.saveCalls shouldBe emptyList()
        accountRepository.saveCalls.size shouldBe saveCallsBeforeExecute
    }
}
