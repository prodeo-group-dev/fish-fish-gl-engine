package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.fixedassets.AssetCategory
import com.theprodeogroup.fish.domain.fixedassets.FixedAsset
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val TODAY = LocalDate.of(2026, 9, 3)

class ComputeFixedAssetRegisterUseCaseTest {

    private val companyRepository = FakeCompanyRepository()
    private val fixedAssetRepository = FakeFixedAssetRepository()
    private val useCase = ComputeFixedAssetRegisterUseCase(companyRepository, fixedAssetRepository)

    private fun company(): Company {
        val company = Company.create(TenantId.generate(), "Purse UK", ClientType.NON_PROFIT, Jurisdiction.UK, GBP)
        companyRepository.save(company)
        return company
    }

    @Test
    fun `given two held assets and one disposed asset, when executed, then totals are struck only over the held assets`() {
        val company = company()
        val held1 = FixedAsset.create(company.id, "Delivery Van", AssetCategory.VEHICLES, Money(BigDecimal("10000.00"), GBP), TODAY, 5)
        val held2 = FixedAsset.create(company.id, "Plot 12", AssetCategory.LAND, Money(BigDecimal("50000.00"), GBP), TODAY)
        val disposed = FixedAsset.create(company.id, "Old Forklift", AssetCategory.EQUIPMENT, Money(BigDecimal("2000.00"), GBP), TODAY, 3)
        val expenseAccountId = com.theprodeogroup.fish.domain.ledger.AccountId.generate()
        val accumulatedDepreciationAccountId = com.theprodeogroup.fish.domain.ledger.AccountId.generate()
        val cashAccountId = com.theprodeogroup.fish.domain.ledger.AccountId.generate()
        val fixedAssetAccountId = com.theprodeogroup.fish.domain.ledger.AccountId.generate()
        val saleAccountId = com.theprodeogroup.fish.domain.ledger.AccountId.generate()
        val periodId = com.theprodeogroup.fish.domain.ledger.PeriodId.generate()
        disposed.dispose(Money(BigDecimal.ZERO, GBP), cashAccountId, fixedAssetAccountId, accumulatedDepreciationAccountId, saleAccountId, periodId, TODAY)
        fixedAssetRepository.save(held1)
        fixedAssetRepository.save(held2)
        fixedAssetRepository.save(disposed)

        val result = useCase.execute(company.id)

        val success = result.shouldBeInstanceOf<ComputeFixedAssetRegisterUseCase.Result.Success>()
        success.register.lines.size shouldBe 3
        success.register.totalCost shouldBe Money(BigDecimal("60000.00"), GBP)
        success.register.totalNetBookValue shouldBe Money(BigDecimal("60000.00"), GBP)
    }

    @Test
    fun `given a Company with no Fixed Asset Register entries, when executed, then it returns NoFixedAssetsForCompany`() {
        val company = company()

        val result = useCase.execute(company.id)

        result.shouldBeInstanceOf<ComputeFixedAssetRegisterUseCase.Result.NoFixedAssetsForCompany>()
    }

    @Test
    fun `given a nonexistent Company, when executed, then it returns CompanyNotFound`() {
        val result = useCase.execute(CompanyId.generate())

        result.shouldBeInstanceOf<ComputeFixedAssetRegisterUseCase.Result.CompanyNotFound>()
    }
}
