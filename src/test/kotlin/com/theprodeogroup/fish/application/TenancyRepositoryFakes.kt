package com.theprodeogroup.fish.application

import com.auth0.jwt.JWT
import com.theprodeogroup.fish.domain.tenancy.AdminPhoneVerificationChecker
import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.domain.tenancy.Membership
import com.theprodeogroup.fish.domain.tenancy.MembershipId
import com.theprodeogroup.fish.domain.tenancy.MembershipRepository
import com.theprodeogroup.fish.domain.tenancy.MembershipStatus
import com.theprodeogroup.fish.domain.tenancy.PhoneNumber
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.domain.tenancy.StaffInviteNotificationGateway
import com.theprodeogroup.fish.domain.tenancy.StaffInviteNotificationResult
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.domain.tenancy.TenantRepository
import com.theprodeogroup.fish.domain.tenancy.TenantStatus
import com.theprodeogroup.fish.domain.tenancy.User
import com.theprodeogroup.fish.domain.tenancy.UserId
import com.theprodeogroup.fish.domain.tenancy.UserRepository
import com.theprodeogroup.fish.infrastructure.ea.CallerMembership
import com.theprodeogroup.fish.infrastructure.ea.EaCallerLookupResult
import com.theprodeogroup.fish.infrastructure.ea.EaMembershipGateway

/**
 * In-memory stand-ins for the Tenancy repository interfaces, shared
 * across every `application`-layer use case test in this package - pure
 * orchestration tests shouldn't need a real database (that's already
 * covered by the Exposed `integrationTest` suite for each individual
 * repository, docs/DDD_Design.md Section 10.2-10.4). These fakes don't
 * round-trip through reconstitute() - they just hold object references,
 * which is enough to verify what a use case calls and in what order/state.
 */
class FakeTenantRepository : TenantRepository {
    /**
     * Snapshots, not raw `Tenant` references - `Tenant` is mutable, so
     * storing the object itself would make every recorded call reflect
     * whatever its *final* state ends up being, not its state at the
     * moment `save()` was actually called.
     */
    data class SaveSnapshot(val status: TenantStatus, val companyIds: Set<CompanyId>)

    val saveCalls = mutableListOf<SaveSnapshot>()
    private val store = mutableMapOf<TenantId, Tenant>()
    override fun save(tenant: Tenant) {
        saveCalls.add(SaveSnapshot(tenant.status, tenant.companyIds))
        store[tenant.id] = tenant
    }
    override fun findById(id: TenantId): Tenant? = store[id]
    override fun findAllActive(): List<Tenant> = store.values.filter { it.status == TenantStatus.ACTIVE }
}

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

class FakeUserRepository : UserRepository {
    private val store = mutableMapOf<UserId, User>()
    override fun save(user: User) { store[user.id] = user }
    override fun findById(id: UserId): User? = store[id]
    override fun findByEmail(email: String): User? = store.values.find { it.email == email }
}

class FakeMembershipRepository : MembershipRepository {
    private val store = mutableMapOf<MembershipId, Membership>()
    override fun save(membership: Membership) { store[membership.id] = membership }
    override fun findById(id: MembershipId): Membership? = store[id]
    override fun findAllByTenant(tenantId: TenantId): List<Membership> = store.values.filter { it.tenantId == tenantId }
    override fun findAllByUser(userId: UserId): List<Membership> = store.values.filter { it.userId == userId }
}

/**
 * Defaults to a [StaffInviteNotificationResult.Success] - the common
 * case for tests that only need [InviteStaffMemberUseCase] to be
 * wireable, not to exercise its notification-failure path.
 * [InviteStaffMemberUseCaseTest] uses [alwaysFail] for that path
 * specifically, mirroring [FakeAdminPhoneVerificationChecker]'s own
 * `alwaysReject()` shape.
 */
class FakeStaffInviteNotificationGateway : StaffInviteNotificationGateway {
    val sentTo = mutableListOf<String>()
    private var shouldFail = false

    fun alwaysFail() { shouldFail = true }

