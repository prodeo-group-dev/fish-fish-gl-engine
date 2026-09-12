package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChartOfAccountsTemplateTest {

    @Test
    fun `given any ClientType, when a template is requested, then every account belongs to the requested Company and has a unique code`() {
        val companyId = CompanyId.generate()

        for (clientType in ClientType.entries) {
            val accounts = ChartOfAccountsTemplate.accountsFor(clientType, companyId)

            accounts.isNotEmpty() shouldBe true
            accounts.all { it.companyId == companyId } shouldBe true
            accounts.map { it.code }.toSet().size shouldBe accounts.size
        }
    }

    @Test
    fun `given any ClientType, when a template is requested, then every account is active with no parent`() {
        val accounts = ChartOfAccountsTemplate.accountsFor(ClientType.COMPANY_LIMITED, CompanyId.generate())

        accounts.all { it.active } shouldBe true
        accounts.all { it.parentId == null } shouldBe true
    }

    @Test
    fun `given INDIVIDUAL, when a template is requested, then it seeds personal-finance accounts, not business ones`() {
        val accounts = ChartOfAccountsTemplate.accountsFor(ClientType.INDIVIDUAL, CompanyId.generate())

        accounts.map { it.name } shouldContainExactlyInAnyOrder listOf(
            "Cash", "Investments", "Loans", "Credit Cards", "Personal Equity", "Opening Balance Equity", "Suspense Account",
            "Salary Income", "Investment Income", "Living Expenses", "Entertainment"
        )
    }

    @Test
    fun `given COMPANY_LIMITED, when a template is requested, then equity reflects share capital and dividends, not drawings`() {
        val accounts = ChartOfAccountsTemplate.accountsFor(ClientType.COMPANY_LIMITED, CompanyId.generate())

        accounts.filter { it.type == AccountType.EQUITY }.map { it.name } shouldContainExactlyInAnyOrder
            listOf("Share Capital", "Retained Earnings", "Dividends", "Opening Balance Equity", "Suspense Account")
    }

    @Test
    fun `given SOLE_TRADER, when a template is requested, then equity reflects owner's capital and drawings`() {
        val accounts = ChartOfAccountsTemplate.accountsFor(ClientType.SOLE_TRADER, CompanyId.generate())

        accounts.filter { it.type == AccountType.EQUITY }.map { it.name } shouldContainExactlyInAnyOrder
            listOf("Owner's Capital", "Owner's Drawings", "Opening Balance Equity", "Suspense Account")
    }

    @Test
    fun `given NON_PROFIT, when a template is requested, then equity reflects net assets, not owner's equity or share capital`() {
        val accounts = ChartOfAccountsTemplate.accountsFor(ClientType.NON_PROFIT, CompanyId.generate())

        accounts.filter { it.type == AccountType.EQUITY }.map { it.name } shouldContainExactlyInAnyOrder
            listOf("Unrestricted Net Assets", "Restricted Net Assets", "Opening Balance Equity", "Suspense Account")
        accounts.filter { it.type == AccountType.REVENUE }.map { it.name } shouldContainExactlyInAnyOrder
            listOf("Donations Income", "Grants Income")
    }

    @Test
    fun `given every ClientType, when a template is requested, then every Asset and Liability account has a classification`() {
        for (clientType in ClientType.entries) {
            val accounts = ChartOfAccountsTemplate.accountsFor(clientType, CompanyId.generate())

            accounts.filter { it.type == AccountType.ASSET || it.type == AccountType.LIABILITY }
                .all { it.classification != null } shouldBe true
            accounts.filter { it.type == AccountType.EQUITY || it.type == AccountType.REVENUE || it.type == AccountType.EXPENSE }
                .all { it.classification == null } shouldBe true
        }
    }

    @Test
    fun `given every ClientType, when a template is requested, then it includes a Cash account, an Opening Balance Equity account, and a Suspense Account at the documented codes`() {
        for (clientType in ClientType.entries) {
            val accounts = ChartOfAccountsTemplate.accountsFor(clientType, CompanyId.generate())

            val cash = accounts.single { it.code == ChartOfAccountsTemplate.CASH_CODE }
            cash.name shouldBe "Cash"
            cash.type shouldBe AccountType.ASSET

            val openingBalanceEquity = accounts.single { it.code == ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE }
            openingBalanceEquity.name shouldBe "Opening Balance Equity"
            openingBalanceEquity.type shouldBe AccountType.EQUITY

            val suspenseAccount = accounts.single { it.code == ChartOfAccountsTemplate.SUSPENSE_ACCOUNT_CODE }
            suspenseAccount.name shouldBe "Suspense Account"
            suspenseAccount.type shouldBe AccountType.EQUITY
        }
    }

    @Test
    fun `given every business ClientType, when a template is requested, then it includes payroll accounts at the documented codes`() {
        for (clientType in ClientType.entries.filter { it != ClientType.INDIVIDUAL }) {
            val accounts = ChartOfAccountsTemplate.accountsFor(clientType, CompanyId.generate())

            val wages = accounts.single { it.code == ChartOfAccountsTemplate.WAGES_EXPENSE_CODE }
            wages.name shouldBe "Wages Expense"
            wages.type shouldBe AccountType.EXPENSE

            val salaries = accounts.single { it.code == ChartOfAccountsTemplate.SALARIES_EXPENSE_CODE }
            salaries.name shouldBe "Salaries Expense"
            salaries.type shouldBe AccountType.EXPENSE

            val leaveExpense = accounts.single { it.code == ChartOfAccountsTemplate.LEAVE_EXPENSE_CODE }
            leaveExpense.name shouldBe "Leave Expense"
            leaveExpense.type shouldBe AccountType.EXPENSE

            val accruedLeaveLiability = accounts.single { it.code == ChartOfAccountsTemplate.ACCRUED_LEAVE_LIABILITY_CODE }
            accruedLeaveLiability.name shouldBe "Accrued Leave Liability"
            accruedLeaveLiability.type shouldBe AccountType.LIABILITY
        }
    }

    @Test
    fun `given INDIVIDUAL, when a template is requested, then it has no payroll accounts`() {
        val accounts = ChartOfAccountsTemplate.accountsFor(ClientType.INDIVIDUAL, CompanyId.generate())

        accounts.none { it.code == ChartOfAccountsTemplate.WAGES_EXPENSE_CODE } shouldBe true
        accounts.none { it.code == ChartOfAccountsTemplate.SALARIES_EXPENSE_CODE } shouldBe true
        accounts.none { it.code == ChartOfAccountsTemplate.LEAVE_EXPENSE_CODE } shouldBe true
        accounts.none { it.code == ChartOfAccountsTemplate.ACCRUED_LEAVE_LIABILITY_CODE } shouldBe true
    }

    @Test
    fun `given every business ClientType, when a template is requested, then it includes a Trade Finance Facility Payable liability at the documented code`() {
        for (clientType in ClientType.entries.filter { it != ClientType.INDIVIDUAL }) {
            val accounts = ChartOfAccountsTemplate.accountsFor(clientType, CompanyId.generate())

            val facilityLiability = accounts.single { it.code == ChartOfAccountsTemplate.FACILITY_LIABILITY_CODE }
            facilityLiability.name shouldBe "Trade Finance Facility Payable"
            facilityLiability.type shouldBe AccountType.LIABILITY
            facilityLiability.classification shouldBe AccountClassification.CURRENT
        }
    }

    @Test
    fun `given INDIVIDUAL, when a template is requested, then it has no Trade Finance Facility Payable account`() {
        val accounts = ChartOfAccountsTemplate.accountsFor(ClientType.INDIVIDUAL, CompanyId.generate())

        accounts.none { it.code == ChartOfAccountsTemplate.FACILITY_LIABILITY_CODE } shouldBe true
    }
}
