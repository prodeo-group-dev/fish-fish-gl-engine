package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.tenancy.Company
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.domain.tenancy.Membership
import com.theprodeogroup.fish.domain.tenancy.MembershipId
import com.theprodeogroup.fish.domain.tenancy.MembershipRepository
import com.theprodeogroup.fish.domain.tenancy.Tenant
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.domain.tenancy.TenantRepository
import com.theprodeogroup.fish.domain.tenancy.TenantStatus
import com.theprodeogroup.fish.domain.tenancy.User
import com.theprodeogroup.fish.domain.tenancy.UserId
import com.theprodeogroup.fish.domain.tenancy.UserRepository

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
