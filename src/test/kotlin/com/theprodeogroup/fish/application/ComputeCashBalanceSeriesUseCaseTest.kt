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

class ComputeCashBalanceSeriesUseCaseTest {

    private val gbp = Currency.getInstance("GBP")
    private val companies = FakeCompanyRepository()
    private val accounts = FakeAccountRepository()
    private val periods = FakePeriodRepository()
    private val entries = FakeJournalEntryRepository()
    private val useCase = ComputeCashBalanceSeriesUseCase(companies, accounts, entries)
    private val listBooks = ListCashBooksUseCase(companies, accounts, entries)
    private val today = LocalDate.of(2026, 10, 12)

    private val company = Company.create(TenantId.generate(), "Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
    private val period = Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31)).also { it.open(); periods.save(it) }

    private fun asset(code: String, kind: CashBookKind?, forCompany: Company = company) =
        Account.create(forCompany.id, AccountType.ASSET, AccountClassification.CURRENT, code, "Acct $code", cashBookKind = kind).also { accounts.save(it) }

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun post(date: LocalDate, debit: Account, credit: Account, amount: String) =
        JournalEntry.create(
            period.id, date,
            listOf(JournalLine(debit.id, money(amount), TransactionSide.DEBIT), JournalLine(credit.id, money(amount), TransactionSide.CREDIT)),
            JournalSource.MANUAL
        ).also { it.post(); entries.save(it) }

    private val equity = Account.create(company.id, AccountType.EQUITY, null, "3000", "Capital").also { accounts.save(it) }

    @Test
    fun `given a cash book and a bank book, then the series is their combined balance and today's point equals the sum the book list shows`() {
        val cash = asset("1000", CashBookKind.CASH)
        val bank = asset("1010", CashBookKind.BANK)
        post(today.minusDays(5), cash, equity, "100.00")
        post(today.minusDays(1), bank, equity, "900.00")
        post(today, equity, bank, "150.00")

        val series = useCase.execute(company.id, 7, today).shouldBeInstanceOf<ComputeCashBalanceSeriesUseCase.Result.Success>().series
        val listed = listBooks.execute(company.id).shouldBeInstanceOf<ListCashBooksUseCase.Result.Success>().items

        series.points.last().closingBalance shouldBe listed.fold(Money(BigDecimal.ZERO, gbp)) { sum, item -> sum + item.balance }
        series.points.map { it.closingBalance.amount.toPlainString() } shouldBe listOf("0.00", "100.00", "100.00", "100.00", "100.00", "1000.00", "850.00")
        series.currency shouldBe gbp
    }

    @Test
    fun `given an asset account without a kind and an inactive cash book, then neither is in the series`() {
        val receivables = asset("1100", null)
        val oldBank = asset("1020", CashBookKind.BANK)
        val cash = asset("1000", CashBookKind.CASH)
        post(today, receivables, equity, "500.00")
        post(today, oldBank, equity, "300.00")
        oldBank.deactivate().also { accounts.save(oldBank) }
        post(today, cash, equity, "10.00")

        useCase.execute(company.id, 1, today).shouldBeInstanceOf<ComputeCashBalanceSeriesUseCase.Result.Success>()
            .series.points.single().closingBalance shouldBe money("10.00")
    }

    @Test
    fun `given another Company's books, then they never enter this Company's series`() {
        val mine = asset("1000", CashBookKind.CASH)
        val other = Company.create(TenantId.generate(), "Other", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
        val theirs = asset("1000", CashBookKind.CASH, other)
        val theirEquity = Account.create(other.id, AccountType.EQUITY, null, "3000", "Capital").also { accounts.save(it) }
        post(today, mine, equity, "10.00")
        post(today, theirs, theirEquity, "9999.00")

        useCase.execute(company.id, 1, today).shouldBeInstanceOf<ComputeCashBalanceSeriesUseCase.Result.Success>()
            .series.points.single().closingBalance shouldBe money("10.00")
    }

    @Test
    fun `given an unknown Company, then it is not found`() {
        useCase.execute(CompanyId.generate(), 30, today) shouldBe ComputeCashBalanceSeriesUseCase.Result.CompanyNotFound
    }

    @Test
    fun `given days outside the range, then it is refused before anything is read`() {
        useCase.execute(company.id, 0, today) shouldBe ComputeCashBalanceSeriesUseCase.Result.InvalidDays
        useCase.execute(company.id, 367, today) shouldBe ComputeCashBalanceSeriesUseCase.Result.InvalidDays
        useCase.execute(CompanyId.generate(), 0, today) shouldBe ComputeCashBalanceSeriesUseCase.Result.InvalidDays
    }
}
