package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodStatus
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodClosed
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
class ClosePeriodUseCaseTest {

    private val periodRepository = FakePeriodRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val useCase = ClosePeriodUseCase(periodRepository, journalEntryRepository)

    private val companyId = CompanyId.generate()

    private fun openPeriod(): Period {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        period.open()
        periodRepository.save(period)
        return period
    }

    private fun draftEntry(periodId: PeriodId): JournalEntry {
        val entry = JournalEntry.create(
            periodId, TODAY,
            listOf(
                JournalLine(AccountId.generate(), Money(BigDecimal("50.00"), GBP), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("50.00"), GBP), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL
        )
        journalEntryRepository.save(entry)
        return entry
    }

    private fun postedEntry(periodId: PeriodId): JournalEntry {
        val entry = draftEntry(periodId)
        entry.post()
        journalEntryRepository.save(entry)
        return entry
    }

    @Test
    fun `given an Open Period with no outstanding entries, when executed, then it returns a Closed Period`() {
        val period = openPeriod()

        val result = useCase.execute(period.id)

        val success = result.shouldBeInstanceOf<ClosePeriodResult.Success>()
        success.period.status shouldBe PeriodStatus.CLOSED
    }

    @Test
    fun `given an Open Period with only Posted entries, when executed, then it succeeds`() {
        val period = openPeriod()
        postedEntry(period.id)

        val result = useCase.execute(period.id)

        result.shouldBeInstanceOf<ClosePeriodResult.Success>()
    }

    @Test
    fun `given a successful close, then PeriodClosed is among the returned events`() {
        val period = openPeriod()

        val result = useCase.execute(period.id)

        val success = result.shouldBeInstanceOf<ClosePeriodResult.Success>()
        success.events.map { it::class } shouldContain PeriodClosed::class
    }

    @Test
    fun `given a nonexistent Period id, when executed, then it returns PeriodNotFound`() {
        val result = useCase.execute(PeriodId.generate())

        result.shouldBeInstanceOf<ClosePeriodResult.PeriodNotFound>()
    }

    @Test
    fun `given a Draft Period never opened, when executed, then it returns PeriodNotOpen`() {
        val period = Period.create(companyId, PeriodType.MONTH, TODAY, TODAY.plusDays(30))
        periodRepository.save(period)

        val result = useCase.execute(period.id)

        result.shouldBeInstanceOf<ClosePeriodResult.PeriodNotOpen>()
    }

    @Test
    fun `given an already Closed Period, when executed again, then it returns PeriodNotOpen`() {
        val period = openPeriod()
        period.close()
        periodRepository.save(period)

        val result = useCase.execute(period.id)

        result.shouldBeInstanceOf<ClosePeriodResult.PeriodNotOpen>()
    }

    @Test
    fun `given a Draft JournalEntry still in the Period, when executed, then it returns UnresolvedEntriesExist naming it`() {
        val period = openPeriod()
        val draft = draftEntry(period.id)

        val result = useCase.execute(period.id)

        val unresolved = result.shouldBeInstanceOf<ClosePeriodResult.UnresolvedEntriesExist>()
        unresolved.entryIds shouldContain draft.id
    }

    @Test
    fun `given unresolved entries exist, when executed, then the Period is not saved as Closed`() {
        val period = openPeriod()
        draftEntry(period.id)

        useCase.execute(period.id)

        periodRepository.findById(period.id)?.status shouldBe PeriodStatus.OPEN
    }
}
