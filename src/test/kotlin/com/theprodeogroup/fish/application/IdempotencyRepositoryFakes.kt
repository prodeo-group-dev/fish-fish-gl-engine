package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.infrastructure.persistence.IdempotencyKeyRepository
import com.theprodeogroup.fish.infrastructure.persistence.IdempotencyRecord

/**
 * In-memory stand-in for [IdempotencyKeyRepository] - same discipline
 * as `LedgerRepositoryFakes.kt`/`EcosystemRepositoryFakes.kt`, grouped
 * on its own here since idempotency-key replay caching isn't owned by
 * any bounded context (see [IdempotencyKeyRepository]'s own KDoc).
 */
class FakeIdempotencyKeyRepository : IdempotencyKeyRepository {
    private val store = mutableMapOf<Triple<TenantId, String, String>, IdempotencyRecord>()

    override fun find(tenantId: TenantId, endpoint: String, idempotencyKey: String): IdempotencyRecord? =
        store[Triple(tenantId, endpoint, idempotencyKey)]

    override fun insertIfAbsent(record: IdempotencyRecord): Boolean {
        val key = Triple(record.tenantId, record.endpoint, record.idempotencyKey)
        if (store.containsKey(key)) return false
        store[key] = record
        return true
    }
}
