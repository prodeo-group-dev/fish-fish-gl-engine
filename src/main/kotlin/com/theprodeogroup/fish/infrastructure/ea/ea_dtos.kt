package com.theprodeogroup.fish.infrastructure.ea

import kotlinx.serialization.Serializable

/**
 * Mirrors EA's own `MyTenantDto`/`MyProfileResponseDto`/`CompanySummaryDto`
 * (`EA/.../infrastructure/web/Dtos.kt`) field-for-field - GL is a
 * client of EA's `GET /me`, not a shared-module consumer, so this is a
 * deliberate duplicate of the wire shape rather than a cross-repo type
 * dependency (same reasoning IM's own `gl_engine_dtos.kt` already
 * applies to GL's response shapes).
 *
 * **Rewritten 2026-09-23** (`Per_Company_RBAC_Design.md`) for EA's
 * per-Company RBAC rewrite - `EaTenantMembershipDto.role`/`.accessLevel`
 * are gone (EA no longer sends them; they were never optional here, so
 * leaving them declared would crash every authorized GL request the
 * moment EA stopped sending them, exactly the incident already recorded
 * against this file once before). `role`/`accessLevel`/`grantedModules`
 * now live per-Company on [EaCompanySummaryDto], and **GL now actually
 * reads this field** - unlike before, when GL sourced Company names from
 * its own local `CompanyRepository` and ignored EA's own `companies`
 * list entirely, `role`/`accessLevel`/`grantedModules` exist nowhere
 * else now that they're no longer Tenant-wide.
 */
@Serializable
data class EaCompanySummaryDto(
    val id: String,
    val name: String,
    val role: String? = null,
    val accessLevel: String? = null,
    val grantedModules: List<String> = emptyList()
)

@Serializable
data class EaTenantMembershipDto(
    val tenantId: String,
    val tenantName: String,
    val isOwnerAdmin: Boolean,
    val tenantStatus: String,
    val kybStatus: String,
    val adminPhoneNumber: String?,
    val adminPhoneVerificationStatus: String,
    val phoneVerificationDeadline: String?,
    val companies: List<EaCompanySummaryDto>
)

/**
 * [kycStatus] is top-level, not per-tenant, since 2026-09-06 -
 * [User.kycStatus] on EA's side (docs/Tenancy_Administration_Extraction_DDD_Design.md's
 * identity-first onboarding). GL never reads this value (nothing here
 * branches on the caller's own personal KYC status), it's declared
 * purely so deserialization succeeds against EA's real response shape -
 * this field's absence is exactly what crashed every authorized GL
 * request (`KtorEaMembershipGateway.lookupCaller`) until this was added.
 *
 * [userId] added 2026-09-29 - EA's own `MyProfileResponseDto` gained this
 * field 2026-09-28 (commit `cf1cef69`, for a message-thread UI to tell
 * "did I send this"). **This did not actually crash GL** the way it
 * crashed SOP's copy of this DTO: unlike SOP, GL already configures
 * `ignoreUnknownKeys = true` on its `eaHttpClient` (`Application.kt`,
 * fixed 2026-09-21 after this exact class of incident happened here
 * first over the now-removed `role`/`accessLevel` fields - see
 * `KtorEaMembershipGatewayTest`'s own regression test). Declared here
 * anyway purely to keep this DTO an accurate mirror of EA's real wire
 * shape, matching this file's own stated purpose - GL never reads the
 * value. Left `ignoreUnknownKeys` as-is rather than removing it to force
 * strict declaration platform-wide (SOP's own preference per direct
 * feedback) - that's a real policy inconsistency across GL/SOP worth a
 * deliberate decision, not something to flip unilaterally here.
 */
@Serializable
data class EaMyProfileResponseDto(
    val email: String,
    val name: String,
    val kycStatus: String,
    val userId: String,
    val tenants: List<EaTenantMembershipDto>
)
