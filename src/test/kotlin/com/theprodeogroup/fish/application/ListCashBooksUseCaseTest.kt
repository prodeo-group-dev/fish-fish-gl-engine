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
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

class ListCashBooksUseCaseTest {

    private val gbp = Currency.getInstance("GBP")
    private val companies = FakeCompanyRepository()
    private val accounts = FakeAccountRepository()
    private val periods = FakePeriodRepository()
    private val entries = FakeJournalEntryRepository()
    private val useCase = ListCashBooksUseCase(companies, accounts, entries)

    private val company = Company.create(TenantId.generate(), "Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
    private val period = Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).also { it.open(); periods.save(it) }

    private fun asset(code: String, name: String, kind: CashBookKind?) =
        Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, code, name, cashBookKind = kind).also { accounts.save(it) }

    private fun money(amount: String) = Money(BigDecimal(amount), gbp)

    private fun post(date: String, debit: Account, credit: Account, amount: String) =
        JournalEntry.create(
            period.id, LocalDate.parse(date),
            listOf(JournalLine(debit.id, money(amount), TransactionSide.DEBIT), JournalLine(credit.id, money(amount), TransactionSide.CREDIT)),
            JournalSource.MANUAL
        ).also { it.post(); entries.save(it) }

    @Test
    fun `given a cash book and a bank book, then both are listed by code with balance, last entry date and the default flag`() {
        val cash = asset("1000", "Cash", CashBookKind.CASH)
        val bank = asset("1010", "Barclays", CashBookKind.BANK)
        val equity = Account.create(company.id, AccountType.EQUITY, null, "3000", "Capital").also { accounts.save(it) }
        post("2026-10-02", cash, equity, "100.00")
        post("2026-10-09", bank, equity, "900.00")
        post("2026-10-12", equity, bank, "150.00")

        val result = useCase.execute(company.id).shouldBeInstanceOf<ListCashBooksUseCase.Result.Success>()

        result.items.map { it.account.code } shouldBe listOf("1000", "1010")
        result.items.map { it.balance } shouldBe listOf(money("100.00"), money("750.00"))
        result.items.map { it.lastEntryDate } shouldBe listOf(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 12))
        result.items.map { it.isDefault } shouldBe listOf(true, false)
        result.currency shouldBe gbp
    }

    @Test
    fun `given an asset account without a kind and an inactive cash book, then neither is listed`() {
        asset("1100", "Receivables", null)
        asset("1020", "Old bank", CashBookKind.BANK).also { it.deactivate() }

        val result = useCase.execute(company.id).shouldBeInstanceOf<ListCashBooksUseCase.Result.Success>()

        result.items shouldBe emptyList()
    }

    @Test
    fun `given another Company's cash book, then it is not listed`() {
        val other = Company.create(TenantId.generate(), "Other", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
        Account.create(other.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH).also { accounts.save(it) }

        val result = useCase.execute(company.id).shouldBeInstanceOf<ListCashBooksUseCase.Result.Success>()

        result.items shouldBe emptyList()
    }

    @Test
    fun `given an unknown Company, then it is not found`() {
        useCase.execute(CompanyId.generate()) shouldBe ListCashBooksUseCase.Result.CompanyNotFound
    }
}
