package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.tax.RateSource
import com.theprodeogroup.fish.domain.tax.TestVatRates
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tax.VatRateRepository
import com.theprodeogroup.fish.domain.tax.VatRateSchedule
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Currency

/**
 * GL-T-34: where a posting gets the VAT rates is a port ([RateSource]), so the source can change (today GL's own table, later
 * that same table refreshed from the Tax service) without touching a posting. Behaviour-neutral: the repository IS a RateSource
 * and answers exactly as before; the reads depend on the port, not on the table.
 */
class RateSourceTest {

    private val gbp = Currency.getInstance("GBP")
    private val on = LocalDate.of(2026, 10, 6)
    private val companyRepository = FakeCompanyRepository()

    private fun company(jurisdiction: Jurisdiction) =
        Company.create(TenantId.generate(), "Co", ClientType.COMPANY_LIMITED, jurisdiction, gbp).also { companyRepository.save(it) }

    @Test
    fun `the VAT rate repository is a RateSource and serves the same schedule through the port as directly`() {
        val repository: VatRateRepository = FakeVatRateRepository()
        val source: RateSource = repository

        source.findVerifiedScheduleFor(Jurisdiction.UK)!!.rateFor(VatCategory.STANDARD, on) shouldBe repository.findVerifiedScheduleFor(Jurisdiction.UK)!!.rateFor(VatCategory.STANDARD, on)
        source.findVerifiedScheduleFor(Jurisdiction.SL) shouldBe null // loaded unverified: no schedule through the port either
    }

    @Test
    fun `the categories read depends on the port, so any RateSource drives it`() {
        val ukOnly = RateSource { jurisdiction -> if (jurisdiction == Jurisdiction.UK) VatRateSchedule.of(TestVatRates.UK_ROWS) else null }
        val useCase = ComputeVatCategoriesUseCase(companyRepository, ukOnly)

        val uk = useCase.execute(company(Jurisdiction.UK).id, on).shouldBeInstanceOf<ComputeVatCategoriesUseCase.Result.Success>()
        val ie = useCase.execute(company(Jurisdiction.IE).id, on).shouldBeInstanceOf<ComputeVatCategoriesUseCase.Result.Success>()

        uk.scheduleConfigured shouldBe true
        uk.categories.map { it.category } shouldBe listOf(VatCategory.STANDARD, VatCategory.REDUCED, VatCategory.ZERO_RATED, VatCategory.EXEMPT).sortedBy { it.ordinal }
        ie.scheduleConfigured shouldBe false // the port has no schedule for IE: never a fallback to another jurisdiction's
        ie.categories shouldBe emptyList()
    }
}
