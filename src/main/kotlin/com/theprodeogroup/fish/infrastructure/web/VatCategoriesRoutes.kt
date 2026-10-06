package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeVatCategoriesUseCase
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * `GET /companies/{companyId}/vat-categories?asOf=YYYY-MM-DD` - see
 * [ComputeVatCategoriesUseCase]. Read-only reference data for the Company's
 * jurisdiction, so [authorizeTenantForRead] like the other read routes
 * (any caller with read access to the Company, the Owner-Admin's
 * intrinsic floor included). `asOf` is optional and defaults to today
 * (UTC).
 *
 * `ratePercent` is a plain decimal percent string ("23", "13.5", "0"), and
 * `null` for EXEMPT (outside VAT scope - not a 0% rate; ZERO_RATED is "0").
 */
fun Route.vatCategoriesRoutes(
    computeVatCategoriesUseCase: ComputeVatCategoriesUseCase,
    companyRepository: CompanyRepository
) {
    get("/companies/{companyId}/vat-categories") {
        val companyIdRaw = call.parameters["companyId"]
        if (companyIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
            return@get
        }
        val companyUuid = call.parseUuid(companyIdRaw) ?: return@get
        val companyId = CompanyId(companyUuid)

        val asOf = call.request.queryParameters["asOf"]?.let {
            try {
                LocalDate.parse(it)
            } catch (e: DateTimeParseException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "asOf must be ISO-8601 (YYYY-MM-DD)"))
                return@get
            }
        } ?: LocalDate.now(ZoneOffset.UTC)

        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        when (val result = computeVatCategoriesUseCase.execute(companyId, asOf)) {
            is ComputeVatCategoriesUseCase.Result.Success -> call.respond(
                VatCategoriesResponseDto(
                    jurisdiction = result.jurisdiction.code,
                    asOf = result.asOf.toString(),
                    vatScheduleConfigured = result.scheduleConfigured,
                    categories = result.categories.map {
                        VatCategoryRateDto(it.category.name, it.rate?.movePointRight(2)?.stripTrailingZeros()?.toPlainString())
                    }
                )
            )
            ComputeVatCategoriesUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
        }
    }
}
