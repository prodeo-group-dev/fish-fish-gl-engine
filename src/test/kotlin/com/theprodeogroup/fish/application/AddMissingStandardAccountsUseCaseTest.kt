package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.Currency

/**
 * `AddMissingStandardAccountsUseCase` (docs/GL_Cash_And_Bank_Books_SRS.md, FR-CB60): an older Company whose chart
 * predates the current template (the VAT control account 2150 that Company 13de72e4 lacked, so its Sales screen
 * answered 409) gets the missing standard accounts, and nothing it already has is touched.
 */
class AddMissingStandardAccountsUseCaseTest {

    private val gbp = Currency.getInstance("GBP")
    private val companies = FakeCompanyRepository()
    private val accounts = FakeAccountRepository()
    private val useCase = AddMissingStandardAccountsUseCase(companies, accounts)

    private fun company(type: ClientType = ClientType.COMPANY_LIMITED) =
        Company.create(TenantId.generate(), "Co", type, Jurisdiction.UK, gbp).also { companies.save(it) }

    /** A chart as an older Company has it: the template minus the given codes. */
    private fun olderChart(company: Company, without: Set<String>) {
        ChartOfAccountsTemplate.accountsFor(company.clientType, company.id)
            .filter { it.code !in without }
            .forEach { accounts.save(it) }
    }

    @Test
    fun `given a chart without the VAT control account, when run, then 2150 is added and reported`() {
        val co = company()
        olderChart(co, without = setOf(ChartOfAccountsTemplate.VAT_CONTROL_ACCOUNT_CODE))

        val result = useCase.execute(co.id).shouldBeInstanceOf<AddMissingStandardAccountsUseCase.Result.Success>()

        result.added.map { it.code } shouldBe listOf("2150")
        accounts.findAllByCompany(co.id).any { it.code == "2150" && it.type == AccountType.LIABILITY } shouldBe true
    }

    @Test
    fun `given the same Company, when run twice, then the second run adds nothing`() {
        val co = company()
        olderChart(co, without = setOf("2150"))
        useCase.execute(co.id)
        val countAfterFirst = accounts.findAllByCompany(co.id).size

        val second = useCase.execute(co.id).shouldBeInstanceOf<AddMissingStandardAccountsUseCase.Result.Success>()

        second.added shouldBe emptyList()
        accounts.findAllByCompany(co.id).size shouldBe countAfterFirst
    }

    @Test
    fun `given a code already used by an account of a different type, then it is reported as a conflict and left alone`() {
        val co = company()
        olderChart(co, without = setOf("2150"))
        val mine = Account.create(co.id, AccountType.EXPENSE, null, "2150", "My own account").also { accounts.save(it) }

        val result = useCase.execute(co.id).shouldBeInstanceOf<AddMissingStandardAccountsUseCase.Result.Success>()

        result.added shouldBe emptyList()
        result.conflicts.map { it.code } shouldBe listOf("2150")
        accounts.findById(mine.id)!!.name shouldBe "My own account"
        accounts.findAllByCompany(co.id).count { it.code == "2150" } shouldBe 1
    }

    @Test
    fun `given account 1000 with no kind, when run, then it becomes the CASH book`() {
        val co = company()
        accounts.save(Account.create(co.id, AccountType.ASSET, AccountClassification.CURRENT, "1000", "Cash"))

        val result = useCase.execute(co.id).shouldBeInstanceOf<AddMissingStandardAccountsUseCase.Result.Success>()

        result.cashBookKindSet shouldBe true
        accounts.findAllByCompany(co.id).single { it.code == "1000" }.cashBookKind shouldBe CashBookKind.CASH
        useCase.execute(co.id).shouldBeInstanceOf<AddMissingStandardAccountsUseCase.Result.Success>().cashBookKindSet shouldBe false
    }

    @Test
    fun `given a bank account the owner created, then it is not touched and no bank account is added`() {
        val co = company()
        olderChart(co, without = emptySet())
        val bank = Account.create(co.id, AccountType.ASSET, AccountClassification.CURRENT, "1010", "Barclays", cashBookKind = CashBookKind.BANK).also { accounts.save(it) }

        useCase.execute(co.id)

        accounts.findById(bank.id)!!.cashBookKind shouldBe CashBookKind.BANK
        accounts.findAllByCompany(co.id).count { it.cashBookKind == CashBookKind.BANK } shouldBe 1
    }

    @Test
    fun `given any client type with a full chart, then nothing is added`() {
        for (type in ClientType.entries) {
            val co = company(type)
            olderChart(co, without = emptySet())

            useCase.execute(co.id).shouldBeInstanceOf<AddMissingStandardAccountsUseCase.Result.Success>().added shouldBe emptyList()
        }
    }

    @Test
    fun `given another Company with the same gap, then it is not changed`() {
        val mine = company()
        val other = company()
        olderChart(mine, without = setOf("2150"))
        olderChart(other, without = setOf("2150"))

        useCase.execute(mine.id)

        accounts.findAllByCompany(other.id).none { it.code == "2150" } shouldBe true
    }

    @Test
    fun `given an unknown Company, then it is not found`() {
        useCase.execute(CompanyId.generate()) shouldBe AddMissingStandardAccountsUseCase.Result.CompanyNotFound
    }
}
