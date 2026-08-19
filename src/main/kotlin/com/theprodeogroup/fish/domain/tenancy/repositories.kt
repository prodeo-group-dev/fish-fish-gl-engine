package com.theprodeogroup.fish.domain.tenancy

/**
 * Persistence contracts for the Tenancy aggregates (docs/DDD_Design.md
 * Section 10.3) - interfaces live in `domain`, Exposed-backed
 * implementations live in `infrastructure.persistence`, matching the
 * split already established for the core Ledger repositories
 * (Section 10.2). Deliberately no `delete()` on any of these, same
 * rationale as the Ledger repositories - nothing in this codebase
 * deletes a Tenant/Company/User/Membership, only deactivates/closes/
 * revokes them.
 */
interface TenantRepository {
    fun save(tenant: Tenant)
    fun findById(id: TenantId): Tenant?

    /**
     * Every `Active` Tenant, regardless of Company/Membership - the query
     * `KybGracePeriodSweep` (Section 9.4/10.7) needs to find candidates
     * whose `kybVerificationDeadline` may have lapsed. Only `Active`
     * Tenants can have an expired grace period in the first place
     * (`Tenant.isKybGracePeriodExpired()` already requires `status ==
     * ACTIVE`), so filtering here at the query level - not loading every
     * Tenant regardless of status - is a real, not premature,
     * optimization.
     */
    fun findAllActive(): List<Tenant>
}

interface CompanyRepository {
    fun save(company: Company)
    fun findById(id: CompanyId): Company?
    fun findAllByTenant(tenantId: TenantId): List<Company>
}

/** [findByEmail] mirrors [User.email]'s role as the login identifier (Section 3.2). */
interface UserRepository {
    fun save(user: User)
    fun findById(id: UserId): User?
    fun findByEmail(email: String): User?
}

interface MembershipRepository {
    fun save(membership: Membership)
    fun findById(id: MembershipId): Membership?
    fun findAllByTenant(tenantId: TenantId): List<Membership>
    fun findAllByUser(userId: UserId): List<Membership>
}
