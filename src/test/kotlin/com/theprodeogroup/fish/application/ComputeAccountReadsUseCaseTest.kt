package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
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

/**
 * The two reads Cash and Bank Books builds its books and reconciliations from (docs/Cash_And_Bank_Books_Extraction_Assessment.md,
 * Femi 2026-10-19: CBB is its own service): the postings on one account in a date range with the balance before it, and the balance of one
 * account as at a date. Both work for any account of the Company; neither writes anything.
 */
class ComputeAccountReadsUseCaseTest {

    private val companyRepository = FakeCompanyRepository()
    private val periodRepository = FakePeriodRepository()
    private val accountRepository = FakeAccountRepository()
    private val journalEntryRepository = FakeJournalEntryRepository()
    private val postings = ComputeAccountPostingsUseCase(companyRepository, accountRepository, journalEntryRepository, periodRepository)
    private val balance = ComputeAccountBalanceUseCase(companyRepository, accountRepository, journalEntryRepository)

    private val company = Company.create(TenantId.generate(), "Test Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, GBP).also { companyRepository.save(it) }
    private val period = Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).also {
        it.open()
        periodRepository.save(it)
    }

    private fun account(code: String, type: AccountType, name: String = "Account $code", kind: CashBookKind? = null, owner: CompanyId = company.id): Account =
        Account.create(owner, type, if (type.requiresClassification()) AccountClassification.CURRENT else null, code, name, cashBookKind = kind)
            .also { accountRepository.save(it) }

    private val bank = account("1010", AccountType.ASSET, "Current Account", CashBookKind.BANK)
    private val sales = account("4000", AccountType.REVENUE, "Sales")
    private val rent = account("5100", AccountType.EXPENSE, "Rent")

    private fun gbp(amount: String) = Money(BigDecimal(amount), GBP)

    private fun post(date: String, debit: Account, credit: Account, amount: String, description: String? = null, reversalOf: JournalEntry? = null): JournalEntry {
        val entry = JournalEntry.create(
            period.id, LocalDate.parse(date),
            listOf(JournalLine(debit.id, gbp(amount), TransactionSide.DEBIT), JournalLine(credit.id, gbp(amount), TransactionSide.CREDIT)),
            JournalSource.MANUAL, description
        )
        entry.post()
        journalEntryRepository.save(entry)
        return entry
    }

    @Test
    fun `given entries before and inside the range, when postings are read, then the opening net is before the range and only the range is listed in date order`() {
        post("2026-09-20", bank, sales, "100.00")
        post("2026-10-05", bank, sales, "50.00", "second")
        post("2026-10-02", rent, bank, "30.00", "first")
        post("2026-11-03", bank, sales, "999.00") // after the range

        val success = postings.execute(company.id, bank.id, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
            .shouldBeInstanceOf<ComputeAccountPostingsUseCase.Result.Success>()

        success.openingNet shouldBe gbp("100.00")
        success.postings.map { it.entry.description } shouldBe listOf("first", "second")
        success.postings.first().entry.lines.size shouldBe 2
        success.accountsById.keys.containsAll(listOf(bank.id, sales.id, rent.id)) shouldBe true
        success.currency shouldBe GBP
    }

    @Test
    fun `given a reversed entry, when postings are read, then the original shows what reversed it and the reversal is its own posting`() {
        val original = post("2026-10-02", bank, sales, "40.00", "wrong")
        val reversal = original.reverse(now = java.time.Instant.parse("2026-10-03T10:00:00Z"))!!
        journalEntryRepository.recordReversal(original, reversal)

        val success = postings.execute(company.id, bank.id, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
            .shouldBeInstanceOf<ComputeAccountPostingsUseCase.Result.Success>()

        success.postings.single { it.entry.id == original.id }.reversedByEntryId shouldBe reversal.id
        success.postings.single { it.entry.id == reversal.id }.entry.reversalOfEntryId shouldBe original.id
    }

    @Test
    fun `given a closed period, when postings are read, then the posting says its period is not open`() {
        post("2026-10-02", bank, sales, "10.00")
        period.close()
        periodRepository.save(period)

        postings.execute(company.id, bank.id, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
            .shouldBeInstanceOf<ComputeAccountPostingsUseCase.Result.Success>().postings.single().periodOpen shouldBe false
    }

    @Test
    fun `given a missing Company, an account of another Company or an inverted range, when postings are read, then the matching refusal`() {
        val foreign = account("1010", AccountType.ASSET, owner = CompanyId.generate())

        postings.execute(CompanyId.generate(), bank.id, null, null).shouldBeInstanceOf<ComputeAccountPostingsUseCase.Result.CompanyNotFound>()
        postings.execute(company.id, foreign.id, null, null).shouldBeInstanceOf<ComputeAccountPostingsUseCase.Result.AccountNotFound>()
        postings.execute(company.id, AccountId.generate(), null, null).shouldBeInstanceOf<ComputeAccountPostingsUseCase.Result.AccountNotFound>()
        postings.execute(company.id, bank.id, LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 1)).shouldBeInstanceOf<ComputeAccountPostingsUseCase.Result.InvalidRange>()
    }

    @Test
    fun `given a debit account, when the balance is read as at a date, then it is the net of entries on or before that date with the side it falls on`() {
        post("2026-10-02", bank, sales, "100.00")
        post("2026-10-10", rent, bank, "30.00")
        post("2026-10-20", bank, sales, "500.00")

        val asOfTheTenth = balance.execute(company.id, bank.id, LocalDate.of(2026, 10, 10)).shouldBeInstanceOf<ComputeAccountBalanceUseCase.Result.Success>()
        asOfTheTenth.balance shouldBe gbp("70.00")
        asOfTheTenth.side shouldBe TransactionSide.DEBIT

        val overdrawn = balance.execute(company.id, rent.id, LocalDate.of(2026, 10, 1)).shouldBeInstanceOf<ComputeAccountBalanceUseCase.Result.Success>()
        overdrawn.balance shouldBe gbp("0.00")
        overdrawn.side shouldBe TransactionSide.DEBIT // an expense account's normal side
    }

    @Test
    fun `given a credit that exceeds the debits, when the balance is read, then it is positive on the credit side`() {
        post("2026-10-02", rent, bank, "80.00")

        val result = balance.execute(company.id, bank.id, LocalDate.of(2026, 10, 31)).shouldBeInstanceOf<ComputeAccountBalanceUseCase.Result.Success>()

        result.balance shouldBe gbp("80.00")
        result.side shouldBe TransactionSide.CREDIT
    }

    @Test
    fun `given a missing Company or an account of another Company, when the balance is read, then the matching refusal`() {
        val foreign = account("1010", AccountType.ASSET, owner = CompanyId.generate())

        balance.execute(CompanyId.generate(), bank.id, null).shouldBeInstanceOf<ComputeAccountBalanceUseCase.Result.CompanyNotFound>()
        balance.execute(company.id, foreign.id, null).shouldBeInstanceOf<ComputeAccountBalanceUseCase.Result.AccountNotFound>()
    }
}
