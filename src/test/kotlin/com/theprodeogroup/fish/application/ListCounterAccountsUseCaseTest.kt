package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.CashDirection
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.ledger.ExpenseClassification
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
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

class ListCounterAccountsUseCaseTest {

    private val gbp = Currency.getInstance("GBP")
    private val companies = FakeCompanyRepository()
    private val accounts = FakeAccountRepository()
    private val periods = FakePeriodRepository()
    private val entries = FakeJournalEntryRepository()
    private val useCase = ListCounterAccountsUseCase(companies, accounts)

    private val company = Company.create(TenantId.generate(), "Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
    private val book = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH).also { accounts.save(it) }

    private fun options(direction: CashDirection = CashDirection.RECEIVED) =
        useCase.execute(company.id, book.id, direction).shouldBeInstanceOf<ListCounterAccountsUseCase.Result.Success>().options

    @Test
    fun `given a full business chart, then the control accounts, other cash and bank books and the book itself are not offered`() {
        ChartOfAccountsTemplate.accountsFor(ClientType.COMPANY_LIMITED, company.id).filter { it.code != "1000" }.forEach { accounts.save(it) }
        Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Bank", cashBookKind = CashBookKind.BANK).also { accounts.save(it) }

        val codes = options().map { it.account.code }

        listOf("1000", "1010", "1100", "2000", "2150", "1300", "1200", "1210", "3900", "3910").forEach { (it in codes) shouldBe false }
        codes.isNotEmpty() shouldBe true
    }

    @Test
    fun `given income, expense, loan, owner and other accounts, then each carries its plain group and the list is ordered by group then code`() {
        Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales").also { accounts.save(it) }
        Account.create(company.id, AccountType.EXPENSE, null, "5000", "Rent").also { accounts.save(it) }
        Account.create(company.id, AccountType.EXPENSE, null, "5010", "Cost of sales", ExpenseClassification.COST_OF_GOODS_SOLD).also { accounts.save(it) }
        Account.create(company.id, AccountType.LIABILITY, AccountClassification.NON_CURRENT, "2500", "Bank loan").also { accounts.save(it) }
        Account.create(company.id, AccountType.EQUITY, null, "3000", "Capital").also { accounts.save(it) }
        Account.create(company.id, AccountType.LIABILITY, AccountClassification.CURRENT, "2400", "Other creditors").also { accounts.save(it) }

        options().map { it.account.code to it.group } shouldBe listOf(
            "4000" to "INCOME",
            "5000" to "EXPENSE_OTHER",
            "5010" to "EXPENSE_COST_OF_GOODS_SOLD",
            "2500" to "LOAN",
            "3000" to "OWNERS_MONEY",
            "2400" to "OTHER"
        )
    }

    @Test
    fun `given an inactive account or another Company's account, then neither is offered`() {
        Account.create(company.id, AccountType.REVENUE, null, "4100", "Old income").also { it.deactivate(); accounts.save(it) }
        val other = Company.create(TenantId.generate(), "Other", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
        Account.create(other.id, AccountType.REVENUE, null, "4200", "Their income").also { accounts.save(it) }

        options() shouldBe emptyList()
    }

    @Test
    fun `given the direction is either way, then the same accounts are offered`() {
        Account.create(company.id, AccountType.REVENUE, null, "4000", "Sales").also { accounts.save(it) }

        options(CashDirection.RECEIVED).map { it.account.code } shouldBe options(CashDirection.PAID).map { it.account.code }
    }

    @Test
    fun `given the full chart, then every account the picker offers is accepted by the recording use case - the two can never disagree`() {
        ChartOfAccountsTemplate.accountsFor(ClientType.COMPANY_LIMITED, company.id).filter { it.code != "1000" }.forEach { accounts.save(it) }
        Period.create(company.id, PeriodType.MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).also { it.open(); periods.save(it) }
        val record = RecordCashBookEntryUseCase(companies, accounts, periods, entries, PostJournalEntryUseCase(periods, accounts, entries))

        for (option in options()) {
            val result = record.execute(
                RecordCashBookEntryUseCase.Request(
                    company.id, book.id, CashDirection.RECEIVED, BigDecimal("1.00"), LocalDate.of(2026, 10, 5), option.account.id,
                    null, CashFlowActivity.OPERATING, JournalEntryId.generate()
                )
            )
            (result is RecordCashBookEntryUseCase.Result.Posted) shouldBe true
        }
    }

    @Test
    fun `given an account that is not a cash book, another Company's account, or an unknown Company, then they are refused`() {
        val plain = Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1500", "Land").also { accounts.save(it) }
        val other = Company.create(TenantId.generate(), "Other", ClientType.COMPANY_LIMITED, Jurisdiction.UK, gbp).also { companies.save(it) }
        val foreign = Account.create(other.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash", cashBookKind = CashBookKind.CASH).also { accounts.save(it) }

        useCase.execute(company.id, plain.id, CashDirection.RECEIVED) shouldBe ListCounterAccountsUseCase.Result.NotACashBook
        useCase.execute(company.id, foreign.id, CashDirection.RECEIVED) shouldBe ListCounterAccountsUseCase.Result.AccountNotFound
        useCase.execute(CompanyId.generate(), book.id, CashDirection.RECEIVED) shouldBe ListCounterAccountsUseCase.Result.CompanyNotFound
    }
}
