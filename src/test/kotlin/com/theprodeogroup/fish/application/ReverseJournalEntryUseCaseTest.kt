package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
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
class ReverseJournalEntryUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = ReverseJournalEntryUseCase(journalEntryRepository, periodRepository, accountRepository)

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

    private fun postedEntry(periodId: PeriodId, cash: Account, revenue: Account): JournalEntry {
        val entry = JournalEntry.create(
            periodId, TODAY,
            listOf(
                JournalLine(cash.id, Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
                JournalLine(revenue.id, Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        entry.post()
        journalEntryRepository.save(entry)
        return entry
    }

    @Test
    fun `given a Posted entry in an Open Period, when executed, then it returns a Reversed original and a Posted reversal`() {
        val period = openPeriod()
        val cash = account("1000", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val original = postedEntry(period.id, cash, revenue)

        val result = useCase.execute(original.id)

        val success = result.shouldBeInstanceOf<ReverseJournalEntryResult.Success>()
        success.originalEntry.status shouldBe PostingStatus.REVERSED
        success.reversalEntry.status shouldBe PostingStatus.POSTED
        success.reversalEntry.reversalOfEntryId shouldBe original.id
    }

    @Test
    fun `given the reversal succeeds, then the reversal lines are the mirror image of the original`() {
        val period = openPeriod()
        val cash = account("1000", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val original = postedEntry(period.id, cash, revenue)

        val result = useCase.execute(original.id)

        val success = result.shouldBeInstanceOf<ReverseJournalEntryResult.Success>()
        success.reversalEntry.lines.map { it.accountId to it.side } shouldBe
            original.lines.map { it.accountId to it.side.opposite() }
    }

    @Test
    fun `given the reversal succeeds, then JournalEntryPosted is among the returned events`() {
        val period = openPeriod()
        val cash = account("1000", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val original = postedEntry(period.id, cash, revenue)

        val result = useCase.execute(original.id)

        val success = result.shouldBeInstanceOf<ReverseJournalEntryResult.Success>()
        success.events.map { it::class } shouldContain JournalEntryPosted::class
    }

    @Test
    fun `given the reversal succeeds, then both entries and every referenced Account are saved`() {
        val period = openPeriod()
        val cash = account("1000", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val original = postedEntry(period.id, cash, revenue)

        val result = useCase.execute(original.id)

        val success = result.shouldBeInstanceOf<ReverseJournalEntryResult.Success>()
        journalEntryRepository.saveCalls shouldContain original.id
        journalEntryRepository.saveCalls shouldContain success.reversalEntry.id
        accountRepository.saveCalls shouldContain cash.id
        accountRepository.saveCalls shouldContain revenue.id
    }

    @Test
    fun `given a nonexistent JournalEntry id, when executed, then it returns EntryNotFound`() {
        val result = useCase.execute(JournalEntryId.generate())

        result.shouldBeInstanceOf<ReverseJournalEntryResult.EntryNotFound>()
    }

    @Test
    fun `given the entry's Period is Closed, when executed, then it returns PeriodNotOpen and nothing is mutated`() {
        val period = openPeriod()
        val cash = account("1000", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val original = postedEntry(period.id, cash, revenue)
        period.close()
        periodRepository.save(period)

        val result = useCase.execute(original.id)

        result.shouldBeInstanceOf<ReverseJournalEntryResult.PeriodNotOpen>()
        journalEntryRepository.findById(original.id)?.status shouldBe PostingStatus.POSTED
    }

    @Test
    fun `given a Draft entry never posted, when executed, then it returns EntryNotReversible`() {
        val period = openPeriod()
        val cash = account("1000", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val draft = JournalEntry.create(
            period.id, TODAY,
            listOf(
                JournalLine(cash.id, Money(BigDecimal("100.00"), GBP), TransactionSide.DEBIT),
                JournalLine(revenue.id, Money(BigDecimal("100.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        journalEntryRepository.save(draft)

        val result = useCase.execute(draft.id)

        result.shouldBeInstanceOf<ReverseJournalEntryResult.EntryNotReversible>()
    }

    @Test
    fun `given an already-Reversed entry, when executed again, then it returns EntryNotReversible`() {
        val period = openPeriod()
        val cash = account("1000", AccountType.ASSET)
        val revenue = account("4000", AccountType.REVENUE)
        val original = postedEntry(period.id, cash, revenue)
        useCase.execute(original.id)

        val result = useCase.execute(original.id)

        result.shouldBeInstanceOf<ReverseJournalEntryResult.EntryNotReversible>()
    }
}
