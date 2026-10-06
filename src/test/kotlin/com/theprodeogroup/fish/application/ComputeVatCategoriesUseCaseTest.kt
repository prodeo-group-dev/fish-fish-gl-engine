package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Currency

private val GBP: Currency = Currency.getInstance("GBP")
private val ON = LocalDate.of(2026, 10, 6)

class ComputeVatCategoriesUseCaseTest {

    private val companyRepository = FakeCompanyRepository()
    private val vatRateRepository = FakeVatRateRepository()
    private val useCase = ComputeVatCategoriesUseCase(companyRepository, vatRateRepository)
    private val tenantId = TenantId.generate()

    private fun company(jurisdiction: Jurisdiction) =
        Company.create(tenantId, "Co", ClientType.COMPANY_LIMITED, jurisdiction, GBP).also { companyRepository.save(it) }

    @Test
    fun `given a nonexistent Company, when computed, then it returns CompanyNotFound`() {
        useCase.execute(CompanyId.generate(), ON).shouldBeInstanceOf<ComputeVatCategoriesUseCase.Result.CompanyNotFound>()
    }

    @Test
    fun `given an Irish Company, when computed, then it reads the same table record-sale posts against - six categories`() {
        val result = useCase.execute(company(Jurisdiction.IE).id, ON)
            .shouldBeInstanceOf<ComputeVatCategoriesUseCase.Result.Success>()

        result.jurisdiction shouldBe Jurisdiction.IE
        result.scheduleConfigured shouldBe true
        result.categories.map { it.category } shouldBe VatCategory.entries
    }

    @Test
    fun `given a jurisdiction whose only rows are unverified, when computed, then the schedule is not configured and categories is empty`() {
        val result = useCase.execute(company(Jurisdiction.SL).id, ON)
            .shouldBeInstanceOf<ComputeVatCategoriesUseCase.Result.Success>()

        result.scheduleConfigured shouldBe false
        result.categories shouldBe emptyList()
    }
}
