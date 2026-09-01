package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.domain.tenancy.TenantRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /me` - this codebase's first read-only/query route (every other
 * route so far posts a domain action). The tenant dashboard's whole
 * reason for existing: a returning signed-in caller needs to know
 * which Tenant(s) they belong to and each one's verification status
 * before the frontend can decide whether to show onboarding (no
 * Tenant yet) or the dashboard (one already exists) - a decision
 * App.tsx couldn't make at all before this route existed.
 *
 * No tenant-specific authorization needed (unlike every write route in
 * this package) - this only ever returns the caller's own
 * [AuthenticatedCaller.memberships], never another User's data, so
 * there's no X-Tenant-Id/`authorizeTenantForWrite` check to make.
 *
 * Resolves each Company's name, not just its id (2026-09-01, "on
 * logging in to fish I should be given the options to choose the
 * company I want to work on") - a caller with Memberships spanning
 * multiple Tenants and/or Companies needs real names to pick from, not
 * raw UUIDs.
 */
fun Route.meRoutes(tenantRepository: TenantRepository, companyRepository: CompanyRepository) {
    get("/me") {
        val caller = call.principal<AuthenticatedCaller>()
        if (caller == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "No authenticated caller"))
            return@get
        }

        val tenants = caller.memberships.mapNotNull { membership ->
            val tenant = tenantRepository.findById(membership.tenantId) ?: return@mapNotNull null
            val companies = tenant.companyIds.mapNotNull { companyId ->
                companyRepository.findById(companyId)?.let { CompanySummaryDto(it.id.value.toString(), it.name) }
            }
            MyTenantDto(
                tenantId = tenant.id.value.toString(),
                tenantName = tenant.name,
                role = membership.role.name,
                tenantStatus = tenant.status.name,
                kybStatus = tenant.kybStatus.name,
                adminKycStatus = tenant.adminKycStatus.name,
                adminPhoneNumber = tenant.adminPhoneNumber?.value,
                adminPhoneVerificationStatus = tenant.adminPhoneVerificationStatus.name,
                phoneVerificationDeadline = tenant.phoneVerificationDeadline?.toString(),
                companies = companies,
                grantedModules = membership.grantedModules.map { it.name }
            )
        }

        call.respond(
            MyProfileResponseDto(
                email = caller.user.email,
                name = caller.user.name,
                tenants = tenants
            )
        )
    }
}
