package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountType
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
        val company = Company.create(tenantId, "Acme Ltd", ClientType.COMPANY_LIMITED, "GB", GBP)
        companyRepository.save(company)
        return company.id
    }

    private fun useCase(companyRepository: FakeCompanyRepository, accountRepository: FakeAccountRepository) =
        CreateAccountUseCase(companyRepository, accountRepository)

    @Test
    fun `given a valid request, when executed, then it creates and persists the Account`() {
        val companyRepository = FakeCompanyRepository()
        val tenantId = TenantId.generate()
        val company = Company.create(tenantId, "Acme Ltd", ClientType.COMPANY_LIMITED, "GB", GBP)
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
        val company = Company.create(tenantId, "Acme Ltd", ClientType.COMPANY_LIMITED, "GB", GBP)
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
        val company = Company.create(tenantId, "Acme Ltd", ClientType.COMPANY_LIMITED, "GB", GBP)
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
        val company = Company.create(tenantId, "Acme Ltd", ClientType.COMPANY_LIMITED, "GB", GBP)
        companyRepository.save(company)
        val accountRepository = FakeAccountRepository()

        val result = useCase(companyRepository, accountRepository).execute(
            CreateAccountUseCase.Request(company.id, AccountType.ASSET, null, "1300", "Inventory")
        )

        result.shouldBeInstanceOf<CreateAccountUseCase.Result.InvalidAccount>()
    }
}
