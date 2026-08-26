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
            "Cash", "Investments", "Loans", "Credit Cards", "Personal Equity", "Opening Balance Equity",
            "Salary Income", "Investment Income", "Living Expenses", "Entertainment"
        )
    }

    @Test
    fun `given COMPANY_LIMITED, when a template is requested, then equity reflects share capital and dividends, not drawings`() {
        val accounts = ChartOfAccountsTemplate.accountsFor(ClientType.COMPANY_LIMITED, CompanyId.generate())

        accounts.filter { it.type == AccountType.EQUITY }.map { it.name } shouldContainExactlyInAnyOrder
            listOf("Share Capital", "Retained Earnings", "Dividends", "Opening Balance Equity")
    }

    @Test
    fun `given SOLE_TRADER, when a template is requested, then equity reflects owner's capital and drawings`() {
        val accounts = ChartOfAccountsTemplate.accountsFor(ClientType.SOLE_TRADER, CompanyId.generate())

        accounts.filter { it.type == AccountType.EQUITY }.map { it.name } shouldContainExactlyInAnyOrder
            listOf("Owner's Capital", "Owner's Drawings", "Opening Balance Equity")
    }

    @Test
    fun `given NON_PROFIT, when a template is requested, then equity reflects net assets, not owner's equity or share capital`() {
        val accounts = ChartOfAccountsTemplate.accountsFor(ClientType.NON_PROFIT, CompanyId.generate())

        accounts.filter { it.type == AccountType.EQUITY }.map { it.name } shouldContainExactlyInAnyOrder
            listOf("Unrestricted Net Assets", "Restricted Net Assets", "Opening Balance Equity")
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
    fun `given every ClientType, when a template is requested, then it includes a Cash account and an Opening Balance Equity account at the documented codes`() {
        for (clientType in ClientType.entries) {
            val accounts = ChartOfAccountsTemplate.accountsFor(clientType, CompanyId.generate())

            val cash = accounts.single { it.code == ChartOfAccountsTemplate.CASH_CODE }
            cash.name shouldBe "Cash"
            cash.type shouldBe AccountType.ASSET

            val openingBalanceEquity = accounts.single { it.code == ChartOfAccountsTemplate.OPENING_BALANCE_EQUITY_CODE }
            openingBalanceEquity.name shouldBe "Opening Balance Equity"
            openingBalanceEquity.type shouldBe AccountType.EQUITY
        }
    }
}
