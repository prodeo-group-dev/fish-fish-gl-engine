package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.application.RecordSupplierCreditNoteResult
import com.theprodeogroup.fish.application.RecordSupplierCreditNoteUseCase
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.purchasing.SupplierId
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tax.RateSource
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
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Currency

/**
 * `POST /purchasing/record-credit-note` (2026-10-16): POP's supplier credit note for a discount or a rebate, the
 * purchase-side mirror of `POST /sales/record-sales-return` (POP's Discounts Received design, task T-D5). A new route
 * only: nothing existing changes. `companyId` is in the body, as on `record-payment`, since there is no owning aggregate
 * to derive the Tenant from. Same access sequence as the other posting routes (resolve the Tenant from the Company,
 * verify a claimed Tenant, WRITE at the Company; a service login skips the person check by design) and the same
 * `Idempotency-Key` treatment (only a successful answer is stored). GL computes the VAT on the Company's VERIFIED rate
 * schedule (409 `no_vat_rate_schedule` where there is none); a caller never sends a VAT figure.
 */
fun Route.recordSupplierCreditNoteRoutes(
    recordSupplierCreditNoteUseCase: RecordSupplierCreditNoteUseCase,
    companyRepository: CompanyRepository,
    rateSource: RateSource,
    idempotencyKeyRepository: IdempotencyKeyRepository
) {
    post("/purchasing/record-credit-note") {
        val request = call.receive<RecordSupplierCreditNoteRequestDto>()
        if (!call.recordProvenance(request.sourceReference, request.vatBasis)) return@post
        val companyUuid = call.parseUuid(request.companyId) ?: return@post
        val companyId = CompanyId(companyUuid)
        val company = companyRepository.findById(companyId)
        if (company == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Company not found"))
            return@post
        }
        if (!call.verifyClaimedTenant(company.tenantId)) return@post
        call.authorizeTenantForWrite(company.tenantId, companyId) ?: return@post

        val vatRateSchedule = rateSource.findVerifiedScheduleFor(company.jurisdiction)
        if (vatRateSchedule == null) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponseDto("no_vat_rate_schedule", "No VAT rate schedule is configured for jurisdiction '${company.jurisdiction}'")
            )
            return@post
        }

        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val apUuid = call.parseUuid(request.apControlAccountId) ?: return@post
        val discountsUuid = call.parseUuid(request.discountsReceivedAccountId) ?: return@post
        val vatUuid = call.parseUuid(request.vatControlAccountId) ?: return@post
        val supplierUuid = call.parseUuid(request.supplierId) ?: return@post
        val currency = call.parseCreditNoteCurrency(request.currency) ?: return@post
        val date = call.parseCreditNoteDate(request.date, "date") ?: return@post
        val supplyDate = request.originalSupplyDate?.let { call.parseCreditNoteDate(it, "originalSupplyDate") ?: return@post }
        val kind = try {
            RecordSupplierCreditNoteUseCase.Kind.valueOf(request.kind)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "kind must be DISCOUNT or REBATE"))
            return@post
        }
        val lines = call.parseCreditNoteLines(request.lines, currency) ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, company.tenantId, "record-supplier-credit-note",
            Json.encodeToString(RecordSupplierCreditNoteRequestDto.serializer(), request)
        ) {
            val result = recordSupplierCreditNoteUseCase.execute(
                RecordSupplierCreditNoteUseCase.Request(
                    companyId, PeriodId(periodUuid), date, AccountId(apUuid), AccountId(discountsUuid), AccountId(vatUuid),
                    lines, SupplierId(supplierUuid), kind, vatRateSchedule, supplyDate, request.description
                )
            )

            when (result) {
                is RecordSupplierCreditNoteResult.Success ->
                    HttpStatusCode.OK to Json.encodeToString(
                        RecordSupplierCreditNoteResponseDto.serializer(),
                        RecordSupplierCreditNoteResponseDto(
                            result.journalEntry.id.value.toString(), result.journalEntry.status.name,
                            creditNoteTotal = MoneyAmountDto(result.creditNoteTotal.amount.toPlainString(), result.creditNoteTotal.currency.currencyCode),
                            vat = MoneyAmountDto(result.vat.amount.toPlainString(), result.vat.currency.currencyCode)
                        )
                    )
                is RecordSupplierCreditNoteResult.InvalidAmount -> HttpStatusCode.BadRequest to errorResponseJson("invalid_amount")
                is RecordSupplierCreditNoteResult.PeriodNotFound -> HttpStatusCode.NotFound to errorResponseJson("period_not_found")
                is RecordSupplierCreditNoteResult.PeriodNotOpen -> HttpStatusCode.Conflict to errorResponseJson("period_not_open")
                is RecordSupplierCreditNoteResult.ApControlAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("ap_control_account_not_found", result.accountId.value.toString())
                is RecordSupplierCreditNoteResult.DiscountsReceivedAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("discounts_received_account_not_found", result.accountId.value.toString())
                is RecordSupplierCreditNoteResult.DiscountsReceivedAccountNotAllowed ->
                    HttpStatusCode.BadRequest to errorResponseJson("discounts_received_account_not_allowed", result.accountId.value.toString())
                is RecordSupplierCreditNoteResult.VatControlAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("vat_control_account_not_found", result.accountId.value.toString())
                is RecordSupplierCreditNoteResult.VatCategoryNotSupported ->
                    HttpStatusCode.BadRequest to errorResponseJson("vat_category_not_supported", result.category.name)
                is RecordSupplierCreditNoteResult.VatRateNotInForce ->
                    HttpStatusCode.BadRequest to errorResponseJson("vat_rate_not_in_force", "${result.category.name} on ${result.asOf}")
            }
        }
    }
}

private suspend fun ApplicationCall.parseCreditNoteCurrency(value: String): Currency? =
    try {
        Currency.getInstance(value)
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "currency is not a valid ISO currency code"))
        null
    }

private suspend fun ApplicationCall.parseCreditNoteDate(value: String, field: String): LocalDate? =
    try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "$field must be ISO-8601 (YYYY-MM-DD)"))
        null
    }

/** Parses the credit note lines. Responds 400 and returns `null` on an empty list, a non-positive net amount or an unknown category. */
private suspend fun ApplicationCall.parseCreditNoteLines(lines: List<PurchaseLineDto>, currency: Currency): List<RecordSupplierCreditNoteUseCase.CreditNoteLine>? {
    if (lines.isEmpty()) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "lines must not be empty"))
        return null
    }
    val parsed = mutableListOf<RecordSupplierCreditNoteUseCase.CreditNoteLine>()
    for (line in lines) {
        val netAmount = line.netAmount.toBigDecimalOrNull()
        if (netAmount == null || netAmount.signum() <= 0) {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "line netAmount must be a positive decimal"))
            return null
        }
        val category = try {
            VatCategory.valueOf(line.vatCategory)
        } catch (e: IllegalArgumentException) {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'${line.vatCategory}' is not a valid vatCategory"))
            return null
        }
        parsed.add(RecordSupplierCreditNoteUseCase.CreditNoteLine(Money(netAmount, currency), category))
    }
    return parsed
}
