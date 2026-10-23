package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Currency

/**
 * GL-RF-RV1, the generic journal reversal (Femi via CM, 2026-10-23: "Refuse, point to CBB"). Every journal is reversed, always, with no
 * reconciliation check, EXCEPT an entry made in a cash or bank book, which is corrected in Cash and Bank Books, never in the GL.
 */
class ReverseEntryUseCaseTest {

    private val gbp = Currency.getInstance("GBP")
    private val companies = FakeCompanyRepository()
    private val accounts = FakeAccountRepository()
    private val entries = FakeJournalEntryRepository()
    private val periods = FakePeriodRepository()
    private val useCase = ReverseEntryUseCase(companies, accounts, entries, periods)
    private val now = Instant.parse("2026-10-24T09:00:00Z")

    private val company = Company.create(TenantId.generate(), "Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
    private val period = Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).also { it.open(); periods.save(it) }
    private val expense = Account.create(company.id, AccountType.EXPENSE, null, "5000", "Expense").also { accounts.save(it) }
    private val bank = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank").also { accounts.save(it) }

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun entry(source: JournalSource, description: String? = "Takings", forPeriod: Period = period): JournalEntry =
        JournalEntry.create(
            forPeriod.id, LocalDate.of(2026, 10, 3),
            listOf(JournalLine(expense.id, money("40.00"), TransactionSide.DEBIT), JournalLine(bank.id, money("40.00"), TransactionSide.CREDIT)),
            source, description
        ).also { it.post(); entries.save(it) }

    @Test
    fun `given a posted manual entry, then a reversing entry is posted with the sides swapped and the original is marked reversed`() {
        val original = entry(JournalSource.MANUAL)

        val result = useCase.execute(company.id, original.id, now).shouldBeInstanceOf<ReverseEntryUseCase.Result.Reversed>()

        result.reversal.source shouldBe JournalSource.REVERSAL
        result.reversal.status shouldBe PostingStatus.POSTED
        result.reversal.reversalOfEntryId shouldBe original.id
        result.reversal.lines.map { it.accountId to it.side } shouldBe listOf(expense.id to TransactionSide.CREDIT, bank.id to TransactionSide.DEBIT)
        result.reversal.description shouldBe "Reversal of Takings (2026-10-03)"
        entries.findById(original.id)!!.status shouldBe PostingStatus.REVERSED
        entries.findById(result.reversal.id) shouldBe result.reversal
    }

    @Test
    fun `given entries made by the modules, such as an integration posting or an import, then they are reversed too, always`() {
        for (source in listOf(JournalSource.INTEGRATION, JournalSource.IMPORT, JournalSource.MANUAL)) {
            useCase.execute(company.id, entry(source).id, now).shouldBeInstanceOf<ReverseEntryUseCase.Result.Reversed>()
        }
    }

    @Test
    fun `given an entry made in a cash or bank book, then it is refused and points to Cash and Bank Books, and nothing changes`() {
        val original = entry(JournalSource.CASH_BOOK)
        val saves = entries.saveCalls.size

        useCase.execute(company.id, original.id, now).shouldBeInstanceOf<ReverseEntryUseCase.Result.CorrectInCashAndBankBooks>()

        entries.findById(original.id)!!.status shouldBe PostingStatus.POSTED
        entries.saveCalls.size shouldBe saves
    }

    @Test
    fun `given an entry that is reconciled elsewhere, then GL does not look, there is no reconciliation check`() {
        // GL holds no reconciliation state for this decision (CBB keeps its own); a manual or module entry is reversed whatever bank lines it touches.
        val original = entry(JournalSource.MANUAL, "Matched in a bank reconciliation")

        useCase.execute(company.id, original.id, now).shouldBeInstanceOf<ReverseEntryUseCase.Result.Reversed>()
    }

    @Test
    fun `given the same entry reversed twice, then the second is already reversed and only one reversal exists`() {
        val original = entry(JournalSource.MANUAL)
        val first = useCase.execute(company.id, original.id, now).shouldBeInstanceOf<ReverseEntryUseCase.Result.Reversed>()

        useCase.execute(company.id, original.id, now).shouldBeInstanceOf<ReverseEntryUseCase.Result.AlreadyReversed>()

        entries.findAllByPeriod(period.id).count { it.reversalOfEntryId == original.id } shouldBe 1
        entries.findById(first.reversal.id) shouldBe first.reversal
    }

    @Test
    fun `given an entry that is itself a reversal, or a draft, then it cannot be reversed`() {
        val original = entry(JournalSource.MANUAL)
        val reversal = (useCase.execute(company.id, original.id, now) as ReverseEntryUseCase.Result.Reversed).reversal
        val draft = JournalEntry.create(
            period.id, LocalDate.of(2026, 10, 3),
            listOf(JournalLine(expense.id, money("1.00"), TransactionSide.DEBIT), JournalLine(bank.id, money("1.00"), TransactionSide.CREDIT)),
            JournalSource.MANUAL, "Draft"
        ).also { entries.save(it) }

        useCase.execute(company.id, reversal.id, now).shouldBeInstanceOf<ReverseEntryUseCase.Result.NotReversible>()
        useCase.execute(company.id, draft.id, now).shouldBeInstanceOf<ReverseEntryUseCase.Result.NotReversible>()
    }

    @Test
    fun `given a period that is not open, then it is refused and nothing changes`() {
        val closed = Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).also { periods.save(it) }
        val original = entry(JournalSource.MANUAL, forPeriod = closed)

        useCase.execute(company.id, original.id, now).shouldBeInstanceOf<ReverseEntryUseCase.Result.PeriodNotOpen>()

        entries.findById(original.id)!!.status shouldBe PostingStatus.POSTED
    }

    @Test
    fun `given an entry of another Company, an unknown entry or an unknown Company, then it is not found, never a distinct refusal`() {
        val otherCompany = Company.create(TenantId.generate(), "Other", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
        val otherPeriod = Period.create(otherCompany.id, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).also { it.open(); periods.save(it) }
        val foreign = JournalEntry.create(
            otherPeriod.id, LocalDate.of(2026, 10, 3),
            listOf(JournalLine(expense.id, money("5.00"), TransactionSide.DEBIT), JournalLine(bank.id, money("5.00"), TransactionSide.CREDIT)),
            JournalSource.MANUAL, "Foreign"
        ).also { it.post(); entries.save(it) }

        useCase.execute(company.id, foreign.id, now).shouldBeInstanceOf<ReverseEntryUseCase.Result.EntryNotFound>()
        useCase.execute(company.id, JournalEntryId.generate(), now).shouldBeInstanceOf<ReverseEntryUseCase.Result.EntryNotFound>()
        useCase.execute(CompanyId.generate(), foreign.id, now).shouldBeInstanceOf<ReverseEntryUseCase.Result.CompanyNotFound>()
        entries.findById(foreign.id)!!.status shouldBe PostingStatus.POSTED
    }

    @Test
    fun `given a reversal, then the accounts on its lines record activity`() {
        val original = entry(JournalSource.MANUAL)

        useCase.execute(company.id, original.id, now)

        accounts.findById(expense.id)!!.hasPostedActivity shouldBe true
        accounts.findById(bank.id)!!.hasPostedActivity shouldBe true
    }
}