    override fun sendInviteEmail(to: String, tenantName: String, inviterName: String, role: Role): StaffInviteNotificationResult {
        sentTo.add(to)
        return if (shouldFail) StaffInviteNotificationResult.Failure("test gateway configured to fail")
        else StaffInviteNotificationResult.Success("fake-message-id")
    }
}

/**
 * Defaults to reporting every number as verified - the common case for
 * route-level tests that only need `RecordAdminPhoneNumberUseCase` to
 * be wireable at all, not to exercise its Cognito-rejection path.
 * [RecordAdminPhoneNumberUseCaseTest] uses [verifiedFor]/[alwaysReject]
 * to test the rejection path specifically.
 */
class FakeAdminPhoneVerificationChecker(private var verified: Boolean = true) : AdminPhoneVerificationChecker {
    override fun isVerified(email: String, phoneNumber: PhoneNumber): Boolean = verified

    fun alwaysReject() { verified = false }
}

/**
 * Test double standing in for a real network call to EA
 * (`docs/Tenancy_Administration_Extraction_DDD_Design.md`'s human-facing
 * rewiring) - decodes the forwarded token's `email` claim (no signature
 * check needed here; a real EA independently re-verifies the token in
 * production) and resolves the same way EA's own `GET /me` does, but
 * against these same three fakes rather than a real database. Every
 * existing route test fixture already seeds [userRepository]/
 * [membershipRepository]/[tenantRepository] with exactly the Tenant/User/
 * Membership data its tests need - this reuses that seeding rather than
 * asking each affected test file to duplicate it a second time for a
 * fake gateway.
 *
 * **Deliberately more tolerant than real EA's own `/me`** on one point:
 * many existing route tests grant a `Membership` against a bare
 * `TenantId.generate()` without ever saving a matching `Tenant` - a
 * shortcut that never mattered before this rewiring, since
 * `authorizeTenantFor*` never looked up `Tenant` at all. Real EA drops
 * such a membership entirely (no `Tenant` to describe); this fake
 * doesn't, since what these tests actually exercise is the
 * authorization check itself (`role`/`accessLevel`/`grantedModules`),
 * not `/me`'s own tenant-metadata resolution - falls back to empty/
 * default values for the tenant-descriptive fields only.
 */
class FakeEaMembershipGateway(
    private val userRepository: UserRepository,
    private val membershipRepository: MembershipRepository,
    private val tenantRepository: TenantRepository
) : EaMembershipGateway {
    override suspend fun lookupCaller(bearerToken: String): EaCallerLookupResult {
        val email = runCatching { JWT.decode(bearerToken).getClaim("email").asString() }.getOrNull()
            ?: return EaCallerLookupResult.Unauthorized

        val user = userRepository.findByEmail(email) ?: return EaCallerLookupResult.Unauthorized
        val activeMemberships = membershipRepository.findAllByUser(user.id).filter { it.status == MembershipStatus.ACTIVE }
        if (activeMemberships.isEmpty()) return EaCallerLookupResult.Unauthorized

        val memberships = activeMemberships.map { membership ->
            val tenant = tenantRepository.findById(membership.tenantId)
            CallerMembership(
                tenantId = membership.tenantId,
                tenantName = tenant?.name ?: "",
                role = membership.role,
                accessLevel = membership.accessLevel,
                tenantStatus = tenant?.status?.name ?: TenantStatus.ACTIVE.name,
                kybStatus = tenant?.kybStatus?.name ?: "",
                adminKycStatus = tenant?.adminKycStatus?.name ?: "",
                adminPhoneNumber = tenant?.adminPhoneNumber?.value,
                adminPhoneVerificationStatus = tenant?.adminPhoneVerificationStatus?.name ?: "",
                phoneVerificationDeadline = tenant?.phoneVerificationDeadline?.toString(),
                grantedModules = membership.grantedModules
            )
        }

        return EaCallerLookupResult.Success(email = user.email, name = user.name, memberships = memberships)
    }
}
