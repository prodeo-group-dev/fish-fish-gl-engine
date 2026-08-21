package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.tenancy.TenantId
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.util.UUID

/**
 * Exposed table definition for idempotency-key replay caching
 * (`V7__idempotency_keys.sql`, docs/GL_Production_Readiness_Plan.md) -
 * one row per (Tenant, endpoint, caller-supplied key), storing exactly
 * enough to answer a repeat request without re-executing it: what the
 * original request looked like ([IdempotencyRecord.requestFingerprint],
 * to detect a key reused with a *different* body) and what was sent
 * back ([IdempotencyRecord.responseStatusCode]/[IdempotencyRecord.responseBody]).
 */
object IdempotencyKeysTable : Table("idempotency_keys") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val endpoint = varchar("endpoint", 100)
    val idempotencyKey = varchar("idempotency_key", 255)
    val requestFingerprint = varchar("request_fingerprint", 64)
    val responseStatusCode = integer("response_status_code")
    val responseBody = text("response_body")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * One cached outcome of a posting request, keyed by (Tenant, endpoint,
 * caller-supplied `Idempotency-Key`). See `infrastructure.web.Idempotency.kt`
 * for how this is actually used at the HTTP layer.
 */
data class IdempotencyRecord(
    val tenantId: TenantId,
    val endpoint: String,
    val idempotencyKey: String,
    val requestFingerprint: String,
    val responseStatusCode: Int,
    val responseBody: String
)

/**
 * **Deliberately lives in `infrastructure.persistence`, interface and
 * implementation together in one file** - every other repository
 * interface in this codebase lives in its owning bounded context's
 * `domain.*` package (`PayRunRepository` in `domain.payroll`, etc.),
 * with only the Exposed implementation here. Idempotency-key replay
 * caching has no bounded-context home to give it: it's not a Ledger,
 * Tenancy, or Payroll concept, it's a pure HTTP-layer technical
 * concern with zero business meaning, so there's no `domain` package
 * this naturally belongs to. Kept as an interface at all (rather than
 * route files depending on `ExposedIdempotencyKeyRepository` directly)
 * so tests can still substitute a fake, matching every other
 * repository's testability shape.
 */
interface IdempotencyKeyRepository {
    fun find(tenantId: TenantId, endpoint: String, idempotencyKey: String): IdempotencyRecord?

    /**
     * Inserts [record], returning `true` if this call actually won the
     * insert. Returns `false` (inserting nothing) if a concurrent
     * request already claimed the same (tenantId, endpoint,
     * idempotencyKey) first - detected via the unique index on
     * `V7__idempotency_keys.sql` raising a constraint violation, not
     * a `find()`-then-`insert()` race the application itself has to
     * arbitrate. The caller (`infrastructure.web.Idempotency.kt`)
     * treats `false` as "someone else's response already won; this
     * request still completed correctly on its own terms, just isn't
     * the one that got persisted" - a narrow, documented, acceptable
     * outcome for genuinely simultaneous duplicate requests, not
     * silently swept under the rug.
     */
    fun insertIfAbsent(record: IdempotencyRecord): Boolean
}

class ExposedIdempotencyKeyRepository : IdempotencyKeyRepository {

    override fun find(tenantId: TenantId, endpoint: String, idempotencyKey: String): IdempotencyRecord? = transaction {
        IdempotencyKeysTable.selectAll()
            .where {
                (IdempotencyKeysTable.tenantId eq tenantId.value) and
                    (IdempotencyKeysTable.endpoint eq endpoint) and
                    (IdempotencyKeysTable.idempotencyKey eq idempotencyKey)
            }
            .map {
                IdempotencyRecord(
                    tenantId = TenantId(it[IdempotencyKeysTable.tenantId]),
                    endpoint = it[IdempotencyKeysTable.endpoint],
                    idempotencyKey = it[IdempotencyKeysTable.idempotencyKey],
                    requestFingerprint = it[IdempotencyKeysTable.requestFingerprint],
                    responseStatusCode = it[IdempotencyKeysTable.responseStatusCode],
                    responseBody = it[IdempotencyKeysTable.responseBody]
                )
            }
            .singleOrNull()
    }

    override fun insertIfAbsent(record: IdempotencyRecord): Boolean = transaction {
        try {
            IdempotencyKeysTable.insert { statement ->
                statement[id] = UUID.randomUUID()
                statement[tenantId] = record.tenantId.value
                statement[endpoint] = record.endpoint
                statement[idempotencyKey] = record.idempotencyKey
                statement[requestFingerprint] = record.requestFingerprint
                statement[responseStatusCode] = record.responseStatusCode
                statement[responseBody] = record.responseBody
                statement[createdAt] = Instant.now()
            }
            true
        } catch (e: ExposedSQLException) {
            false
        }
    }
}
