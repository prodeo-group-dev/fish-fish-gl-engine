package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * ME-FX-3 (docs/Multi_Entity_Accounting_Playbook.md, 2026-10-22): no new journal entry may carry a line in a currency other than
 * the Company's. One guard at the point every posting use case saves through, so no route can forget it.
 */
class CompanyCurrencyGuardedJournalEntryRepositoryTest {

    private val gbp = Currency.getInstance("GBP")
    private val usd = Currency.getInstance("USD")
    private val companies = FakeCompanyRepository()
    private val periods = FakePeriodRepository()
    private val delegate = FakeJournalEntryRepository()
    private val guarded = CompanyCurrencyGuardedJournalEntryRepository(delegate, periods, companies)

    private val company = Company.create(TenantId.generate(), "Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
    private val period = Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).also { it.open(); periods.save(it) }

    private fun entry(currency: Currency, vararg extra: JournalLine): JournalEntry {
        val lines = listOf(
            JournalLine(AccountId.generate(), Money(BigDecimal("10.00"), currency), TransactionSide.DEBIT),
            JournalLine(AccountId.generate(), Money(BigDecimal("10.00"), currency), TransactionSide.CREDIT)
        ) + extra
        return JournalEntry.create(period.id, LocalDate.of(2026, 10, 5), lines, JournalSource.MANUAL, "test")
    }

    @Test
    fun `given a new entry in the Company's currency, when saved, then it is stored`() {
        val e = entry(gbp)

        guarded.save(e)

        delegate.findById(e.id) shouldBe e
    }

    @Test
    fun `given a new entry with a line in another currency, when saved, then it is refused naming the line and both currencies, and nothing is stored`() {
        val e = entry(usd)

        val refused = shouldThrow<CurrencyNotCompanyCurrencyException> { guarded.save(e) }

        refused.lineIndex shouldBe 0
        refused.lineCurrency shouldBe usd
        refused.companyCurrency shouldBe gbp
        refused.message!! shouldContain "USD"
        refused.message!! shouldContain "GBP"
        delegate.findById(e.id) shouldBe null
        delegate.saveCalls shouldBe emptyList()
    }

    @Test
    fun `given one good line and one foreign line, when saved, then the foreign line is the one named`() {
        val e = JournalEntry.create(
            period.id, LocalDate.of(2026, 10, 5),
            listOf(
                JournalLine(AccountId.generate(), Money(BigDecimal("10.00"), gbp), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("10.00"), gbp), TransactionSide.CREDIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("5.00"), usd), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("5.00"), usd), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL, "mixed"
        )

        shouldThrow<CurrencyNotCompanyCurrencyException> { guarded.save(e) }.lineIndex shouldBe 2
    }

    @Test
    fun `given a foreign entry that is already stored, when it is saved again after a status change, then it is allowed`() {
        val legacy = entry(usd)
        delegate.save(legacy)
        legacy.post()

        guarded.save(legacy)

        delegate.findById(legacy.id) shouldBe legacy
    }

    @Test
    fun `given a reversal of a stored foreign entry, when recorded, then the clean-up path is allowed`() {
        val legacy = entry(usd)
        legacy.post()
        delegate.save(legacy)
        val reversal = legacy.reverse()!!

        guarded.recordReversal(legacy, reversal) shouldBe true
    }

    @Test
    fun `given an entry stored only if absent with a foreign line, when inserted, then it is refused`() {
        val e = entry(usd)

        shouldThrow<CurrencyNotCompanyCurrencyException> { guarded.insertIfAbsent(e) }

        delegate.findById(e.id) shouldBe null
    }

    @Test
    fun `given a period the guard cannot resolve, when saved, then it defers to the delegate and does not invent a refusal`() {
        val e = JournalEntry.create(
            com.theprodeogroup.fish.domain.ledger.PeriodId.generate(), LocalDate.of(2026, 10, 5),
            listOf(
                JournalLine(AccountId.generate(), Money(BigDecimal("1.00"), usd), TransactionSide.DEBIT),
                JournalLine(AccountId.generate(), Money(BigDecimal("1.00"), usd), TransactionSide.CREDIT)
            ),
            JournalSource.MANUAL, "orphan"
        )

        guarded.save(e)

        delegate.findById(e.id) shouldBe e
    }

    @Test
    fun `given a reversal of a stored foreign entry, when saved with save, then it is allowed`() {
        val legacy = entry(usd)
        legacy.post()
        delegate.save(legacy)
        val reversal = legacy.reverse()!!

        guarded.save(reversal)

        delegate.findById(reversal.id) shouldBe reversal
    }
}
