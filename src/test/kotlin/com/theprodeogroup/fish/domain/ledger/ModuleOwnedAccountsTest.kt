package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.assertions.withClue
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
    fun `given the Discounts Received account, then a cash book may not post straight to it, and Purchases is named instead`() {
        useInstead(account(AccountType.REVENUE, "4920")) shouldBe UseInstead.PURCHASE_PAYMENT
        // Only the revenue account at that code: the same code as another type is not the reserved account.
        useInstead(account(AccountType.EXPENSE, "4920")) shouldBe null
        // An Individual's chart has no such account and reserves nothing here.
        useInstead(account(AccountType.REVENUE, "4920"), ClientType.INDIVIDUAL) shouldBe null
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
            reserved["4920"] shouldBe UseInstead.PURCHASE_PAYMENT
        }
    }

    @Test
    fun `given a business chart, then the accounts the system posts to by code are required by the system and may not be inactivated`() {
        val required = listOf(
            AccountType.ASSET to "1000", AccountType.ASSET to "1100", AccountType.ASSET to "1200", AccountType.ASSET to "1210", AccountType.ASSET to "1300",
            AccountType.LIABILITY to "2000", AccountType.LIABILITY to "2150", AccountType.LIABILITY to "2200", AccountType.LIABILITY to "2300",
            AccountType.EQUITY to "3900", AccountType.EQUITY to "3910", AccountType.REVENUE to "4920",
            AccountType.EXPENSE to "5200", AccountType.EXPENSE to "5300", AccountType.EXPENSE to "5400"
        )
        for ((type, code) in required) {
            withClue("$type $code") { ModuleOwnedAccounts.requiredBySystem(account(type, code), ClientType.COMPANY_LIMITED) shouldBe true }
        }
    }

    @Test
    fun `given an ordinary account of the owner or a cash or bank book, then it is not required by the system`() {
        ModuleOwnedAccounts.requiredBySystem(account(AccountType.EXPENSE, "5800"), ClientType.COMPANY_LIMITED) shouldBe false
        ModuleOwnedAccounts.requiredBySystem(account(AccountType.REVENUE, "4100"), ClientType.COMPANY_LIMITED) shouldBe false
        // A second bank account or a petty-cash book may be inactivated by its owner; only account 1000 is the Cash Book.
        ModuleOwnedAccounts.requiredBySystem(account(AccountType.ASSET, "1015", CashBookKind.BANK), ClientType.COMPANY_LIMITED) shouldBe false
        // The same code at another type is not the reserved account.
        ModuleOwnedAccounts.requiredBySystem(account(AccountType.EXPENSE, "4920"), ClientType.COMPANY_LIMITED) shouldBe false
    }

    @Test
    fun `given an Individual chart, then only the cash book and the opening-figure accounts are required by the system`() {
        ModuleOwnedAccounts.requiredBySystem(account(AccountType.ASSET, "1000"), ClientType.INDIVIDUAL) shouldBe true
        ModuleOwnedAccounts.requiredBySystem(account(AccountType.EQUITY, "3900"), ClientType.INDIVIDUAL) shouldBe true
        ModuleOwnedAccounts.requiredBySystem(account(AccountType.EQUITY, "3910"), ClientType.INDIVIDUAL) shouldBe true
        // An Individual chart uses 1100 for Investments and 2000 for Loans: the owner's own accounts.
        ModuleOwnedAccounts.requiredBySystem(account(AccountType.ASSET, "1100"), ClientType.INDIVIDUAL) shouldBe false
        ModuleOwnedAccounts.requiredBySystem(account(AccountType.LIABILITY, "2000"), ClientType.INDIVIDUAL) shouldBe false
    }
}

