package com.theprodeogroup.fish.infrastructure.ea

import com.theprodeogroup.fish.domain.tenancy.AccessLevel
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.ManagedModule
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Calls EA's `GET /me` over HTTP - GL's counterpart to IM's own
 * `KtorGlEngineGateway`. Forwards [bearerToken] as-is (the caller's own
 * token, not [KtorGlEngineGateway]'s fixed service-account provider
 * function) - see [EaMembershipGateway]'s own KDoc for why.
 */
class KtorEaMembershipGateway(
    private val client: HttpClient,
    private val baseUrl: String
) : EaMembershipGateway {

    override suspend fun lookupCaller(bearerToken: String): EaCallerLookupResult {
        val response: HttpResponse = try {
            client.get("$baseUrl/api/me") {
                header(HttpHeaders.Authorization, "Bearer $bearerToken")
            }
        } catch (e: Exception) {
            // Network-level failure (EA unreachable, DNS, timeout) - not a
            // "bad token" but a "the dependency is down" failure, so this
            // is deliberately not folded into Unauthorized. No specific
            // status code applies here; callers treat any Failure as 503.
            return EaCallerLookupResult.Failure(HttpStatusCode.ServiceUnavailable.value)
        }

        return when {
            response.status == HttpStatusCode.Unauthorized -> EaCallerLookupResult.Unauthorized
            response.status.isSuccess() -> {
                val body = response.body<EaMyProfileResponseDto>()
                EaCallerLookupResult.Success(
                    email = body.email,
                    name = body.name,
                    memberships = body.tenants.map { it.toCallerMembership() }
                )
            }
            else -> EaCallerLookupResult.Failure(response.status.value)
        }
    }

    private fun EaTenantMembershipDto.toCallerMembership() = CallerMembership(
        tenantId = TenantId(UUID.fromString(tenantId)),
        tenantName = tenantName,
        isOwnerAdmin = isOwnerAdmin,
        tenantStatus = tenantStatus,
        kybStatus = kybStatus,
        adminPhoneNumber = adminPhoneNumber,
        adminPhoneVerificationStatus = adminPhoneVerificationStatus,
        phoneVerificationDeadline = phoneVerificationDeadline,
        companies = companies.map { it.toCompanyAccess() }
    )

    private fun EaCompanySummaryDto.toCompanyAccess() = CompanyAccess(
        companyId = CompanyId(UUID.fromString(id)),
        name = name,
        role = role?.let { Role.valueOf(it) },
        accessLevel = accessLevel?.let { AccessLevel.valueOf(it) },
        grantedModules = grantedModules.mapNotNull { knownModuleOrNull(it) }.toSet()
    )

    /**
     * Fail closed on a module value GL does not know (2026-10-15): it grants nothing and is logged, it never throws.
     * `valueOf` used to throw here, which 500'd every authorised GL request for the Company the day EA first sent
     * EDUCATION_RUNTIME (2026-09-29). Only the module *value* is tolerated this way: unknown keys in the payload
     * still fail loudly (strict decoding is unchanged), and a value is matched exactly, never case-folded.
     */
    private fun knownModuleOrNull(value: String): ManagedModule? =
        ManagedModule.entries.firstOrNull { it.name == value }
            ?: run {
                log.warn("EA /me sent a module GL does not know; granting nothing for it: {}", value.take(64))
                null
            }

    private companion object {
        private val log = LoggerFactory.getLogger(KtorEaMembershipGateway::class.java)
    }
}
