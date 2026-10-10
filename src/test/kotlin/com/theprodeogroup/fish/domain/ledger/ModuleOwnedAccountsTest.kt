package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ModuleOwnedAccountsTest {

    private val company = CompanyId.generate()

    private fun account(type: AccountType, code: String, kind: CashBookKind? = null) =
        Account.create(company, type, if (type.requiresClassification()) AccountClassification.CURRENT else null, code, "Acct $code", cashBookKind = kind)

    private fun useInstead(account: Account, clientType: ClientType = ClientType.COMPANY_LIMITED) = ModuleOwnedAccounts.useInstead(account, clientType)

    @Test
    fun `given a business chart, then receivables, payables, VAT, stock and fixed assets point to their own screens`() {
        useInstead(account(AccountType.ASSET, "1100")) shouldBe UseInstead.SALES_COLLECTION
        useInstead(account(AccountType.LIABILITY, "2000")) shouldBe UseInstead.PURCHASE_PAYMENT
        useInstead(account(AccountType.LIABILITY, "2150")) shouldBe UseInstead.VAT
        useInstead(account(AccountType.ASSET, "1300")) shouldBe UseInstead.INVENTORY
        useInstead(account(AccountType.ASSET, "1200")) shouldBe UseInstead.FIXED_ASSETS
        useInstead(account(AccountType.ASSET, "1210")) shouldBe UseInstead.FIXED_ASSETS
    }

    @Test
    fun `given the opening-figure accounts, then they point to opening figures for every client type`() {
        for (type in ClientType.entries) {
            useInstead(account(AccountType.EQUITY, "3900"), type) shouldBe UseInstead.OPENING_FIGURES
            useInstead(account(AccountType.EQUITY, "3910"), type) shouldBe UseInstead.OPENING_FIGURES
        }
    }

    @Test
    fun `given another cash or bank book, then it points to Move money`() {
        useInstead(account(AccountType.ASSET, "1010", CashBookKind.BANK)) shouldBe UseInstead.TRANSFER
        useInstead(account(AccountType.ASSET, "1000", CashBookKind.CASH)) shouldBe UseInstead.TRANSFER
    }

    @Test
    fun `given an ordinary income, expense, equity or loan account, then it is allowed`() {
        useInstead(account(AccountType.REVENUE, "4000")) shouldBe null
        useInstead(account(AccountType.EXPENSE, "5000")) shouldBe null
        useInstead(account(AccountType.EQUITY, "3000")) shouldBe null
        useInstead(account(AccountType.LIABILITY, "2100")) shouldBe null
    }

    @Test
    fun `given an Individual chart, then 1100 Investments and 2000 Loans are ordinary accounts, not receivables or payables`() {
        useInstead(account(AccountType.ASSET, "1100"), ClientType.INDIVIDUAL) shouldBe null
        useInstead(account(AccountType.LIABILITY, "2000"), ClientType.INDIVIDUAL) shouldBe null
    }

    @Test
    fun `given the template of every business type, then every account the rule reserves really exists in it by that code and type`() {
        for (type in ClientType.entries.filter { it != ClientType.INDIVIDUAL }) {
            val accounts = ChartOfAccountsTemplate.accountsFor(type, company)
            val reserved = accounts.mapNotNull { a -> ModuleOwnedAccounts.useInstead(a, type)?.let { a.code to it } }.toMap()

            reserved["1100"] shouldBe UseInstead.SALES_COLLECTION
            reserved["2000"] shouldBe UseInstead.PURCHASE_PAYMENT
            reserved["2150"] shouldBe UseInstead.VAT
        }
    }
}
