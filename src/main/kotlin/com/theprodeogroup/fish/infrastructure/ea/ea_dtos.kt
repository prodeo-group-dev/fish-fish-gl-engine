package com.theprodeogroup.fish.infrastructure.ea

import kotlinx.serialization.Serializable

/**
 * Mirrors EA's own `MyTenantDto`/`MyProfileResponseDto`
 * (`EA/.../infrastructure/web/Dtos.kt`) field-for-field - GL is a
 * client of EA's `GET /me`, not a shared-module consumer, so this is a
 * deliberate duplicate of the wire shape rather than a cross-repo type
 * dependency (same reasoning IM's own `gl_engine_dtos.kt` already
 * applies to GL's response shapes).
 */
/** Mirrors EA's own `CompanySummaryDto` (`EA/.../infrastructure/web/Dtos.kt`) - present on the wire since 2026-09-05's login/company-list feature, but GL never reads this field, since it already sources Companies from its own local `CompanyRepository` (`MeRoutes.kt`). Declared purely so deserialization succeeds against EA's real response shape. */
@Serializable
data class EaCompanySummaryDto(
    val id: String,
    val name: String
)

@Serializable
data class EaTenantMembershipDto(
    val tenantId: String,
    val tenantName: String,
    val role: String,
    val accessLevel: String,
    val tenantStatus: String,
    val kybStatus: String,
    val adminPhoneNumber: String?,
    val adminPhoneVerificationStatus: String,
    val phoneVerificationDeadline: String?,
    // Present on the wire since 2026-09-05, never read here - see [EaCompanySummaryDto]'s own KDoc.
    val companies: List<EaCompanySummaryDto>,
    val grantedModules: List<String>
)

/**
 * [kycStatus] is top-level, not per-tenant, since 2026-09-06 -
 * [User.kycStatus] on EA's side (docs/Tenancy_Administration_Extraction_DDD_Design.md's
 * identity-first onboarding). GL never reads this value (nothing here
 * branches on the caller's own personal KYC status), it's declared
 * purely so deserialization succeeds against EA's real response shape -
 * this field's absence is exactly what crashed every authorized GL
 * request (`KtorEaMembershipGateway.lookupCaller`) until this was added.
 */
@Serializable
data class EaMyProfileResponseDto(
    val email: String,
    val name: String,
    val kycStatus: String,
    val tenants: List<EaTenantMembershipDto>
)
