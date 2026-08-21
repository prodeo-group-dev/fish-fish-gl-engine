package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.PostJournalEntryResult
import com.theprodeogroup.fish.application.PostJournalEntryUseCase
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Currency
import java.util.UUID

/**
 * `POST /journal-entries` - wraps [PostJournalEntryUseCase] (docs/DDD_Design.md
 * Section 10.19), one of this first pass's two representative endpoints
 * (the other is `purchaseOrderRoutes`) demonstrating the auth ->
 * routing -> use case -> `Result`-to-HTTP-status pattern before it gets
 * mechanically repeated across the other 12 use cases.
 *
 * **Tenant resolution for authorization**: the request body carries
 * `periodId`, not a `tenantId`/`companyId` directly - `Period.companyId`
 * is looked up first (via [periodRepository]), then `Company.tenantId`
 * (via [companyRepository]), so the caller's `X-Tenant-Id` header is
 * checked against the *actual* owning Tenant of the Period being posted
 * into, not just trusted at face value. Without this, any authenticated
 * caller with *any* Tenant Membership could post into any other
 * Tenant's Period by simply claiming a different Tenant in the request -
 * a real multi-tenancy leak this check closes.
 */
fun Route.journalEntryRoutes(
    postJournalEntryUseCase: PostJournalEntryUseCase,
    periodRepository: PeriodRepository,
    companyRepository: CompanyRepository
) {
    post("/journal-entries") {
        val request = call.receive<PostJournalEntryRequestDto>()

        val periodId = call.parseUuid(request.periodId) ?: return@post
        val period = periodRepository.findById(PeriodId(periodId))
        if (period == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Period not found"))
            return@post
        }
        val tenantId = call.resolveTenantForCompany(period.companyId, companyRepository) ?: return@post

        val claimedTenantIdRaw = call.request.header("X-Tenant-Id")
        if (claimedTenantIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "X-Tenant-Id header is required"))
            return@post
        }
        val claimedTenantId = call.parseUuid(claimedTenantIdRaw) ?: return@post
        if (claimedTenantId != tenantId.value) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "X-Tenant-Id does not own the requested Period"))
            return@post
        }
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val lines = call.parseJournalLines(request.lines) ?: return@post
        val date = try {
            LocalDate.parse(request.date)
        } catch (e: DateTimeParseException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "date must be ISO-8601 (YYYY-MM-DD)"))
            return@post
        }
        val source = try {
            JournalSource.valueOf(request.source)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "source must be a valid JournalSource"))
            return@post
        }

        val result = postJournalEntryUseCase.execute(
            PostJournalEntryUseCase.Request(PeriodId(periodId), date, lines, source, request.description)
        )

        when (result) {
            is PostJournalEntryResult.Success ->
                call.respond(HttpStatusCode.Created, JournalEntryResponseDto(result.entry.id.value.toString(), result.entry.status.name))
            is PostJournalEntryResult.PeriodNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("period_not_found"))
            is PostJournalEntryResult.PeriodNotOpen ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("period_not_open"))
            is PostJournalEntryResult.InvalidLines ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_lines", result.errors.joinToString()))
            is PostJournalEntryResult.AccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("account_not_found", result.accountId.value.toString()))
        }
    }
}

/** Parses a UUID string, responding 400 and returning `null` on failure - shared by every route file in this package. */
internal suspend fun ApplicationCall.parseUuid(value: String): UUID? =
    try {
        UUID.fromString(value)
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'$value' is not a valid UUID"))
        null
    }

/**
 * Parses every [JournalLineDto] into a domain [JournalLine], responding
 * 400 with the specific reason and returning `null` on the *first*
 * invalid line - deliberately not accumulating every line's errors into
 * one response, unlike `JournalEntry.validateLines()`'s own
 * `ValidationResult` (which does accumulate) - this is wire-format
 * parsing, a different concern from that domain-level business-rule
 * validation, which still runs afterward inside the use case itself
 * once every line has already parsed successfully.
 */
private suspend fun ApplicationCall.parseJournalLines(dtos: List<JournalLineDto>): List<JournalLine>? {
    val lines = mutableListOf<JournalLine>()
    for ((index, dto) in dtos.withIndex()) {
        val accountUuid = try {
            UUID.fromString(dto.accountId)
        } catch (e: IllegalArgumentException) {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "lines[$index].accountId is not a valid UUID"))
            return null
        }
        val currencyValue = try {
            Currency.getInstance(dto.currency)
        } catch (e: IllegalArgumentException) {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "lines[$index].currency is not a valid ISO currency code"))
            return null
        }
        val amountValue = dto.amount.toBigDecimalOrNull()
        if (amountValue == null) {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "lines[$index].amount is not a valid decimal"))
            return null
        }
        val sideValue = try {
            TransactionSide.valueOf(dto.side)
        } catch (e: IllegalArgumentException) {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "lines[$index].side must be DEBIT or CREDIT"))
            return null
        }
        val dimensionsValue = mutableMapOf<DimensionType, String>()
        for ((key, dimensionValue) in dto.dimensions) {
            val dimensionType = try {
                DimensionType.valueOf(key)
            } catch (e: IllegalArgumentException) {
                respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "lines[$index].dimensions has an unknown key '$key'"))
                return null
            }
            dimensionsValue[dimensionType] = dimensionValue
        }
        lines.add(JournalLine(AccountId(accountUuid), Money(amountValue, currencyValue), sideValue, dimensionsValue))
    }
    return lines
}
