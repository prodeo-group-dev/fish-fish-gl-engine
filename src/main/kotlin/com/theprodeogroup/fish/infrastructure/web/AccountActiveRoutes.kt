package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeAccountBalanceUseCase
import com.theprodeogroup.fish.application.SetAccountActiveUseCase
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.put
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * `PUT /companies/{companyId}/accounts/{accountId}/active` with `{"active": false}` or `{"active": true}` (Femi, 2026-10-23: the Business
 * owner determines the life span of his accounts). People only: no service credential is on any allow-list for it.
 *
 * Who may do it: the Tenant's Owner-Admin, signed in as a person ([authorizeTenantOwnerAdmin]). **A stop-gap**, stated as one: that gate is
 * Tenant-wide, not per-Company, and the RBAC SPUTO is frozen, so this adds no new role and changes no RBAC; the per-Company ADMIN level
 * would not do, because the Owner-Admin only holds READ at a Company unless assigned more. When the RBAC lands, this is the one line to move.
 *
 * Refusals: 404 `account_not_found` (an unknown account, or another Company's); 409 `account_required_by_system` (the Cash Book, a control or
 * module-owned account, Suspense). A balance never blocks it, and the answer carries the balance as at today.
 */
fun Route.accountActiveRoutes(
    setAccountActiveUseCase: SetAccountActiveUseCase,
    computeAccountBalanceUseCase: ComputeAccountBalanceUseCase,
    companyRepository: CompanyRepository
) {
    put("/companies/{companyId}/accounts/{accountId}/active") {
        val companyUuid = call.parseUuid(call.parameters["companyId"] ?: "") ?: return@put
        val accountUuid = call.parseUuid(call.parameters["accountId"] ?: "") ?: return@put
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@put
        if (!call.verifyClaimedTenant(tenantId)) return@put
        call.authorizeTenantOwnerAdmin(tenantId) ?: return@put

        val request = try {
            Json.decodeFromString(SetAccountActiveRequestDto.serializer(), call.receiveText())
        } catch (e: SerializationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "active must be true or false"))
            return@put
        }
        val accountId = AccountId(accountUuid)
        when (val result = setAccountActiveUseCase.execute(companyId, accountId, request.active)) {
            is SetAccountActiveUseCase.Result.Success -> {
                val balance = computeAccountBalanceUseCase.execute(companyId, accountId, null) as? ComputeAccountBalanceUseCase.Result.Success
                    ?: error("The balance of an account that was just changed could not be read")
                call.respond(
                    AccountActiveDto(
                        accountId = result.account.id.value.toString(),
                        code = result.account.code,
                        name = result.account.name,
                        active = result.account.active,
                        currency = balance.currency.currencyCode,
                        balance = balance.balance.amount.toPlainString(),
                        balanceSide = balance.side.name
                    )
                )
            }
            SetAccountActiveUseCase.Result.AccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("account_not_found", "Account not found"))
            SetAccountActiveUseCase.Result.RequiredBySystem ->
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponseDto("account_required_by_system", "The system posts to this account, so it stays in use. Add another account if you need a different one.")
                )
        }
    }
}
