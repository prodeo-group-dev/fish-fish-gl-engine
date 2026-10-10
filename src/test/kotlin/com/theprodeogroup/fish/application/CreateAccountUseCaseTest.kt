package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.CashBookKind
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")

/** `CreateAccountUseCase` (2026-09-03, "We need work on the Chart of Accounts. There is no setup for it."). */
class CreateAccountUseCaseTest {

    private fun company(): CompanyId {
        val companyRepository = FakeCompanyRepository()
        val tenantId = TenantId.generate()
        val company = Company.create(tenantId, "Acme Ltd", ClientType.COMPANY_LIMITED, Jurisdiction.UK, GBP)
        companyRepository.save(company)
        return company.id
    }

    private fun useCase(companyRepository: FakeCompanyRepository, accountRepository: FakeAccountRepository) =
        CreateAccountUseCase(companyRepository, accountRepository)

    @Test
    fun `given a valid request, when executed, then it creates and persists the Account`() {
        val companyRepository = FakeCompanyRepository()
        val tenantId = TenantId.generate()
        val company = Company.create(tenantId, "Acme Ltd", ClientType.COMPANY_LIMITED, Jurisdiction.UK, GBP)
        companyRepository.save(company)
        val accountRepository = FakeAccountRepository()

        val result = useCase(companyRepository, accountRepository).execute(
            CreateAccountUseCase.Request(company.id, AccountType.ASSET, AccountClassification.NON_CURRENT, "1250", "Company Vehicles")
        )

        val success = result.shouldBeInstanceOf<CreateAccountUseCase.Result.Success>()
        success.account.code shouldBe "1250"
        success.account.name shouldBe "Company Vehicles"
        success.account.classification shouldBe AccountClassification.NON_CURRENT
        accountRepository.findAllByCompany(company.id).map { it.code } shouldBe listOf("1250")
    }

    @Test
    fun `given a Liability account classified Long term, when executed, then it stores NON_CURRENT`() {
        val companyRepository = FakeCompanyRepository()
        val tenantId = TenantId.generate()
        val company = Company.create(tenantId, "Acme Ltd", ClientType.COMPANY_LIMITED, Jurisdiction.UK, GBP)
        companyRepository.save(company)
        val accountRepository = FakeAccountRepository()

        val result = useCase(companyRepository, accountRepository).execute(
            CreateAccountUseCase.Request(company.id, AccountType.LIABILITY, AccountClassification.NON_CURRENT, "2200", "Bank Loan")
        )

        val success = result.shouldBeInstanceOf<CreateAccountUseCase.Result.Success>()
        success.account.classification shouldBe AccountClassification.NON_CURRENT
    }

    @Test
    fun `given a nonexistent Company, when executed, then it reports not found`() {
        val result = useCase(FakeCompanyRepository(), FakeAccountRepository()).execute(
            CreateAccountUseCase.Request(CompanyId.generate(), AccountType.ASSET, AccountClassification.CURRENT, "1300", "Inventory")
        )

        result shouldBe CreateAccountUseCase.Result.CompanyNotFound
    }

    @Test
    fun `given a code already in use by this Company, when executed, then it fails`() {
        val companyRepository = FakeCompanyRepository()
        val tenantId = TenantId.generate()
        val company = Company.create(tenantId, "Acme Ltd", ClientType.COMPANY_LIMITED, Jurisdiction.UK, GBP)
        companyRepository.save(company)
        val accountRepository = FakeAccountRepository()
        useCase(companyRepository, accountRepository).execute(
            CreateAccountUseCase.Request(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1300", "Inventory")
        )

        val result = useCase(companyRepository, accountRepository).execute(
            CreateAccountUseCase.Request(company.id, AccountType.ASSET, AccountClassification.CURRENT, "1300", "Duplicate")
        )

        result shouldBe CreateAccountUseCase.Result.DuplicateCode("1300")
    }

    @Test
    fun `given an Asset account with no classification, when executed, then it fails - classification is required for Asset Liability`() {
        val companyRepository = FakeCompanyRepository()
        val tenantId = TenantId.generate()
        val company = Company.create(tenantId, "Acme Ltd", ClientType.COMPANY_LIMITED, Jurisdiction.UK, GBP)
        companyRepository.save(company)
        val accountRepository = FakeAccountRepository()

        val result = useCase(companyRepository, accountRepository).execute(
            CreateAccountUseCase.Request(company.id, AccountType.ASSET, null, "1300", "Inventory")
        )

        result.shouldBeInstanceOf<CreateAccountUseCase.Result.InvalidAccount>()
    }

    // ---- cash and bank accounts (docs/GL_Cash_And_Bank_Books_SRS.md, FR-CB02/CB06) ----

    private class CashBookFixture {
        val companyRepository = FakeCompanyRepository()
        val accountRepository = FakeAccountRepository()
        val company = Company.create(TenantId.generate(), "Acme Ltd", ClientType.COMPANY_LIMITED, Jurisdiction.UK, GBP)
            .also { companyRepository.save(it) }
        val useCase = CreateAccountUseCase(companyRepository, accountRepository)

        fun create(code: String, kind: CashBookKind?, type: AccountType = AccountType.ASSET, name: String = "Barclays") =
            useCase.execute(
                CreateAccountUseCase.Request(
                    company.id, type, if (type.requiresClassification()) AccountClassification.CURRENT else null, code, name, cashBookKind = kind
                )
            )

        fun take(code: String) {
            accountRepository.save(Account.create(company.id, AccountType.ASSET, AccountClassification.CURRENT, code, "Taken $code"))
        }
    }

    @Test
    fun `given a bank account with an explicit code, when created, then it carries the BANK kind`() {
        val f = CashBookFixture()

        val result = f.create("1015", CashBookKind.BANK).shouldBeInstanceOf<CreateAccountUseCase.Result.Success>()

        result.account.code shouldBe "1015"
        result.account.cashBookKind shouldBe CashBookKind.BANK
    }

    @Test
    fun `given a bank account with no code, when created, then GL assigns the next free 10xx from 1010`() {
        val f = CashBookFixture()
        f.take("1010")
        f.take("1011")

        val result = f.create("", CashBookKind.BANK).shouldBeInstanceOf<CreateAccountUseCase.Result.Success>()

        result.account.code shouldBe "1012"
    }

    @Test
    fun `given every code from 1010 to 1099 taken, when a coded-by-GL bank account is created, then it is refused`() {
        val f = CashBookFixture()
        (1010..1099).forEach { f.take(it.toString()) }

        f.create("", CashBookKind.BANK).shouldBeInstanceOf<CreateAccountUseCase.Result.InvalidAccount>()
    }

    @Test
    fun `given an ordinary account with no code, when created, then it is refused - only cash and bank accounts get a code from GL`() {
        val f = CashBookFixture()

        f.create("", null).shouldBeInstanceOf<CreateAccountUseCase.Result.InvalidAccount>()
    }

    @Test
    fun `given a non-asset account with a cash or bank kind, when created, then it is refused`() {
        val f = CashBookFixture()

        f.create("2500", CashBookKind.BANK, AccountType.LIABILITY).shouldBeInstanceOf<CreateAccountUseCase.Result.InvalidAccount>()
    }
}
