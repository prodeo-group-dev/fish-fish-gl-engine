package com.theprodeogroup.fish.application

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.JournalSource
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
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/** Which accounts the statement of cash flows covers: every cash and bank account (docs/GL_Cash_And_Bank_Books_SRS.md, FR-CB40). */
class ComputeCashFlowUseCaseTest {

    private val gbp = Currency.getInstance("GBP")
    private val from = LocalDate.of(2026, 10, 1)
    private val to = LocalDate.of(2026, 10, 31)
    private val companies = FakeCompanyRepository()
    private val accounts = FakeAccountRepository()
    private val periods = FakePeriodRepository()
    private val entries = FakeJournalEntryRepository()
    private val useCase = ComputeCashFlowUseCase(companies, periods, accounts, entries)

    private val company = Company.create(TenantId.generate(), "Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
    private val period = Period.create(company.id, PeriodType.MONTH, from, to).also { it.open(); periods.save(it) }
    private val revenue = Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales").also { accounts.save(it) }

    private fun asset(code: String, kind: CashBookKind?) =
        Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, code, "Acct $code", cashBookKind = kind).also { accounts.save(it) }

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun post(debit: Account, credit: Account, amount: String) =
        JournalEntry.create(
            period.id, LocalDate.of(2026, 10, 9),
            listOf(JournalLine(debit.id, money(amount), TransactionSide.DEBIT), JournalLine(credit.id, money(amount), TransactionSide.CREDIT)),
            JournalSource.MANUAL
        ).also { it.post(); entries.save(it) }

    private fun statement() =
        useCase.execute(company.id, from, to).shouldBeInstanceOf<ComputeCashFlowUseCase.Result.Success>().statementOfCashFlows

    @Test
    fun `given only account 1000 with no kind yet, then it is still the cash pool - nothing changes for existing Companies`() {
        val cash = asset("1000", null)
        post(cash, revenue, "100.00")

        statement().closingBalance shouldBe money("100.00")
    }

    @Test
    fun `given a cash book and a bank book, then the statement covers both and a transfer between them is not a cash flow`() {
        val cash = asset("1000", CashBookKind.CASH)
        val bank = asset("1010", CashBookKind.BANK)
        post(cash, revenue, "100.00")
        post(bank, revenue, "400.00")
        post(bank, cash, "30.00") // transfer

        val s = statement()

        s.closingBalance shouldBe money("500.00")
        s.netCashFlow shouldBe money("500.00")
        s.cashAccountIds.size shouldBe 2
    }

    @Test
    fun `given a bank book and no account 1000, then the bank book alone is the pool`() {
        val bank = asset("1010", CashBookKind.BANK)
        post(bank, revenue, "250.00")

        statement().closingBalance shouldBe money("250.00")
    }

    @Test
    fun `given an asset account that is neither flagged nor 1000, then it is not counted as cash`() {
        val cash = asset("1000", CashBookKind.CASH)
        val receivables = asset("1100", null)
        post(cash, revenue, "100.00")
        post(receivables, revenue, "900.00")

        statement().closingBalance shouldBe money("100.00")
    }

    @Test
    fun `given no cash or bank account at all, then there is no cash account`() {
        asset("1100", null)

        useCase.execute(company.id, from, to) shouldBe ComputeCashFlowUseCase.Result.NoCashAccount
    }
}
