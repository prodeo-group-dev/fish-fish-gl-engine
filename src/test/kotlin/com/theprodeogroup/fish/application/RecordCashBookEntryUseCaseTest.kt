package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.CashDirection
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.UseInstead
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

/** Recording money in and out in a cash or bank book (docs/GL_Cash_And_Bank_Books_SRS.md, FR-CB20 to CB26, Release B). */
class RecordCashBookEntryUseCaseTest {

    private val gbp = Currency.getInstance("GBP")
    private val today = LocalDate.of(2026, 10, 12)
    private val companies = FakeCompanyRepository()
    private val accounts = FakeAccountRepository()
    private val periods = FakePeriodRepository()
    private val entries = FakeJournalEntryRepository()
    private val post = PostJournalEntryUseCase(periods, accounts, entries)
    private val useCase = RecordCashBookEntryUseCase(companies, accounts, periods, entries, post)

    private val company = Company.create(TenantId.generate(), "Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
    private val period = Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).also { it.open(); periods.save(it) }

    private fun asset(code: String, kind: CashBookKind?) =
        Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, code, "Acct $code", cashBookKind = kind).also { accounts.save(it) }

    private val cash = asset("1000", CashBookKind.CASH)
    private val bank = asset("1010", CashBookKind.BANK)
    private val sales = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales").also { accounts.save(it) }
    private val rent = Account.create(company.id, AccountType.EXPENSE, null, "5000", "Rent").also { accounts.save(it) }

    private fun request(
        book: Account = cash,
        direction: CashDirection = CashDirection.RECEIVED,
        amount: String = "100.00",
        counter: Account = sales,
        entryId: JournalEntryId = JournalEntryId.generate(),
        description: String? = "Takings",
        activity: CashFlowActivity = CashFlowActivity.OPERATING
    ) = RecordCashBookEntryUseCase.Request(company.id, book.id, direction, BigDecimal(amount), today, counter.id, description, activity, entryId)

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun posted(result: RecordCashBookEntryUseCase.Result) = result.shouldBeInstanceOf<RecordCashBookEntryUseCase.Result.Posted>()

    @Test
    fun `given a receipt, then a balanced CASH_BOOK entry is posted debiting the book, with the balance after and no warning`() {
        val result = posted(useCase.execute(request(amount = "100.00")))

        result.entry.source shouldBe JournalSource.CASH_BOOK
        result.entry.lines.map { it.accountId to it.side } shouldBe listOf(cash.id to TransactionSide.DEBIT, sales.id to TransactionSide.CREDIT)
        result.balanceAfter shouldBe money("100.00")
        result.warnings shouldBe emptyList()
        entries.findAllByAccount(cash.id).size shouldBe 1
        accounts.findById(cash.id)!!.let { it.hasPostedActivityForTest() shouldBe true }
    }

    @Test
    fun `given a payment, then it credits the book and debits the counter-account`() {
        useCase.execute(request(amount = "300.00"))

        val result = posted(useCase.execute(request(direction = CashDirection.PAID, amount = "120.00", counter = rent)))

        result.entry.lines.map { it.accountId to it.side } shouldBe listOf(cash.id to TransactionSide.CREDIT, rent.id to TransactionSide.DEBIT)
        result.balanceAfter shouldBe money("180.00")
    }

    @Test
    fun `given a cash flow activity, then it is tagged on the book's line`() {
        val result = posted(useCase.execute(request(activity = CashFlowActivity.FINANCING, counter = sales)))

        result.entry.lines.first { it.accountId == cash.id }.dimensions[DimensionType.CASH_FLOW_ACTIVITY] shouldBe "FINANCING"
    }

    @Test
    fun `given a payment that takes cash below zero, then it posts with a cash_below_zero warning naming the account and the balance`() {
        val result = posted(useCase.execute(request(direction = CashDirection.PAID, amount = "50.00", counter = rent)))

        result.balanceAfter shouldBe money("-50.00")
        result.warnings shouldBe listOf(RecordCashBookEntryUseCase.Warning("cash_below_zero", cash.id, money("-50.00")))
    }

    @Test
    fun `given a payment that overdraws a bank account, then it posts with a bank_overdrawn warning`() {
        val result = posted(useCase.execute(request(book = bank, direction = CashDirection.PAID, amount = "75.00", counter = rent)))

        result.warnings shouldBe listOf(RecordCashBookEntryUseCase.Warning("bank_overdrawn", bank.id, money("-75.00")))
    }

    @Test
    fun `given a receipt, then no warning even when the account was already below zero`() {
        useCase.execute(request(direction = CashDirection.PAID, amount = "50.00", counter = rent))

        posted(useCase.execute(request(amount = "10.00"))).warnings shouldBe emptyList()
    }

    // ---- idempotency: the entry id derived from the Idempotency-Key is the guard ----

    @Test
    fun `given the same request sent again with the same entry id, then it is a replay - one entry, balance unchanged`() {
        val id = JournalEntryId.generate()
        val first = posted(useCase.execute(request(entryId = id, amount = "100.00")))

        val second = useCase.execute(request(entryId = id, amount = "100.00")).shouldBeInstanceOf<RecordCashBookEntryUseCase.Result.Replay>()

        second.entry.id shouldBe first.entry.id
        entries.findAllByAccount(cash.id).size shouldBe 1
        second.balanceAfter shouldBe money("100.00")
    }

    @Test
    fun `given the same entry id with a different amount, counter-account, date or description, then it is refused as key reuse and nothing changes`() {
        val id = JournalEntryId.generate()
        useCase.execute(request(entryId = id, amount = "100.00"))

        useCase.execute(request(entryId = id, amount = "101.00")) shouldBe RecordCashBookEntryUseCase.Result.KeyReused
        useCase.execute(request(entryId = id, counter = rent)) shouldBe RecordCashBookEntryUseCase.Result.KeyReused
        useCase.execute(request(entryId = id, description = "Something else")) shouldBe RecordCashBookEntryUseCase.Result.KeyReused
        useCase.execute(request(entryId = id, direction = CashDirection.PAID)) shouldBe RecordCashBookEntryUseCase.Result.KeyReused
        entries.findAllByAccount(cash.id).size shouldBe 1
    }

    @Test
    fun `given a refused attempt with a key, then the key is not spent - the corrected request with the same key posts`() {
        val id = JournalEntryId.generate()
        val ar = asset("1100", null)

        useCase.execute(request(entryId = id, counter = ar)) shouldBe RecordCashBookEntryUseCase.Result.CounterAccountNotAllowed(UseInstead.SALES_COLLECTION)
        entries.findAllByAccount(cash.id).size shouldBe 0

        posted(useCase.execute(request(entryId = id, counter = sales)))
        entries.findAllByAccount(cash.id).size shouldBe 1
    }

    @Test
    fun `given two simultaneous identical requests, then exactly one entry is posted and the other is a replay`() {
        val id = JournalEntryId.generate()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val futures = (1..8).map {
            pool.submit<RecordCashBookEntryUseCase.Result> {
                start.await()
                useCase.execute(request(entryId = id, amount = "25.00"))
            }
        }
        start.countDown()
        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        results.count { it is RecordCashBookEntryUseCase.Result.Posted } shouldBe 1
        results.count { it is RecordCashBookEntryUseCase.Result.Replay } shouldBe 7
        entries.findAllByAccount(cash.id).size shouldBe 1
    }

    // ---- refusals ----

    @Test
    fun `given a control account as the counter-account, then it is refused naming the screen to use`() {
        val ar = asset("1100", null)
        val ap = Account.create(company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2000", "AP").also { accounts.save(it) }
        val stock = asset("1300", null)

        useCase.execute(request(counter = ar)) shouldBe RecordCashBookEntryUseCase.Result.CounterAccountNotAllowed(UseInstead.SALES_COLLECTION)
        useCase.execute(request(counter = ap)) shouldBe RecordCashBookEntryUseCase.Result.CounterAccountNotAllowed(UseInstead.PURCHASE_PAYMENT)
        useCase.execute(request(counter = stock)) shouldBe RecordCashBookEntryUseCase.Result.CounterAccountNotAllowed(UseInstead.INVENTORY)
        entries.findAllByAccount(cash.id).size shouldBe 0
    }

    @Test
    fun `given another cash or bank book as the counter-account, then it is refused and points to Move money`() {
        useCase.execute(request(counter = bank)) shouldBe RecordCashBookEntryUseCase.Result.CounterAccountNotAllowed(UseInstead.TRANSFER)
    }

    @Test
    fun `given the book itself as the counter-account, then it is refused`() {
        useCase.execute(request(counter = cash)) shouldBe RecordCashBookEntryUseCase.Result.CounterAccountNotAllowed(UseInstead.TRANSFER)
    }

    @Test
    fun `given a counter-account of another Company, then it is not found - nothing confirms it exists`() {
        val other = Company.create(TenantId.generate(), "Other", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
        val foreign = Account.create(other.id, AccountType.REVENUE, null, "4000", "Their sales").also { accounts.save(it) }

        useCase.execute(request(counter = foreign)) shouldBe RecordCashBookEntryUseCase.Result.CounterAccountNotFound
        entries.findAllByAccount(cash.id).size shouldBe 0
    }

    @Test
    fun `given an inactive counter-account, then it is refused`() {
        val old = Account.create(company.id, AccountType.REVENUE, null, "4100", "Old income").also { it.deactivate(); accounts.save(it) }

        useCase.execute(request(counter = old)) shouldBe RecordCashBookEntryUseCase.Result.CounterAccountInactive
    }

    @Test
    fun `given a book account that is not a cash or bank book, or belongs to another Company, then it is refused`() {
        val plain = asset("1500", null)
        val other = Company.create(TenantId.generate(), "Other", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
        val foreign = Account.create(other.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH).also { accounts.save(it) }

        useCase.execute(request(book = plain)) shouldBe RecordCashBookEntryUseCase.Result.NotACashBook
        useCase.execute(request(book = foreign)) shouldBe RecordCashBookEntryUseCase.Result.AccountNotFound
    }

    @Test
    fun `given an amount that is zero, negative, or has more decimals than the currency, then it is refused`() {
        useCase.execute(request(amount = "0.00")) shouldBe RecordCashBookEntryUseCase.Result.InvalidAmount
        useCase.execute(request(amount = "-5.00")) shouldBe RecordCashBookEntryUseCase.Result.InvalidAmount
        useCase.execute(request(amount = "1.005")) shouldBe RecordCashBookEntryUseCase.Result.InvalidAmount
        entries.findAllByAccount(cash.id).size shouldBe 0
    }

    @Test
    fun `given no open Period, then it is refused and nothing posts`() {
        val closed = Company.create(TenantId.generate(), "Closed Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
        val closedCash = Account.create(closed.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH).also { accounts.save(it) }
        val closedSales = Account.create(closed.id, AccountType.REVENUE, null, "4000", "Sales").also { accounts.save(it) }

        val result = useCase.execute(
            RecordCashBookEntryUseCase.Request(
                closed.id, closedCash.id, CashDirection.RECEIVED, BigDecimal("10.00"), today, closedSales.id, null, CashFlowActivity.OPERATING, JournalEntryId.generate()
            )
        )

        result shouldBe RecordCashBookEntryUseCase.Result.NoOpenPeriod
    }

    @Test
    fun `given an unknown Company, then it is not found`() {
        val result = useCase.execute(request().copy(companyId = CompanyId.generate()))

        result shouldBe RecordCashBookEntryUseCase.Result.CompanyNotFound
    }

    /** Whether posting recorded activity on the account (the same flag every posting use case sets). */
    private fun Account.hasPostedActivityForTest(): Boolean = !validateDeletion().isValid
}
