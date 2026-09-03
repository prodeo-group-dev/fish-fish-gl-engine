package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.fixedassets.AssetCategory
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

class CreateFixedAssetUseCaseTest {

    private val companyRepository = FakeCompanyRepository()
    private val fixedAssetRepository = FakeFixedAssetRepository()
    private val useCase = CreateFixedAssetUseCase(companyRepository, fixedAssetRepository)

    private fun company(): Company {
        val company = Company.create(TenantId.generate(), "Purse UK", ClientType.NON_PROFIT, "GB", GBP)
        companyRepository.save(company)
        return company
    }

    @Test
    fun `given a valid request, when executed, then it creates and saves a FixedAsset register entry`() {
        val company = company()

        val result = useCase.execute(
            CreateFixedAssetUseCase.Request(
                company.id, "Delivery Van", AssetCategory.VEHICLES, Money(BigDecimal("15000.00"), GBP), TODAY, 5
            )
        )

        val success = result.shouldBeInstanceOf<CreateFixedAssetUseCase.Result.Success>()
        success.fixedAsset.name shouldBe "Delivery Van"
        success.fixedAsset.netBookValue shouldBe Money(BigDecimal("15000.00"), GBP)
        fixedAssetRepository.saveCalls shouldBe listOf(success.fixedAsset.id)
    }

    @Test
    fun `given a nonexistent Company, when executed, then it returns CompanyNotFound`() {
        val result = useCase.execute(
            CreateFixedAssetUseCase.Request(
                CompanyId.generate(), "Delivery Van", AssetCategory.VEHICLES, Money(BigDecimal("15000.00"), GBP), TODAY, 5
            )
        )

        result.shouldBeInstanceOf<CreateFixedAssetUseCase.Result.CompanyNotFound>()
    }

    @Test
    fun `given a non-positive cost, when executed, then it returns InvalidFixedAsset`() {
        val company = company()

        val result = useCase.execute(
            CreateFixedAssetUseCase.Request(
                company.id, "Delivery Van", AssetCategory.VEHICLES, Money(BigDecimal.ZERO, GBP), TODAY, 5
            )
        )

        result.shouldBeInstanceOf<CreateFixedAssetUseCase.Result.InvalidFixedAsset>()
    }

    @Test
    fun `given Land with a useful life, when executed, then it returns InvalidFixedAsset`() {
        val company = company()

        val result = useCase.execute(
            CreateFixedAssetUseCase.Request(
                company.id, "Plot 12", AssetCategory.LAND, Money(BigDecimal("100000.00"), GBP), TODAY, 10
            )
        )

        result.shouldBeInstanceOf<CreateFixedAssetUseCase.Result.InvalidFixedAsset>()
    }
}
