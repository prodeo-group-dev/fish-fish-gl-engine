package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.Currency

/**
 * The owner decides the life span of his accounts (Femi, 2026-10-23): inactivating is never blocked for a balance, open lines or unreconciled
 * items, only for an account the system itself posts to by code (ModuleOwnedAccounts.requiredBySystem); reactivating is always allowed;
 * a repeat is a no-op.
 */
class SetAccountActiveUseCaseTest {

    private val companies = FakeCompanyRepository()
    private val accounts = FakeAccountRepository()
    private val useCase = SetAccountActiveUseCase(companies, accounts)
    private val company = Company.create(TenantId.generate(), "Acme", ClientType.COMPANY_LIMITED, Jurisdiction.UK, Currency.getInstance("GBP")).also { companies.save(it) }

    private fun account(type: AccountType, code: String, kind: CashBookKind? = null, owner: CompanyId = company.id) =
        Account.create(owner, type, if (type.requiresClassification()) AccountClassification.CURRENT else null, code, "Acct $code", cashBookKind = kind)
            .also { accounts.save(it) }

    @Test
    fun `given an ordinary account, when the owner inactivates it, then it is inactive and saved, and reactivating makes it active again`() {
        val expense = account(AccountType.EXPENSE, "5800")

        useCase.execute(company.id, expense.id, false).shouldBeInstanceOf<SetAccountActiveUseCase.Result.Success>()
        accounts.findById(expense.id)!!.active shouldBe false

        useCase.execute(company.id, expense.id, true).shouldBeInstanceOf<SetAccountActiveUseCase.Result.Success>()
        accounts.findById(expense.id)!!.active shouldBe true
    }

    @Test
    fun `given an account already in the asked state, when it is asked again, then it is a success and nothing changes`() {
        val expense = account(AccountType.EXPENSE, "5800")
        useCase.execute(company.id, expense.id, true).shouldBeInstanceOf<SetAccountActiveUseCase.Result.Success>()

        useCase.execute(company.id, expense.id, false).shouldBeInstanceOf<SetAccountActiveUseCase.Result.Success>()
        useCase.execute(company.id, expense.id, false).shouldBeInstanceOf<SetAccountActiveUseCase.Result.Success>()

        accounts.findById(expense.id)!!.active shouldBe false
    }

    @Test
    fun `given an account the system posts to, when the owner inactivates it, then it is refused as required by the system and stays active`() {
        val cash = account(AccountType.ASSET, "1000", CashBookKind.CASH)
        val vat = account(AccountType.LIABILITY, "2150")
        val suspense = account(AccountType.EQUITY, "3910")

        for (a in listOf(cash, vat, suspense)) {
            useCase.execute(company.id, a.id, false) shouldBe SetAccountActiveUseCase.Result.RequiredBySystem
            accounts.findById(a.id)!!.active shouldBe true
        }
    }

    @Test
    fun `given a bank book, when the owner inactivates it, then it is allowed - only account 1000 is the Cash Book`() {
        val bank = account(AccountType.ASSET, "1015", CashBookKind.BANK)

        useCase.execute(company.id, bank.id, false).shouldBeInstanceOf<SetAccountActiveUseCase.Result.Success>()

        accounts.findById(bank.id)!!.active shouldBe false
    }

    @Test
    fun `given an account of another Company or none, when it is asked, then it is not found and unchanged`() {
        val foreign = account(AccountType.EXPENSE, "5800", owner = CompanyId.generate())

        useCase.execute(company.id, foreign.id, false) shouldBe SetAccountActiveUseCase.Result.AccountNotFound
        accounts.findById(foreign.id)!!.active shouldBe true
        useCase.execute(company.id, AccountId.generate(), false) shouldBe SetAccountActiveUseCase.Result.AccountNotFound
    }

    @Test
    fun `given a system account that is somehow already inactive, when the owner reactivates it, then it is allowed`() {
        val vat = account(AccountType.LIABILITY, "2150").also { it.deactivate(); accounts.save(it) }

        useCase.execute(company.id, vat.id, true).shouldBeInstanceOf<SetAccountActiveUseCase.Result.Success>()

        accounts.findById(vat.id)!!.active shouldBe true
    }
}
