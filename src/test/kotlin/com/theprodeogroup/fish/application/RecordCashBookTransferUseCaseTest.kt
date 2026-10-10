package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
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
import java.time.LocalDate
import java.util.Currency
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Moving money between two cash or bank books (docs/GL_Cash_And_Bank_Books_SRS.md, FR-CB24). */
class RecordCashBookTransferUseCaseTest {

    private val gbp = Currency.getInstance("GBP")
    private val today = LocalDate.of(2026, 10, 12)
    private val companies = FakeCompanyRepository()
    private val accounts = FakeAccountRepository()
    private val periods = FakePeriodRepository()
    private val entries = FakeJournalEntryRepository()
    private val useCase = RecordCashBookTransferUseCase(companies, accounts, periods, entries, PostJournalEntryUseCase(periods, accounts, entries))

    private val company = Company.create(TenantId.generate(), "Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }

    init {
        Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).also { it.open(); periods.save(it) }
    }

    private fun asset(code: String, kind: CashBookKind?) =
        Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, code, "Acct $code", cashBookKind = kind).also { accounts.save(it) }

    private val cash = asset("1000", CashBookKind.CASH)
    private val bank = asset("1010", CashBookKind.BANK)
    private val sales = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales").also { accounts.save(it) }

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun fund(account: Account, amount: String) {
        JournalEntry.create(
            periods.findAllByCompany(company.id).first().id, today,
            listOf(JournalLine(account.id, money(amount), TransactionSide.DEBIT), JournalLine(sales.id, money(amount), TransactionSide.CREDIT)),
            JournalSource.MANUAL
        ).also { it.post(); entries.save(it) }
    }

    private fun request(
        from: Account = cash, to: Account = bank, amount: String = "40.00",
        entryId: JournalEntryId = JournalEntryId.generate(), description: String? = "Banked the takings"
    ) = RecordCashBookTransferUseCase.Request(company.id, from.id, to.id, BigDecimal(amount), today, description, entryId)

    private fun posted(r: RecordCashBookTransferUseCase.Result) = r.shouldBeInstanceOf<RecordCashBookTransferUseCase.Result.Posted>()

    @Test
    fun `given cash in the till, when it is moved to the bank, then one entry credits cash and debits the bank and both books show it`() {
        fund(cash, "100.00")

        val result = posted(useCase.execute(request(amount = "40.00")))

        result.entry.source shouldBe JournalSource.CASH_BOOK
        result.entry.lines.map { it.accountId to it.side } shouldBe listOf(bank.id to TransactionSide.DEBIT, cash.id to TransactionSide.CREDIT)
        result.fromBalanceAfter shouldBe money("60.00")
        result.toBalanceAfter shouldBe money("40.00")
        result.warnings shouldBe emptyList()
        entries.findAllByAccount(cash.id).size shouldBe 2
        entries.findAllByAccount(bank.id).size shouldBe 1
    }

    @Test
    fun `given a transfer that takes the paying book below zero, then it posts with a warning for that book`() {
        val result = posted(useCase.execute(request(from = bank, to = cash, amount = "25.00")))

        result.warnings shouldBe listOf(RecordCashBookEntryUseCase.Warning("bank_overdrawn", bank.id, money("-25.00")))
    }

    @Test
    fun `given the same account on both sides, or an account that is not a cash or bank book, then it is refused`() {
        val receivables = asset("1100", null)

        useCase.execute(request(from = cash, to = cash)) shouldBe RecordCashBookTransferUseCase.Result.SameAccount
        useCase.execute(request(from = cash, to = receivables)) shouldBe RecordCashBookTransferUseCase.Result.NotACashBook
        useCase.execute(request(from = receivables, to = bank)) shouldBe RecordCashBookTransferUseCase.Result.NotACashBook
        entries.findAllByAccount(cash.id).size shouldBe 0
    }

    @Test
    fun `given a book of another Company, then it is not found`() {
        val other = Company.create(TenantId.generate(), "Other", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
        val foreign = Account.create(other.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH).also { accounts.save(it) }

        useCase.execute(request(to = foreign)) shouldBe RecordCashBookTransferUseCase.Result.AccountNotFound
        useCase.execute(request(from = foreign)) shouldBe RecordCashBookTransferUseCase.Result.AccountNotFound
    }

    @Test
    fun `given a bad amount, then it is refused`() {
        useCase.execute(request(amount = "0.00")) shouldBe RecordCashBookTransferUseCase.Result.InvalidAmount
        useCase.execute(request(amount = "-1.00")) shouldBe RecordCashBookTransferUseCase.Result.InvalidAmount
        useCase.execute(request(amount = "1.001")) shouldBe RecordCashBookTransferUseCase.Result.InvalidAmount
    }

    @Test
    fun `given the same request sent again with the same entry id, then it is a replay and one entry exists`() {
        fund(cash, "100.00")
        val id = JournalEntryId.generate()
        posted(useCase.execute(request(entryId = id)))

        val again = useCase.execute(request(entryId = id)).shouldBeInstanceOf<RecordCashBookTransferUseCase.Result.Replay>()

        again.entry.id shouldBe id
        entries.findAllByAccount(bank.id).size shouldBe 1
        again.toBalanceAfter shouldBe money("40.00")
    }

    @Test
    fun `given the same entry id with a different amount or direction, then it is key reuse and nothing changes`() {
        val id = JournalEntryId.generate()
        useCase.execute(request(entryId = id, amount = "40.00"))

        useCase.execute(request(entryId = id, amount = "41.00")) shouldBe RecordCashBookTransferUseCase.Result.KeyReused
        useCase.execute(request(entryId = id, from = bank, to = cash)) shouldBe RecordCashBookTransferUseCase.Result.KeyReused
        entries.findAllByAccount(bank.id).size shouldBe 1
    }

    @Test
    fun `given a refused transfer with a key, then the key is not spent`() {
        val id = JournalEntryId.generate()

        useCase.execute(request(entryId = id, from = cash, to = cash)) shouldBe RecordCashBookTransferUseCase.Result.SameAccount

        posted(useCase.execute(request(entryId = id)))
    }

    @Test
    fun `given two simultaneous identical transfers, then exactly one is posted`() {
        val id = JournalEntryId.generate()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val futures = (1..8).map { pool.submit<RecordCashBookTransferUseCase.Result> { start.await(); useCase.execute(request(entryId = id)) } }
        start.countDown()
        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        results.count { it is RecordCashBookTransferUseCase.Result.Posted } shouldBe 1
        results.count { it is RecordCashBookTransferUseCase.Result.Replay } shouldBe 7
        entries.findAllByAccount(bank.id).size shouldBe 1
    }

    @Test
    fun `given no open Period, then it is refused`() {
        val closed = Company.create(TenantId.generate(), "Closed", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
        val a = Account.create(closed.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH).also { accounts.save(it) }
        val b = Account.create(closed.id, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank", cashBookKind = CashBookKind.BANK).also { accounts.save(it) }

        useCase.execute(RecordCashBookTransferUseCase.Request(closed.id, a.id, b.id, BigDecimal("5.00"), today, null, JournalEntryId.generate())) shouldBe
            RecordCashBookTransferUseCase.Result.NoOpenPeriod
    }

    @Test
    fun `given an unknown Company, then it is not found`() {
        useCase.execute(request().copy(companyId = CompanyId.generate())) shouldBe RecordCashBookTransferUseCase.Result.CompanyNotFound
    }
}
