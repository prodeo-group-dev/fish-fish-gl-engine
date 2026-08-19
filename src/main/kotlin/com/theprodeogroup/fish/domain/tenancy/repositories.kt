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
