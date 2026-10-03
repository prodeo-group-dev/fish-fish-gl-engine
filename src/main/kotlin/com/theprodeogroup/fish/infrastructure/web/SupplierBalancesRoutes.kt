package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeSupplierBalancesResult
import com.theprodeogroup.fish.application.ComputeSupplierBalancesUseCase
import com.theprodeogroup.fish.domain.purchasing.SupplierId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * `POST /companies/{companyId}/supplier-balances` (UC-BO13 "View Cashflow
 * Position") - [ComputeSupplierBalancesUseCase]'s inbound HTTP surface,
 * mirroring `customerBalancesRoutes` exactly. `POST`, not `GET`, since
 * the caller-supplied `supplierIds` list can be long enough to matter as
 * a request body rather than a query string.
 */
fun Route.supplierBalancesRoutes(
    computeSupplierBalancesUseCase: ComputeSupplierBalancesUseCase,
    companyRepository: CompanyRepository
) {
    post("/companies/{companyId}/supplier-balances") {
        val companyIdRaw = call.parameters["companyId"]
        if (companyIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
            return@post
        }
        val companyUuid = call.parseUuid(companyIdRaw) ?: return@post
        val companyId = CompanyId(companyUuid)

        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForRead(tenantId, companyId) ?: return@post

        val request = call.receive<ComputeSupplierBalancesRequestDto>()
        val supplierIds = mutableListOf<SupplierId>()
        for (raw in request.supplierIds) {
            val uuid = call.parseUuid(raw) ?: return@post
            supplierIds.add(SupplierId(uuid))
        }

        when (val result = computeSupplierBalancesUseCase.execute(companyId, supplierIds)) {
            is ComputeSupplierBalancesResult.Success -> call.respond(
                ComputeSupplierBalancesResponseDto(
                    result.balances.map { SupplierBalanceDto(it.supplierId.value.toString(), it.balance.amount.toPlainString(), it.balance.currency.currencyCode) }
                )
            )
            ComputeSupplierBalancesResult.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeSupplierBalancesResult.ApControlAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("ap_control_account_not_configured", "This Company's Chart of Accounts has no Accounts Payable control account (code 2000)"))
        }
    }
}
