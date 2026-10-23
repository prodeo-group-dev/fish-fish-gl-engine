package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ReverseEntryUseCase
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * `POST /companies/{companyId}/journal-entries/{entryId}/reverse` (GL-RF-RV1; Femi via CM, 2026-10-24: "Refuse, point to CBB"): the generic
 * journal reversal. A person with WRITE at the Company posts a reversing entry for any posted journal of that Company, with an optional
 * `{"reason": "..."}` recorded with the reversal; the original is marked REVERSED in the same transaction. An entry made in a cash or bank
 * book is refused (409 `correct_in_cash_and_bank_books`): it is corrected in Cash and Bank Books, never in the GL. There is no reconciliation
 * check. For people only: it is on no service allow-list, so a service credential gets 403 `forbidden_endpoint`.
 */
fun Route.reverseJournalEntryRoutes(
    reverseEntryUseCase: ReverseEntryUseCase,
    companyRepository: CompanyRepository
) {
    post("/companies/{companyId}/journal-entries/{entryId}/reverse") {
        val companyUuid = call.parseUuid(call.parameters["companyId"] ?: "") ?: return@post
        val entryUuid = call.parseUuid(call.parameters["entryId"] ?: "") ?: return@post
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId, companyId) ?: return@post

        // The body is optional: {"reason": "..."} says why. An empty body still works; a malformed one is a 400.
        val request = try {
            call.receiveText().takeIf { it.isNotBlank() }?.let { Json.decodeFromString(ReverseEntryRequestDto.serializer(), it) }
        } catch (e: SerializationException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "The body, if sent, must be JSON like {\"reason\": \"why\"}"))
            return@post
        }
        if (!call.recordReason(request?.reason)) return@post

        when (val result = reverseEntryUseCase.execute(companyId, JournalEntryId(entryUuid))) {
            is ReverseEntryUseCase.Result.Reversed ->
                call.respond(HttpStatusCode.Created, ReverseEntryResponseDto(result.reversal.id.value.toString(), entryUuid.toString()))
            ReverseEntryUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
            ReverseEntryUseCase.Result.EntryNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("entry_not_found", "No such entry in this Company"))
            ReverseEntryUseCase.Result.AlreadyReversed ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("already_reversed", "This entry was already reversed"))
            ReverseEntryUseCase.Result.NotReversible ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("not_reversible", "This entry cannot be reversed: it is itself a reversal or is not posted"))
            ReverseEntryUseCase.Result.CorrectInCashAndBankBooks ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("correct_in_cash_and_bank_books", "This entry was made in a cash or bank book: correct it in Cash and Bank Books"))
            ReverseEntryUseCase.Result.PeriodNotOpen ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("period_not_open", "The period this entry is in is no longer open"))
        }
    }
}
