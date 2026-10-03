package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeAccountsPayableAgingResult
import com.theprodeogroup.fish.application.ComputeAccountsPayableAgingUseCase
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
 * `POST /companies/{companyId}/accounts-payable-aging` -
 * [ComputeAccountsPayableAgingUseCase]'s inbound HTTP surface, the AP
 * mirror of [accountsReceivableAgingRoutes]. `POST`, not `GET`, for the
 * same reason - the caller-supplied `supplierIds` list can be long enough
 * to matter as a request body. Read-only, so [authorizeTenantForRead]
 * like every other posting-context/balances route.
 */
fun Route.accountsPayableAgingRoutes(
    computeAccountsPayableAgingUseCase: ComputeAccountsPayableAgingUseCase,
    companyRepository: CompanyRepository
) {
    post("/companies/{companyId}/accounts-payable-aging") {
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

        val request = call.receive<ComputeAccountsPayableAgingRequestDto>()
        val supplierIds = mutableListOf<SupplierId>()
        for (raw in request.supplierIds) {
            val uuid = call.parseUuid(raw) ?: return@post
            supplierIds.add(SupplierId(uuid))
        }

        when (val result = computeAccountsPayableAgingUseCase.execute(companyId, supplierIds)) {
            is ComputeAccountsPayableAgingResult.Success -> call.respond(
                ComputeAccountsPayableAgingResponseDto(
                    result.aging.map { supplierAging ->
                        SupplierAgingDto(
                            supplierAging.supplierId.value.toString(),
                            supplierAging.buckets.map { AgingBucketAmountDto(it.label.name, it.amount.amount.toPlainString(), it.amount.currency.currencyCode) }
                        )
                    }
                )
            )
            ComputeAccountsPayableAgingResult.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeAccountsPayableAgingResult.ApControlAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("ap_control_account_not_configured", "This Company's Chart of Accounts has no Accounts Payable control account (code 2000)"))
        }
    }
}
