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
@Serializable
data class EaTenantMembershipDto(
    val tenantId: String,
    val tenantName: String,
    val role: String,
    val accessLevel: String,
    val tenantStatus: String,
    val kybStatus: String,
    val adminKycStatus: String,
    val adminPhoneNumber: String?,
    val adminPhoneVerificationStatus: String,
    val phoneVerificationDeadline: String?,
    val grantedModules: List<String>
)

@Serializable
data class EaMyProfileResponseDto(
    val email: String,
    val name: String,
    val tenants: List<EaTenantMembershipDto>
)
