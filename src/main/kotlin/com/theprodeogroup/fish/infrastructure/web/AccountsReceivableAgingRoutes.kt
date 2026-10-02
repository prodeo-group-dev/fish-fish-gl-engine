package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeAccountsReceivableAgingResult
import com.theprodeogroup.fish.application.ComputeAccountsReceivableAgingUseCase
import com.theprodeogroup.fish.domain.sales.CustomerId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * `POST /companies/{companyId}/accounts-receivable-aging` -
 * [ComputeAccountsReceivableAgingUseCase]'s inbound HTTP surface, the
 * bucketed mirror of [customerBalancesRoutes]. `POST`, not `GET`, for the
 * same reason - the caller-supplied `customerIds` list can be long enough
 * to matter as a request body. Read-only, so [authorizeTenantForRead]
 * like every other posting-context/balances route.
 */
fun Route.accountsReceivableAgingRoutes(
    computeAccountsReceivableAgingUseCase: ComputeAccountsReceivableAgingUseCase,
    companyRepository: CompanyRepository
) {
    post("/companies/{companyId}/accounts-receivable-aging") {
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

        val request = call.receive<ComputeAccountsReceivableAgingRequestDto>()
        val customerIds = mutableListOf<CustomerId>()
        for (raw in request.customerIds) {
            val uuid = call.parseUuid(raw) ?: return@post
            customerIds.add(CustomerId(uuid))
        }

        when (val result = computeAccountsReceivableAgingUseCase.execute(companyId, customerIds)) {
            is ComputeAccountsReceivableAgingResult.Success -> call.respond(
                ComputeAccountsReceivableAgingResponseDto(
                    result.aging.map { customerAging ->
                        CustomerAgingDto(
                            customerAging.customerId.value.toString(),
                            customerAging.buckets.map { AgingBucketAmountDto(it.label.name, it.amount.amount.toPlainString(), it.amount.currency.currencyCode) }
                        )
                    }
                )
            )
            ComputeAccountsReceivableAgingResult.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ComputeAccountsReceivableAgingResult.ArControlAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("ar_control_account_not_configured", "This Company's Chart of Accounts has no Accounts Receivable control account (code 1100)"))
        }
    }
}
