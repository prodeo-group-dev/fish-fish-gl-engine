package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.ChartOfAccountsTemplate
import com.theprodeogroup.fish.domain.ledger.ExpenseClassification
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Currency

/**
 * Verifies the trading P&L's account classifications round-trip through a real
 * Postgres, including the two values added 2026-10-07 and re-tagging an
 * existing account. Skips (not fails) if `FISH_DB_USER`/`FISH_DB_PASSWORD` aren't set.
 */
class AccountExpenseClassificationIntegrationTest {

    private val companyRepository = ExposedCompanyRepository()
    private val accountRepository = ExposedAccountRepository()

    @BeforeEach
    fun setUp() {
        assumeTrue(
            System.getenv("FISH_DB_USER") != null && System.getenv("FISH_DB_PASSWORD") != null,
            "FISH_DB_USER/FISH_DB_PASSWORD not set - skipping"
        )
        val dataSource = DatabaseConfig.dataSource()
        DatabaseMigrator.migrate(dataSource)
        DatabaseConfig.connectExposed(dataSource)
    }

    private fun newCompany(): CompanyId =
        Company.create(TenantId.generate(), "Classification Test Co", ClientType.COMPANY_LIMITED, Jurisdiction.UK, Currency.getInstance("GBP"))
            .also { companyRepository.save(it) }.id

    @Test
    fun `given the new interest and income-tax classifications, when accounts are saved and reloaded, then they round-trip`() {
        val companyId = newCompany()
        val interest = Account.create(companyId, AccountType.EXPENSE, null, "5600", "Interest Expense", ExpenseClassification.INTEREST_EXPENSE)
        val tax = Account.create(companyId, AccountType.EXPENSE, null, "5700", "Income Tax Expense", ExpenseClassification.INCOME_TAX_EXPENSE)
        accountRepository.save(interest)
        accountRepository.save(tax)

        val loaded = accountRepository.findAllByCompany(companyId).associateBy { it.code }

        loaded.getValue("5600").expenseClassification shouldBe ExpenseClassification.INTEREST_EXPENSE
        loaded.getValue("5700").expenseClassification shouldBe ExpenseClassification.INCOME_TAX_EXPENSE
    }

    @Test
    fun `given a saved account, when it is re-tagged and saved again, then the new classification is what loads - and clearing it persists too`() {
        val companyId = newCompany()
        val account = Account.create(companyId, AccountType.EXPENSE, null, "5001", "Purchases")
        accountRepository.save(account)

        account.reclassifyExpense(ExpenseClassification.COST_OF_GOODS_SOLD)
        accountRepository.save(account)
        accountRepository.findById(account.id)!!.expenseClassification shouldBe ExpenseClassification.COST_OF_GOODS_SOLD

        account.reclassifyExpense(null)
        accountRepository.save(account)
        accountRepository.findById(account.id)!!.expenseClassification shouldBe null
    }

    @Test
    fun `given the seeded business chart, when its reporting accounts are saved, then cost of sales interest and tax load with their tags`() {
        val companyId = newCompany()
        ChartOfAccountsTemplate.accountsFor(ClientType.COMPANY_LIMITED, companyId).forEach { accountRepository.save(it) }

        val loaded = accountRepository.findAllByCompany(companyId).associateBy { it.code }

        loaded.getValue(ChartOfAccountsTemplate.COST_OF_SALES_CODE).expenseClassification shouldBe ExpenseClassification.COST_OF_GOODS_SOLD
        loaded.getValue(ChartOfAccountsTemplate.INTEREST_EXPENSE_CODE).expenseClassification shouldBe ExpenseClassification.INTEREST_EXPENSE
        loaded.getValue(ChartOfAccountsTemplate.INCOME_TAX_EXPENSE_CODE).expenseClassification shouldBe ExpenseClassification.INCOME_TAX_EXPENSE
    }
}
