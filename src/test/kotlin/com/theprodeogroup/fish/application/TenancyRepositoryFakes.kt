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
import com.theprodeogroup.fish.infrastructure.ea.CompanyAccess
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

/**
 * **Rewritten 2026-09-23** (`Per_Company_RBAC_Design.md`) - a fake
 * `Membership` now represents one (Company, Role, AccessLevel,
 * grantedModules) assignment, mirroring EA's real per-Company RBAC model
 * (`CompanyAccess`/`CallerMembership.accessLevelAt`), rather than a
 * single Tenant-wide grant. [isOwnerAdmin] defaults from `role ==
 * Role.OWNER_ADMIN` - [Role.OWNER_ADMIN] stays meaningful in this fake
 * purely as a convenient default trigger, not because production code
 * branches on it (`Auth.kt` never does; only [CallerMembership.isOwnerAdmin]
 * matters there). [FakeEaMembershipGateway.lookupCaller] groups a user's
 * Memberships by [tenantId] and folds every Membership's [isOwnerAdmin]
 * with `any { }` to build one [CallerMembership] per Tenant, so a caller
 * granted Owner-Admin via *any* one of their Memberships in a Tenant is
 * treated as the Tenant's Owner-Admin overall, matching how EA itself
 * only ever has one Owner-Admin per Tenant.
 */
class Membership private constructor(
    val id: MembershipId,
    val userId: UserId,
    val tenantId: TenantId,
    val companyId: CompanyId,
    val role: Role,
    val accessLevel: AccessLevel,
    val grantedModules: Set<ManagedModule>,
    val isOwnerAdmin: Boolean
) {
    companion object {
        fun grant(
            userId: UserId,
            tenantId: TenantId,
            role: Role,
            companyId: CompanyId,
            accessLevel: AccessLevel = defaultAccessLevelFor(role),
            grantedModules: Set<ManagedModule> = ManagedModule.entries.toSet(),
            isOwnerAdmin: Boolean = (role == Role.OWNER_ADMIN),
            id: MembershipId = MembershipId.generate()
        ): Membership = Membership(id, userId, tenantId, companyId, role, accessLevel, grantedModules, isOwnerAdmin)

        /**
         * Public (not `private`) so route-test `Fixture` classes can use
         * it as a constructor default-parameter expression when
         * translating an old `Role.READ_ONLY`/`Role.APPROVER`-style
         * fixture into an explicit `accessLevel` override against a real
         * functional [Role] (2026-09-23 Per-Company RBAC rewrite - see
         * this class's own KDoc).
         */
        fun defaultAccessLevelFor(role: Role): AccessLevel = when (role) {
            Role.OWNER_ADMIN -> AccessLevel.ADMIN
            Role.ACCOUNTANT -> AccessLevel.WRITE
            Role.SALES_OFFICER -> AccessLevel.WRITE
            Role.PURCHASING_OFFICER -> AccessLevel.WRITE
            Role.INVENTORY_MANAGER -> AccessLevel.WRITE
            Role.HR_OFFICER -> AccessLevel.WRITE
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
 *
 * [companyRepository] is optional - only `MeRoutesTest` asserts on a
 * [CompanyAccess.name], since real EA resolves each Company's actual
 * name (`MeRoutes.kt`'s own KDoc); every other route test only cares
 * about `role`/`accessLevel`/`grantedModules`, so this stays `null`
 * (and [CompanyAccess.name] falls back to `""`) everywhere else rather
 * than forcing every one of the ~23 route-test `Fixture`s to wire it up.
 */
class FakeEaMembershipGateway(
    private val userRepository: FakeUserRepository,
    private val membershipRepository: FakeMembershipRepository,
    private val companyRepository: FakeCompanyRepository? = null
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

        val memberships = activeMemberships.groupBy { it.tenantId }.map { (tenantId, membershipsForTenant) ->
            val description = tenantDescriptions[tenantId]
            CallerMembership(
                tenantId = tenantId,
                tenantName = description?.name ?: "",
                isOwnerAdmin = membershipsForTenant.any { it.isOwnerAdmin },
                tenantStatus = description?.status ?: "ACTIVE",
                kybStatus = description?.kybStatus ?: "VERIFIED",
                adminPhoneNumber = description?.adminPhoneNumber,
                adminPhoneVerificationStatus = description?.adminPhoneVerificationStatus ?: "PENDING",
                phoneVerificationDeadline = description?.phoneVerificationDeadline,
                companies = membershipsForTenant.map { membership ->
                    CompanyAccess(
                        companyId = membership.companyId,
                        name = companyRepository?.findById(membership.companyId)?.name ?: "",
                        role = membership.role,
                        accessLevel = membership.accessLevel,
                        grantedModules = membership.grantedModules
                    )
                }
            )
        }

        return EaCallerLookupResult.Success(email = user.email, name = user.name, memberships = memberships)
    }
}
