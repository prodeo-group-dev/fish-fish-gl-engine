package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeVatLinesUseCase
import com.theprodeogroup.fish.domain.tenancy.AccessLevel
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.domain.tenancy.ManagedModule
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** The default and the largest page of `GET /companies/{companyId}/vat-lines` (design GL_VAT_Lines_Read_Design.md section 3). */
private const val DEFAULT_VAT_LINES_LIMIT = 500
private const val MAX_VAT_LINES_LIMIT = 2000

/**
 * `GET /companies/{companyId}/vat-lines?from=&to=&limit=&cursor=` (GL-T-32, GL-FR-TAX-10; Tax T-TS-13): the posted lines on the Company's VAT
 * Control Account in a window, as raw facts, cursor-paged. See [ComputeVatLinesUseCase] and `docs/reports/Playbooks/GL/GL_VAT_Lines_Read_Design.md`.
 * Read access to the Tax module (the module that gates the VAT return); not on any service allow-list until the Tax credential exists.
 */
fun Route.vatLinesRoutes(
    computeVatLinesUseCase: ComputeVatLinesUseCase,
    companyRepository: CompanyRepository
) {
    get("/companies/{companyId}/vat-lines") {
        val companyIdRaw = call.parameters["companyId"]
        if (companyIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
            return@get
        }
        val companyUuid = call.parseUuid(companyIdRaw) ?: return@get
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForModule(tenantId, companyId, ManagedModule.TAX, AccessLevel.READ) ?: return@get

        val from = call.requiredDate("from") ?: return@get
        val to = call.requiredDate("to") ?: return@get
        val limitRaw = call.request.queryParameters["limit"]
        val limit = if (limitRaw == null) DEFAULT_VAT_LINES_LIMIT else limitRaw.toIntOrNull()
        if (limit == null || limit < 1 || limit > MAX_VAT_LINES_LIMIT) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "limit must be a whole number from 1 to $MAX_VAT_LINES_LIMIT"))
            return@get
        }

        when (val result = computeVatLinesUseCase.execute(ComputeVatLinesUseCase.Request(companyId, from, to, limit, call.request.queryParameters["cursor"]))) {
            is ComputeVatLinesUseCase.Result.Success -> call.respond(
                VatLinesResponseDto(
                    companyId = companyId.value.toString(),
                    vatControlAccountId = result.vatControlAccountId.value.toString(),
                    currency = result.currency.currencyCode,
                    from = result.from.toString(),
                    to = result.to.toString(),
                    recordedUpTo = result.recordedUpTo.toString(),
                    lines = result.lines.map { line ->
                        VatLineDto(
                            entryId = line.entryId.value.toString(),
                            date = line.date.toString(),
                            side = line.side.name,
                            amount = line.amount.amount.toPlainString(),
                            currency = line.amount.currency.currencyCode,
                            vatDirection = line.vatDirection,
                            vatCategory = line.vatCategory,
                            entryStatus = line.entryStatus,
                            sourceReference = line.sourceReference?.let { SourceReferenceReadDto(it.type.name, it.id, it.number) },
                            reversalOfEntryId = line.reversalOfEntryId?.value?.toString()
                        )
                    },
                    nextCursor = result.nextCursor
                )
            )
            ComputeVatLinesUseCase.Result.CompanyNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Company not found"))
            ComputeVatLinesUseCase.Result.VatControlAccountNotConfigured ->
                call.respond(HttpStatusCode.Conflict, ErrorResponseDto("vat_control_account_not_configured", "This Company has no VAT control account"))
            ComputeVatLinesUseCase.Result.InvalidRange -> call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_range", "to cannot be before from"))
            ComputeVatLinesUseCase.Result.InvalidCursor -> call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "cursor is not one this read returned"))
        }
    }
}

private suspend fun ApplicationCall.requiredDate(name: String): LocalDate? {
    val raw = request.queryParameters[name]
    if (raw == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "$name is required, YYYY-MM-DD"))
        return null
    }
    return try {
        LocalDate.parse(raw)
    } catch (e: DateTimeParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "$name must be a date, YYYY-MM-DD"))
        null
    }
}
