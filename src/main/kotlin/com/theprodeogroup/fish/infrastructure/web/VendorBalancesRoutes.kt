package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeVendorBalancesResult
import com.theprodeogroup.fish.application.ComputeVendorBalancesUseCase
import com.theprodeogroup.fish.domain.purchasing.CreditorId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * `POST /companies/{companyId}/vendor-balances` (UC-BO13 "View Cashflow
 * Position") - [ComputeVendorBalancesUseCase]'s inbound HTTP surface,
 * mirroring `customerBalancesRoutes` exactly. `POST`, not `GET`, since
 * the caller-supplied `creditorIds` list can be long enough to matter as
 * a request body rather than a query string.
 */
fun Route.vendorBalancesRoutes(
    computeVendorBalancesUseCase: ComputeVendorBalancesUseCase,
    companyRepository: CompanyRepository
) {
    post("/companies/{companyId}/vendor-balances") {
        val companyIdRaw = call.parameters["companyId"]
        if (companyIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
            return@post
        }
        val companyUuid = call.parseUuid(companyIdRaw) ?: return@post
        val companyId = CompanyId(companyUuid)

        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForRead(tenantId) ?: return@post

        val request = call.receive<ComputeVendorBalancesRequestDto>()
        val creditorIds = mutableListOf<CreditorId>()
        for (raw in request.creditorIds) {
            val uuid = call.parseUuid(raw) ?: return@post
            creditorIds.add(CreditorId(uuid))
        }

        when (val result = computeVendorBalancesUseCase.execute(companyId, creditorIds)) {
            is ComputeVendorBalancesResult.Success -> call.respond(
                ComputeVendorBalancesResponseDto(
                    result.balances.map { VendorBalanceDto(it.creditorId.value.toString(), it.balance.amount.toPlainString(), it.balance.currency.currencyCode) }
                )
            )
            ComputeVendorBalancesResult.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeVendorBalancesResult.ApControlAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("ap_control_account_not_configured", "This Company's Chart of Accounts has no Accounts Payable control account (code 2000)"))
        }
    }
}
