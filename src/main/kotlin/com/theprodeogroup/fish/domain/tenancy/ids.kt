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

