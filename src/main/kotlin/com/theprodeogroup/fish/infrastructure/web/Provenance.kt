package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.domain.ledger.SourceReference
import com.theprodeogroup.fish.domain.ledger.SourceReferenceType
import com.theprodeogroup.fish.domain.ledger.VatBasis
import com.theprodeogroup.fish.infrastructure.audit.AuditContexts
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The document an entry came from, as a caller sends it on a posting door (standard S6, GL-T-31, GL-FR-TAX-10). Optional everywhere: a
 * service declares it in its own gateway first, then sends it; GL stores it once, with the entry, and never edits it.
 * [type] is one of [SourceReferenceType]; [id] is the owning service's own id for the document; [number] is the number printed on it.
 */
@Serializable
data class SourceReferenceDto(val type: String, val id: String, val number: String? = null)

/**
 * The rate row a caller used when it worked the VAT out itself (F22-18): [ratePercent] as an exact decimal percent string, and the Tax rulebook
 * version and effective date it read it under. All three are what the caller says it read; GL does not check them against the rate table.
 */
@Serializable
data class VatBasisDto(val ratePercent: String, val rulebookVersion: Int? = null, val effectiveFrom: String? = null)

/** The longest reason GL stores (S4). */
const val MAX_REASON_LENGTH = 500

/**
 * Records, for the entries this request stores, the document they came from and the rate basis (GL-T-31). Nothing is recorded when both are
 * absent. A value that does not fit (an unknown [SourceReferenceDto.type], an id over 100 characters, a basis that is not a plain percent)
 * is a 400 and nothing is stored, rather than a silently altered or truncated record. Returns false after responding.
 */
suspend fun ApplicationCall.recordProvenance(sourceReference: SourceReferenceDto?, vatBasis: VatBasisDto?): Boolean {
    val context = AuditContexts.current()
    try {
        if (sourceReference != null) {
            val type = SourceReferenceType.entries.firstOrNull { it.name == sourceReference.type }
                ?: throw IllegalArgumentException("sourceReference.type must be one of ${SourceReferenceType.entries.joinToString { it.name }}")
            context?.sourceReference = SourceReference(type, sourceReference.id.trim(), sourceReference.number?.trim())
        }
        if (vatBasis != null) {
            val effectiveFrom = vatBasis.effectiveFrom?.let {
                try {
                    LocalDate.parse(it)
                } catch (e: DateTimeParseException) {
                    throw IllegalArgumentException("vatBasis.effectiveFrom must be a date, YYYY-MM-DD")
                }
            }
            context?.vatBasis = VatBasis(vatBasis.ratePercent.trim(), vatBasis.rulebookVersion, effectiveFrom)
        }
    } catch (e: IllegalArgumentException) {
        // The messages above are fixed text or the domain's own fixed requirement text, never caller input echoed back.
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", e.message ?: "invalid sourceReference or vatBasis"))
        return false
    }
    return true
}

/**
 * Records why, for the entries this request stores (S4): trimmed, blank means none, and more than [MAX_REASON_LENGTH] characters is a 400
 * (never truncated). Returns false after responding.
 */
suspend fun ApplicationCall.recordReason(reason: String?): Boolean {
    val trimmed = reason?.trim()?.takeIf { it.isNotEmpty() }
    if (trimmed != null && trimmed.length > MAX_REASON_LENGTH) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "reason must be at most $MAX_REASON_LENGTH characters"))
        return false
    }
    AuditContexts.current()?.reason = trimmed
    return true
}
