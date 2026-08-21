package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.infrastructure.persistence.IdempotencyKeyRepository
import com.theprodeogroup.fish.infrastructure.persistence.IdempotencyRecord
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * The `Idempotency-Key` support flagged as a gap in the fresh
 * production-readiness review (docs/GL_Production_Readiness_Plan.md) -
 * every posting route in this package (`journalEntryRoutes`,
 * `purchaseOrderRoutes`, `salesOrderRoutes`, `payrollRoutes`,
 * `inventoryRoutes`, `recordSaleAndCollectionRoutes`) calls
 * [ApplicationCall.respondIdempotently] instead of `call.respond`
 * directly for its final result-to-HTTP step.
 *
 * **How it works**: a caller sends an `Idempotency-Key` header (any
 * string of their choosing - a UUID they generate client-side is the
 * usual pattern, same convention Stripe's API popularized). Retrying
 * the exact same request with the same key returns the exact original
 * response without re-executing anything; reusing the same key with a
 * *different* request body is treated as a client error (422), not
 * silently accepted - a caller doing that has a bug worth surfacing,
 * not a legitimate retry. **The header is optional** - a request with
 * no `Idempotency-Key` behaves exactly as it always has, so this is
 * additive for every existing caller (POP/SOP/IM/HR), not a breaking
 * change forcing an immediate consumer-side update.
 *
 * [endpoint] scopes the key alongside [tenantId] (see `V7__idempotency_keys.sql`'s
 * own KDoc) - the same key string reused across two different routes,
 * or by two different Tenants, is never treated as the same request.
 *
 * **Responses are compared/replayed as raw JSON text, not re-decoded
 * domain objects** - [produceResponse] returns an already-serialized
 * body (each call site does `Json.encodeToString(SomeDto.serializer(), dto)`
 * itself, per branch, rather than this function attempting to
 * serialize an `Any` generically via reflection). More typing at each
 * call site, but no runtime-reflective serializer lookup to get wrong.
 *
 * **A narrow, documented concurrency gap**: two genuinely simultaneous
 * requests with the same key can both pass the `find()` check before
 * either finishes executing, so both run [produceResponse]. The
 * *storage* stays correct either way - [IdempotencyKeyRepository.insertIfAbsent]'s
 * unique-index-backed guard means only one of the two ever gets
 * persisted - but the operation itself can genuinely execute twice in
 * this specific race. Accepted as-is for this first pass: the
 * dominant real-world trigger for idempotency keys is a *sequential*
 * retry after a timeout, not two requests landing in the same
 * millisecond, and building full claim-then-complete two-phase
 * locking to close a narrow edge case nobody has hit yet would be
 * exactly the kind of speculative complexity this codebase avoids
 * elsewhere - see the "no cross-repository transaction boundary"
 * finding in the same review this feature came from for the same
 * reasoning applied to a related, already-accepted gap.
 */
suspend fun ApplicationCall.respondIdempotently(
    idempotencyKeyRepository: IdempotencyKeyRepository,
    tenantId: TenantId,
    endpoint: String,
    requestBodyJson: String,
    produceResponse: suspend () -> Pair<HttpStatusCode, String>
) {
    val idempotencyKey = request.header("Idempotency-Key")
    if (idempotencyKey == null) {
        val (status, body) = produceResponse()
        respondText(body, ContentType.Application.Json, status)
        return
    }

    val fingerprint = sha256Hex(requestBodyJson)
    val existing = idempotencyKeyRepository.find(tenantId, endpoint, idempotencyKey)
    if (existing != null) {
        if (existing.requestFingerprint != fingerprint) {
            respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorResponseDto("idempotency_key_reused", "This Idempotency-Key was already used with a different request body")
            )
        } else {
            respondText(existing.responseBody, ContentType.Application.Json, HttpStatusCode.fromValue(existing.responseStatusCode))
        }
        return
    }

    val (status, body) = produceResponse()
    idempotencyKeyRepository.insertIfAbsent(IdempotencyRecord(tenantId, endpoint, idempotencyKey, fingerprint, status.value, body))
    respondText(body, ContentType.Application.Json, status)
}

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/**
 * Serializes an [ErrorResponseDto] to JSON text - every route's
 * failure branches need exactly this when producing a
 * [respondIdempotently] response body, since [ErrorResponseDto] is the
 * one DTO every route file already shares.
 */
fun errorResponseJson(error: String, detail: String? = null): String =
    Json.encodeToString(ErrorResponseDto.serializer(), ErrorResponseDto(error, detail))
