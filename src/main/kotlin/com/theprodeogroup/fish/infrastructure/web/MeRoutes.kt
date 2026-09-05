package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.infrastructure.ea.EaCallerLookupResult
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.request.header
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
 * this package) - this only ever returns the caller's own memberships
 * (resolved via EA, see [EaMembershipGatewayKey]), never another User's
 * data, so there's no X-Tenant-Id/`authorizeTenantForWrite` check to make.
 *
 * Resolves each Company's name, not just its id (2026-09-01, "on
 * logging in to fish I should be given the options to choose the
 * company I want to work on") - a caller with Memberships spanning
 * multiple Tenants and/or Companies needs real names to pick from, not
 * raw UUIDs.
 *
 * **[MyTenantDto.accessLevel] added 2026-09-04** ("each of GL/POP/IM/
 * SOP/HR" cross-repo authorization) - `/me` is this platform's only
 * externally-reachable way for POP/SOP/IM/HR to learn a caller's
 * `AccessLevel`/`grantedModules` for a given Tenant, since Membership
 * lives only in GL's own database. A calling service forwards the
 * caller's own bearer token here, finds the entry matching its own
 * configured Tenant, and checks `accessLevel`/`grantedModules` itself -
 * the same [AccessLevel.atLeast] comparison
 * [authorizeTenantForModule] already does in-process for GL's own
 * routes, just reachable over HTTP for a caller that isn't GL.
 */
fun Route.meRoutes(companyRepository: CompanyRepository) {
    get("/me") {
        // Not fishAuthenticated - /me is only ever reached with a human
        // caller's own token (see this file's own KDoc: a calling
        // service forwards *the human's* token here, never its own
        // service-account one), so there's no ServiceAccountCaller
        // branch to handle, unlike authorizeTenantFor* in Auth.kt.
        val caller = call.principal<AuthenticatedCaller>()
        if (caller == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "No authenticated caller"))
            return@get
        }
        val token = call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")
        if (token.isNullOrBlank()) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "No authenticated caller"))
            return@get
        }

        val gateway = call.application.attributes[EaMembershipGatewayKey]
        when (val result = gateway.lookupCaller(token)) {
            is EaCallerLookupResult.Unauthorized -> {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "No authenticated caller"))
            }
            is EaCallerLookupResult.Failure -> {
                call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponseDto("service_unavailable", "Could not reach the authorization service"))
            }
            is EaCallerLookupResult.Success -> {
                val tenants = result.memberships.map { membership ->
                    val companies = companyRepository.findAllByTenant(membership.tenantId)
                        .map { CompanySummaryDto(it.id.value.toString(), it.name) }
                    MyTenantDto(
                        tenantId = membership.tenantId.value.toString(),
                        tenantName = membership.tenantName,
                        role = membership.role.name,
                        accessLevel = membership.accessLevel.name,
                        tenantStatus = membership.tenantStatus,
                        kybStatus = membership.kybStatus,
                        adminKycStatus = membership.adminKycStatus,
                        adminPhoneNumber = membership.adminPhoneNumber,
                        adminPhoneVerificationStatus = membership.adminPhoneVerificationStatus,
                        phoneVerificationDeadline = membership.phoneVerificationDeadline,
                        companies = companies,
                        grantedModules = membership.grantedModules.map { it.name }
                    )
                }

                call.respond(MyProfileResponseDto(email = result.email, name = result.name, tenants = tenants))
            }
        }
    }
}
