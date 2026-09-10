package com.theprodeogroup.fish.application

import com.auth0.jwt.JWT
import com.theprodeogroup.fish.domain.tenancy.AccessLevel
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.domain.tenancy.ManagedModule
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.infrastructure.ea.CallerMembership
import com.theprodeogroup.fish.infrastructure.ea.EaCallerLookupResult
import com.theprodeogroup.fish.infrastructure.ea.EaMembershipGateway
import java.util.UUID

/**
 * In-memory stand-in for `CompanyRepository` - `Company` is the one
 * Tenancy aggregate that stays in GL
 * (docs/Tenancy_Administration_Extraction_DDD_Design.md, 2026-09-06).
 */
class FakeCompanyRepository : CompanyRepository {
    val saveCalls = mutableListOf<CompanyId>()
    private val store = mutableMapOf<CompanyId, Company>()
    override fun save(company: Company) {
        saveCalls.add(company.id)
        store[company.id] = company
    }
    override fun findById(id: CompanyId): Company? = store[id]
    override fun findAllByTenant(tenantId: TenantId): List<Company> = store.values.filter { it.tenantId == tenantId }
}

/**
 * Test-only identity - `Tenant`/`User`/`Membership` moved to EA
 * (docs/Tenancy_Administration_Extraction_DDD_Design.md, 2026-09-06);
 * GL's own tests never exercised their real business logic (that's
 * EA's own test suite's job now), only enough to seed
 * [FakeEaMembershipGateway] with "this bearer token's email resolves to
 * this role/accessLevel/grantedModules in this Tenant" - what
 * `authorizeTenantFor*` actually needs. Deliberately kept test-local,
 * not restored to `domain.tenancy` - nothing here is production code.
 */
@JvmInline
value class UserId(val value: UUID) {
    companion object {
        fun generate(): UserId = UserId(UUID.randomUUID())
    }
}

class User private constructor(val id: UserId, val email: String, val name: String) {
    companion object {
        fun create(email: String, name: String, id: UserId = UserId.generate()): User = User(id, email, name)
    }
}

class FakeUserRepository {
    private val store = mutableMapOf<UserId, User>()
    fun save(user: User) { store[user.id] = user }
    fun findByEmail(email: String): User? = store.values.find { it.email == email }
}

@JvmInline
value class MembershipId(val value: UUID) {
    companion object {
        fun generate(): MembershipId = MembershipId(UUID.randomUUID())
    }
}

class Membership private constructor(
    val id: MembershipId,
    val userId: UserId,
    val tenantId: TenantId,
    val role: Role,
    val accessLevel: AccessLevel,
    val grantedModules: Set<ManagedModule>
) {
    companion object {
        fun grant(
            userId: UserId,
            tenantId: TenantId,
            role: Role,
            accessLevel: AccessLevel = defaultAccessLevelFor(role),
            grantedModules: Set<ManagedModule> = ManagedModule.entries.toSet(),
            id: MembershipId = MembershipId.generate()
        ): Membership = Membership(id, userId, tenantId, role, accessLevel, grantedModules)

        private fun defaultAccessLevelFor(role: Role): AccessLevel = when (role) {
            Role.OWNER_ADMIN -> AccessLevel.ADMIN
            Role.ACCOUNTANT -> AccessLevel.WRITE
            Role.APPROVER -> AccessLevel.APPROVE
            Role.READ_ONLY -> AccessLevel.READ
            Role.COMPLIANCE_ETHICS_REVIEW -> AccessLevel.READ
        }
    }
}

class FakeMembershipRepository {
    private val store = mutableMapOf<MembershipId, Membership>()
    fun save(membership: Membership) { store[membership.id] = membership }
    fun findAllByUser(userId: UserId): List<Membership> = store.values.filter { it.userId == userId }
}

/**
 * Test double standing in for a real network call to EA
 * (`docs/Tenancy_Administration_Extraction_DDD_Design.md`'s human-facing
 * rewiring) - decodes the forwarded token's `email` claim (no signature
 * check needed here; a real EA independently re-verifies the token in
 * production) and resolves it against [userRepository]/[membershipRepository],
 * the same way EA's own `GET /me` resolves it against its own database.
 *
 * Tenant-descriptive fields (name/status/KYB/etc.) default to values no
 * route test but `MeRoutesTest` ever asserts on - that test calls
 * [describeTenant] to override them for the one case that does; every
 * other test only cares about `role`/`accessLevel`/`grantedModules`.
 */
class FakeEaMembershipGateway(
    private val userRepository: FakeUserRepository,
    private val membershipRepository: FakeMembershipRepository
) : EaMembershipGateway {
    private data class TenantDescription(
        val name: String,
        val status: String,
        val kybStatus: String,
        val adminPhoneNumber: String?,
        val adminPhoneVerificationStatus: String,
        val phoneVerificationDeadline: String?
    )

    private val tenantDescriptions = mutableMapOf<TenantId, TenantDescription>()

    fun describeTenant(
        tenantId: TenantId,
        name: String = "",
        status: String = "ACTIVE",
        kybStatus: String = "VERIFIED",
        adminPhoneNumber: String? = null,
        adminPhoneVerificationStatus: String = "PENDING",
        phoneVerificationDeadline: String? = null
    ) {
        tenantDescriptions[tenantId] = TenantDescription(
            name, status, kybStatus, adminPhoneNumber, adminPhoneVerificationStatus, phoneVerificationDeadline
        )
    }

    override suspend fun lookupCaller(bearerToken: String): EaCallerLookupResult {
        val email = runCatching { JWT.decode(bearerToken).getClaim("email").asString() }.getOrNull()
            ?: return EaCallerLookupResult.Unauthorized

        val user = userRepository.findByEmail(email) ?: return EaCallerLookupResult.Unauthorized
        val activeMemberships = membershipRepository.findAllByUser(user.id)
        if (activeMemberships.isEmpty()) return EaCallerLookupResult.Unauthorized

        val memberships = activeMemberships.map { membership ->
            val description = tenantDescriptions[membership.tenantId]
            CallerMembership(
                tenantId = membership.tenantId,
                tenantName = description?.name ?: "",
                role = membership.role,
                accessLevel = membership.accessLevel,
                tenantStatus = description?.status ?: "ACTIVE",
                kybStatus = description?.kybStatus ?: "VERIFIED",
                adminPhoneNumber = description?.adminPhoneNumber,
                adminPhoneVerificationStatus = description?.adminPhoneVerificationStatus ?: "PENDING",
                phoneVerificationDeadline = description?.phoneVerificationDeadline,
                grantedModules = membership.grantedModules
            )
        }

        return EaCallerLookupResult.Success(email = user.email, name = user.name, memberships = memberships)
    }
}
