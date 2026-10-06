package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.tax.VatCategoryRate
import com.theprodeogroup.fish.domain.tax.VatRateRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import java.time.LocalDate

/**
 * `GET /companies/{companyId}/vat-categories` (2026-10-06, WEB's request,
 * Femi agreed): the VAT categories and rates a Company may use on a sale
 * line as of a date, read from the same rate table (`vat_rates`) that
 * `POST /sales/record-sale` posts against - so the Sales form offers
 * exactly what a posting will accept, never a copy of the rates.
 *
 * A jurisdiction with no VERIFIED rates answers [Success] with
 * `scheduleConfigured = false` and no categories - a fact about the
 * Company, not a failed request - mirroring what `record-sale` does for
 * the same Company (409 `no_vat_rate_schedule`).
 */
class ComputeVatCategoriesUseCase(
    private val companyRepository: CompanyRepository,
    private val vatRateRepository: VatRateRepository
) {
    sealed class Result {
        data class Success(
            val jurisdiction: Jurisdiction,
            val asOf: LocalDate,
            val scheduleConfigured: Boolean,
            val categories: List<VatCategoryRate>
        ) : Result()
        data object CompanyNotFound : Result()
    }

    fun execute(companyId: CompanyId, asOf: LocalDate): Result {
        val company = companyRepository.findById(companyId) ?: return Result.CompanyNotFound
        val schedule = vatRateRepository.findVerifiedScheduleFor(company.jurisdiction)
        return Result.Success(
            company.jurisdiction, asOf,
            scheduleConfigured = schedule != null,
            categories = schedule?.categoriesAsOf(asOf) ?: emptyList()
        )
    }
}
