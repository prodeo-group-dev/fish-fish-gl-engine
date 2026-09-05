package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.RecordAdminPhoneNumberUseCase
import com.theprodeogroup.fish.domain.tenancy.PhoneNumber
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * `POST /tenants/{tenantId}/admin-phone` - the write side of the
 * 2026-08-27 KYB extension (`tenant.kt`'s own KDoc on why a verified
 * admin phone number is part of KYB, not a separate track). The
 * frontend is expected to have already driven Cognito's own
 * `phone_number` verification (an SMS code sent/confirmed entirely
 * within Cognito) before calling this - this route's whole job is
 * confirming that really happened server-side
 * ([RecordAdminPhoneNumberUseCase]/`AdminPhoneVerificationChecker`)
 * rather than trusting the request body outright.
 */
fun Route.adminPhoneRoutes(recordAdminPhoneNumberUseCase: RecordAdminPhoneNumberUseCase) {
    post("/tenants/{tenantId}/admin-phone") {
        val tenantIdRaw = call.parameters["tenantId"]
        if (tenantIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "tenantId path parameter is required"))
            return@post
        }
        val tenantUuid = call.parseUuid(tenantIdRaw) ?: return@post
        val tenantId = TenantId(tenantUuid)

        val caller = call.authorizeTenantForWrite(tenantId) ?: return@post

        val request = call.receive<RecordAdminPhoneNumberRequestDto>()
        val phoneNumber = try {
            PhoneNumber(request.phoneNumber)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", e.message ?: "Invalid phoneNumber"))
            return@post
        }

        when (val result = recordAdminPhoneNumberUseCase.execute(tenantId, caller.email, phoneNumber)) {
            is RecordAdminPhoneNumberUseCase.Result.Success -> call.respond(
                HttpStatusCode.OK,
                RecordAdminPhoneNumberResponseDto(
                    tenantId = result.tenant.id.value.toString(),
                    adminPhoneVerificationStatus = result.tenant.adminPhoneVerificationStatus.name
                )
            )
            RecordAdminPhoneNumberUseCase.Result.TenantNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("tenant_not_found", "Tenant not found"))
            RecordAdminPhoneNumberUseCase.Result.NotVerifiedByCognito ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponseDto(
                        "phone_not_verified",
                        "This phone number hasn't been verified yet - confirm the SMS code first, then try again"
                    )
                )
        }
    }
}
