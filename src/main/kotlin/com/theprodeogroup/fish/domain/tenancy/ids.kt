package com.theprodeogroup.fish.domain.tenancy

import java.util.UUID

/**
 * Identity of a Tenant aggregate.
 *
 * A typed wrapper around UUID so the compiler rejects a TenantId passed
 * where a CompanyId (or any other ID type) is expected.
 */
@JvmInline
value class TenantId(val value: UUID) {
    companion object {
        fun generate(): TenantId = TenantId(UUID.randomUUID())
    }
}

/**
 * Identity of a Company aggregate, referenced by Tenant but not embedded in it.
 */
@JvmInline
value class CompanyId(val value: UUID) {
    companion object {
        fun generate(): CompanyId = CompanyId(UUID.randomUUID())
    }
}

/**
 * Identity of a Membership (User x Tenant x Role), referenced by Tenant but not embedded in it.
 */
@JvmInline
value class MembershipId(val value: UUID) {
    companion object {
        fun generate(): MembershipId = MembershipId(UUID.randomUUID())
    }
}

/**
 * Identity of a User aggregate - a global identity, not scoped to any
 * one Tenant (Section 3.2: "a User can hold Memberships across multiple
 * Tenants").
 */
@JvmInline
value class UserId(val value: UUID) {
    companion object {
        fun generate(): UserId = UserId(UUID.randomUUID())
    }
}
