package com.theprodeogroup.fish.infrastructure.ea

import com.theprodeogroup.fish.domain.tenancy.AccessLevel
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.ManagedModule
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.TenantId

/**
 * One Company's worth of the calling human's access at it, as EA reports
 * it - mirrors EA's own `CompanySummaryDto` field-for-field (2026-09-23,
 * `Per_Company_RBAC_Design.md`). [role]/[accessLevel] are null when the
 * caller has no explicit assignment there - the only way that happens is
 * an Owner-Admin whose Companies list includes this one purely via their
 * intrinsic Tenant-wide floor (see [CallerMembership.accessLevelAt]).
 */
data class CompanyAccess(
    val companyId: CompanyId,
    val name: String,
    val role: Role?,
    val accessLevel: AccessLevel?,
    val grantedModules: Set<ManagedModule>
)

/**
 * One Tenant's worth of the calling human's access, as EA reports it -
 * mirrors EA's own `MyTenantDto` field-for-field. `tenantId`/`companies`
 * are parsed into GL's own domain enums since `authorizeTenantFor*`
 * (`Auth.kt`) compares against them directly; the rest stay plain
 * strings, since `/me` (`MeRoutes.kt`) only ever passes them through to
 * its own response DTO, never branches on them. Deliberately no
 * `MembershipId`/`MembershipStatus` - EA's `GET /me` never returns
 * either (it only ever reports the caller's own already-ACTIVE
 * memberships, so there's nothing for a caller to branch on there),
 * matching [AuthenticatedCaller]'s own KDoc for why GL never needed them
 * either.
 *
 * **Rewritten 2026-09-23** (`Per_Company_RBAC_Design.md`) - `role`/
 * `accessLevel`/`grantedModules` move from single Tenant-wide fields to
 * per-Company ([companies]), and [isOwnerAdmin] replaces the old
 * `role == Role.OWNER_ADMIN` comparison as a real Membership-level fact.
 */
data class CallerMembership(
    val tenantId: TenantId,
    val tenantName: String,
    val isOwnerAdmin: Boolean,
    val tenantStatus: String,
    val kybStatus: String,
    val adminPhoneNumber: String?,
    val adminPhoneVerificationStatus: String,
    val phoneVerificationDeadline: String?,
    val companies: List<CompanyAccess>
) {
    /**
     * The [AccessLevel] this Membership holds at [companyId] - an
     * explicit per-Company assignment when one exists; otherwise
     * [AccessLevel.READ] for the Owner-Admin's intrinsic floor (present
     * in [companies] with a null `accessLevel`, per [CompanyAccess]'s own
     * KDoc), or [AccessLevel.NONE] for anyone else with no assignment
     * there - including a `companyId` this Membership's `companies` list
     * doesn't contain at all (a company outside this Tenant, or one an
     * ordinary staff Membership simply has no standing at).
     */
    fun accessLevelAt(companyId: CompanyId): AccessLevel {
        val company = companies.firstOrNull { it.companyId == companyId } ?: return AccessLevel.NONE
        return company.accessLevel ?: if (isOwnerAdmin) AccessLevel.READ else AccessLevel.NONE
    }

    /** The modules granted at [companyId] - empty if no explicit assignment, including for the Owner-Admin's own intrinsic floor (read-only oversight, not module access). */
    fun grantedModulesAt(companyId: CompanyId): Set<ManagedModule> =
        companies.firstOrNull { it.companyId == companyId }?.grantedModules ?: emptySet()
}

/**
 * Outcome of [EaMembershipGateway.lookupCaller] - deliberately three-way,
 * not just nullable, so a caller (`authorizeTenantForWrite` and friends,
 * `Auth.kt`) can distinguish "this token doesn't grant access" (401,
 * matches today's behavior) from "EA itself is unreachable or erroring"
 * (503 - a genuinely different failure a caller shouldn't silently treat
 * as "not authorized", `docs/Tenancy_Administration_Extraction_DDD_Design.md`
 * §3's open latency/availability question, resolved here as fail-closed).
 */
sealed class EaCallerLookupResult {
    data class Success(val email: String, val name: String, val memberships: List<CallerMembership>) : EaCallerLookupResult()
    data object Unauthorized : EaCallerLookupResult()
    data class Failure(val httpStatusCode: Int) : EaCallerLookupResult()
}

/**
 * GL's client of EA's `GET /me` (`EA/.../infrastructure/web/MeRoutes.kt`)
 * - the human-facing half of
 * `docs/Tenancy_Administration_Extraction_DDD_Design.md`'s "GL's own
 * authorizeTenantFor* / `/me` are rewired to call EA" migration. Mirrors
 * IM's own `GlEngineGateway` shape (`IM/.../infrastructure/gl/gl_engine_gateway.kt`)
 * - an interface real code implements over HTTP
 * ([KtorEaMembershipGateway]) and tests fake in-memory.
 *
 * **[bearerToken] is the caller's own forwarded token, not a fixed
 * service-account token** - unlike IM's gateway (which authenticates as
 * IM itself to post on IM's own behalf), GL is asking EA "does *this*
 * caller have access?", so EA must independently re-verify the exact
 * token that reached GL. This is why this pass only wires the
 * human-facing JWT provider through this gateway - POP/SOP/IM/HR's own
 * service-account tokens carry a different `aud` claim EA doesn't yet
 * verify (see the plan's own "Deferred" section).
 */
interface EaMembershipGateway {
    suspend fun lookupCaller(bearerToken: String): EaCallerLookupResult
}
