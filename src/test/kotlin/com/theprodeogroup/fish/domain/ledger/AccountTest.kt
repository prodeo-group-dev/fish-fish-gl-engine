package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.tenancy.CompanyId
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Test

class AccountTest {

    @Test
    fun `given a valid Company ID, account type, code, and name, when an Account is created, then it's active and has no parent`() {
        val account = Account.create(
            companyId = CompanyId.generate(),
            type = AccountType.ASSET,
            classification = AccountClassification.CURRENT,
            code = "1000",
            name = "Cash and Bank"
        )

        account.active shouldBe true
        account.parentId shouldBe null
    }

    @Test
    fun `given an Asset type with no classification, when an Account is created, then it fails - classification is required`() {
        shouldThrow<IllegalArgumentException> {
            Account.create(
                companyId = CompanyId.generate(),
                type = AccountType.ASSET,
                classification = null,
                code = "1000",
                name = "Cash and Bank"
            )
        }
    }

    @Test
    fun `given a Liability type with no classification, when an Account is created, then it fails - classification is required`() {
        shouldThrow<IllegalArgumentException> {
            Account.create(
                companyId = CompanyId.generate(),
                type = AccountType.LIABILITY,
                classification = null,
                code = "2000",
                name = "Accounts Payable"
            )
        }
    }

    @Test
    fun `given an Equity type with a classification provided anyway, when an Account is created, then it fails - not applicable`() {
        shouldThrow<IllegalArgumentException> {
            Account.create(
                companyId = CompanyId.generate(),
                type = AccountType.EQUITY,
                classification = AccountClassification.NON_CURRENT,
                code = "3000",
                name = "Share Capital"
            )
        }
    }

    @Test
    fun `given a Revenue type with no classification, when an Account is created, then it succeeds`() {
        val account = Account.create(
            companyId = CompanyId.generate(),
            type = AccountType.REVENUE,
            classification = null,
            code = "4000",
            name = "Sales Revenue"
        )

        account.classification shouldBe null
    }

    @Test
    fun `given an attempt to make an Account its own parent, when created, then it fails`() {
        val id = AccountId.generate()

        shouldThrow<IllegalArgumentException> {
            Account.create(
                companyId = CompanyId.generate(),
                type = AccountType.EXPENSE,
                classification = null,
                code = "5000",
                name = "Rent",
                parentId = id,
                id = id
            )
        }
    }

    @Test
    fun `given a valid distinct parent, when a child Account is created, then the parent resolves correctly`() {
        val parentId = AccountId.generate()

        val child = Account.create(
            companyId = CompanyId.generate(),
            type = AccountType.EXPENSE,
            classification = null,
            code = "5100",
            name = "Office Rent",
            parentId = parentId
        )

        child.parentId shouldBe parentId
    }

    @Test
    fun `given an Account with no posted activity, when deletion is validated, then it succeeds`() {
        val account = readyAccount()

        account.validateDeletion().isValid shouldBe true
    }

    @Test
    fun `given an Account with posted activity, when deletion is validated, then it's rejected - deactivation is offered instead`() {
        val account = readyAccount()
        account.recordActivity()

        val result = account.validateDeletion()

        result.isValid shouldBe false
    }

    @Test
    fun `given an active Account, when deactivated, then it becomes inactive`() {
        val account = readyAccount()

        val result = account.deactivate()

        result.isValid shouldBe true
        account.active shouldBe false
    }

    @Test
    fun `given an already-inactive Account, when deactivated again, then it fails`() {
        val account = readyAccount()
        account.deactivate()

        account.deactivate().isValid shouldBe false
    }

    @Test
    fun `given an inactive Account, when reactivated, then it becomes active`() {
        val account = readyAccount()
        account.deactivate()

        val result = account.reactivate()

        result.isValid shouldBe true
        account.active shouldBe true
    }

    @Test
    fun `given AccountType normal balances, when checked, then Asset and Expense are DEBIT while Liability, Equity, and Revenue are CREDIT`() {
        AccountType.ASSET.normalBalance() shouldBe com.theprodeogroup.fish.domain.common.TransactionSide.DEBIT
        AccountType.EXPENSE.normalBalance() shouldBe com.theprodeogroup.fish.domain.common.TransactionSide.DEBIT
        AccountType.LIABILITY.normalBalance() shouldBe com.theprodeogroup.fish.domain.common.TransactionSide.CREDIT
        AccountType.EQUITY.normalBalance() shouldBe com.theprodeogroup.fish.domain.common.TransactionSide.CREDIT
        AccountType.REVENUE.normalBalance() shouldBe com.theprodeogroup.fish.domain.common.TransactionSide.CREDIT
    }

    @Test
    fun `given AccountType classification requirement, when checked, then only Asset and Liability require it`() {
        AccountType.ASSET.requiresClassification() shouldBe true
        AccountType.LIABILITY.requiresClassification() shouldBe true
        AccountType.EQUITY.requiresClassification() shouldBe false
        AccountType.REVENUE.requiresClassification() shouldBe false
        AccountType.EXPENSE.requiresClassification() shouldBe false
    }

    private fun readyAccount(): Account = Account.create(
        companyId = CompanyId.generate(),
        type = AccountType.ASSET,
        classification = AccountClassification.CURRENT,
        code = "1000",
        name = "Cash and Bank"
    )
}
