package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeOpenPeriodUseCase
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /companies/{companyId}/open-period` - the Period a caller may post into: `{periodId, startDate, endDate,
 * status}`, or 409 `no_open_period` when the Company has none. People only (on no service allow-list); READ at the
 * Company through the standard sequence, so it adds no permission and changes nobody's access. It lets the screens
 * name the `periodId` that asset and other creates need without borrowing a posting context.
 */
fun Route.openPeriodRoutes(
    computeOpenPeriodUseCase: ComputeOpenPeriodUseCase,
    companyRepository: CompanyRepository
) {
    get("/companies/{companyId}/open-period") {
        val companyUuid = call.parseUuid(call.parameters["companyId"] ?: "") ?: return@get
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        when (val result = computeOpenPeriodUseCase.execute(companyId)) {
            is ComputeOpenPeriodUseCase.Result.Success -> call.respond(
                OpenPeriodResponseDto(
                    periodId = result.period.id.value.toString(),
                    startDate = result.period.startDate.toString(),
                    endDate = result.period.endDate.toString(),
                    status = result.period.status.name
                )
            )
            ComputeOpenPeriodUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Company not found"))
            ComputeOpenPeriodUseCase.Result.NoOpenPeriod ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("no_open_period", "This Company has no open Period"))
        }
    }
}
