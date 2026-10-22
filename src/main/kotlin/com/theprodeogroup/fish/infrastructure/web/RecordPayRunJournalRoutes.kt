package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.application.PayRunJournalKind
import com.theprodeogroup.fish.application.RecordPayRunJournalResult
import com.theprodeogroup.fish.application.RecordPayRunJournalUseCase
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.IdempotencyKeyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json

/**
 * `POST /payroll/record-pay-run-journal` (Femi, 2026-10-19: Payroll becomes its own service) - the purpose-built payroll
 * door agreed with HR on 2026-10-18: one balanced journal of caller-built lines, each posting to an account from the
 * Company's own chart (expense, liability or a cash/bank book by line kind), an unmapped line to Suspense 3910, the
 * payroll element an opaque audit tag. Serves the liability-first pay run, the disbursement and the remittance. The
 * generic `POST /journal-entries` stays the accountant's free-form journal. `companyId` travels in the body (no
 * owning aggregate), as on the other thin posting routes; one Idempotency-Key per approved run is honoured.
 */
fun Route.recordPayRunJournalRoutes(
    recordPayRunJournalUseCase: RecordPayRunJournalUseCase,
    companyRepository: CompanyRepository,
    idempotencyKeyRepository: IdempotencyKeyRepository
) {
    post("/payroll/record-pay-run-journal") {
        val request = call.receive<RecordPayRunJournalRequestDto>()
        if (!call.recordProvenance(request.sourceReference, request.vatBasis)) return@post
        val companyUuid = call.parseUuid(request.companyId) ?: return@post
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId, companyId) ?: return@post

        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val date = call.parseLocalDate(request.date) ?: return@post
        val lines = call.parsePayRunJournalLines(request) ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, tenantId, "record-pay-run-journal",
            Json.encodeToString(RecordPayRunJournalRequestDto.serializer(), request)
        ) {
            val result = recordPayRunJournalUseCase.execute(
                RecordPayRunJournalUseCase.Request(companyId, PeriodId(periodUuid), date, lines, request.description, request.reference)
            )
            when (result) {
                is RecordPayRunJournalResult.Success ->
                    HttpStatusCode.OK to Json.encodeToString(
                        RecordPayRunJournalResponseDto.serializer(),
                        RecordPayRunJournalResponseDto(
                            result.journalEntry.id.value.toString(), result.journalEntry.status.name,
                            total = MoneyAmountDto(result.total.amount.toPlainString(), result.total.currency.currencyCode)
                        )
                    )
                is RecordPayRunJournalResult.InvalidLines -> HttpStatusCode.BadRequest to errorResponseJson("invalid_lines", result.reason)
                is RecordPayRunJournalResult.Unbalanced ->
                    HttpStatusCode.BadRequest to errorResponseJson("unbalanced_journal", "debits ${result.debits.amount.toPlainString()} credits ${result.credits.amount.toPlainString()}")
                RecordPayRunJournalResult.PeriodNotFound -> HttpStatusCode.NotFound to errorResponseJson("period_not_found")
                RecordPayRunJournalResult.PeriodNotOpen -> HttpStatusCode.Conflict to errorResponseJson("period_not_open")
                is RecordPayRunJournalResult.LineAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("line_account_not_found", result.accountId.value.toString())
                is RecordPayRunJournalResult.LineAccountNotAllowed ->
                    HttpStatusCode.BadRequest to errorResponseJson("line_account_not_allowed", "${result.accountId.value}: ${result.reason.name.lowercase()}")
                RecordPayRunJournalResult.SuspenseAccountNotConfigured ->
                    HttpStatusCode.Conflict to errorResponseJson("suspense_account_not_configured")
                RecordPayRunJournalResult.SettlementAccountRequired ->
                    HttpStatusCode.BadRequest to errorResponseJson("settlement_account_required", "a SETTLEMENT line must name a cash or bank book")
            }
        }
    }
}

/** Parses the lines (kind, side, amount, optional account id); responds 400 and returns null on anything malformed. */
private suspend fun ApplicationCall.parsePayRunJournalLines(request: RecordPayRunJournalRequestDto): List<RecordPayRunJournalUseCase.Line>? {
    val currency = parseCurrency(request.currency) ?: return null
    if (request.lines.isEmpty()) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "lines must not be empty"))
        return null
    }
    val parsed = mutableListOf<RecordPayRunJournalUseCase.Line>()
    for (line in request.lines) {
        val kind = PayRunJournalKind.entries.firstOrNull { it.name == line.kind } ?: run {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "kind must be one of ${PayRunJournalKind.entries.joinToString()}"))
            return null
        }
        val side = TransactionSide.entries.firstOrNull { it.name == line.side } ?: run {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "side must be DEBIT or CREDIT"))
            return null
        }
        val amount = line.amount.toBigDecimalOrNull() ?: run {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "line amount must be a decimal"))
            return null
        }
        val accountId = line.accountId?.let { parseUuid(it) ?: return null }
        parsed.add(RecordPayRunJournalUseCase.Line(line.element, kind, side, Money(amount, currency), accountId?.let { AccountId(it) }))
    }
    return parsed
}

private suspend fun ApplicationCall.parseCurrency(currency: String): java.util.Currency? =
    try {
        java.util.Currency.getInstance(currency)
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "currency is not a valid ISO currency code"))
        null
    }

private suspend fun ApplicationCall.parseLocalDate(value: String): java.time.LocalDate? =
    try {
        java.time.LocalDate.parse(value)
    } catch (e: java.time.format.DateTimeParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "date must be ISO-8601 (YYYY-MM-DD)"))
        null
    }
