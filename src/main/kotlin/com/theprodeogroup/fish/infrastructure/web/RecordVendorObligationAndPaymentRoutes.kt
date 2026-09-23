package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.RecordVendorObligationResult
import com.theprodeogroup.fish.application.RecordVendorObligationUseCase
import com.theprodeogroup.fish.application.RecordVendorPaymentResult
import com.theprodeogroup.fish.application.RecordVendorPaymentUseCase
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.purchasing.CreditorId
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tax.VatRateSchedule
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
 * `POST /purchasing/record-obligation` and `POST /purchasing/record-payment` -
 * HTTP routes for `RecordVendorObligationUseCase`/`RecordVendorPaymentUseCase`
 * (docs/Purchase_Order_Processing_DDD_Design.md Section 0), the two thin
 * posting interfaces the separate `fish-purchase-order-processing`
 * (POP) system calls into. The Purchasing mirror of
 * `recordSaleAndCollectionRoutes` - same tenant-resolution departure
 * from `purchaseOrderRoutes`/`salesOrderRoutes`: neither use case has
 * an owning aggregate in this repo, so the request body carries
 * `companyId` directly, and everything downstream reuses the same
 * shared auth helpers unchanged.
 *
 * **Idempotency-Key support** (docs/GL_Production_Readiness_Plan.md) -
 * both routes route their final execute-and-respond step through
 * [respondIdempotently], the same treatment every other financial-
 * posting endpoint in this package now gets.
 */
fun Route.recordVendorObligationAndPaymentRoutes(
    recordVendorObligationUseCase: RecordVendorObligationUseCase,
    recordVendorPaymentUseCase: RecordVendorPaymentUseCase,
    companyRepository: CompanyRepository,
    idempotencyKeyRepository: IdempotencyKeyRepository
) {
    post("/purchasing/record-obligation") {
        val request = call.receive<RecordVendorObligationRequestDto>()
        val companyUuid = call.parseUuid(request.companyId) ?: return@post
        val company = companyRepository.findById(CompanyId(companyUuid))
        if (company == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Company not found"))
            return@post
        }
        if (!call.verifyClaimedTenant(company.tenantId)) return@post
        call.authorizeTenantForWrite(company.tenantId, company.id) ?: return@post

        val vatRateSchedule = VatRateSchedule.forJurisdiction(company.jurisdiction)
        if (vatRateSchedule == null) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponseDto("no_vat_rate_schedule", "No VAT rate schedule is configured for jurisdiction '${company.jurisdiction}'")
            )
            return@post
        }

        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val expenseOrAssetAccountUuid = call.parseUuid(request.expenseOrAssetAccountId) ?: return@post
        val apControlAccountUuid = call.parseUuid(request.apControlAccountId) ?: return@post
        val vatControlAccountUuid = call.parseUuid(request.vatControlAccountId) ?: return@post
        val vendorUuid = call.parseUuid(request.vendorId) ?: return@post
        val currency = call.parseCurrency(request.currency) ?: return@post
        val date = call.parseLocalDate(request.date) ?: return@post
        val lines = call.parsePurchaseLines(request.lines, currency) ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, company.tenantId, "record-vendor-obligation",
            Json.encodeToString(RecordVendorObligationRequestDto.serializer(), request)
        ) {
            val result = recordVendorObligationUseCase.execute(
                RecordVendorObligationUseCase.Request(
                    PeriodId(periodUuid), date, AccountId(expenseOrAssetAccountUuid), AccountId(apControlAccountUuid),
                    AccountId(vatControlAccountUuid), lines, CreditorId(vendorUuid), vatRateSchedule, request.description
                )
            )

            when (result) {
                is RecordVendorObligationResult.Success ->
                    HttpStatusCode.OK to Json.encodeToString(
                        RecordVendorObligationResponseDto.serializer(),
                        RecordVendorObligationResponseDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name)
                    )
                is RecordVendorObligationResult.InvalidAmount -> HttpStatusCode.BadRequest to errorResponseJson("invalid_amount")
                is RecordVendorObligationResult.PeriodNotFound -> HttpStatusCode.NotFound to errorResponseJson("period_not_found")
                is RecordVendorObligationResult.PeriodNotOpen -> HttpStatusCode.Conflict to errorResponseJson("period_not_open")
                is RecordVendorObligationResult.VatCategoryNotSupported ->
                    HttpStatusCode.BadRequest to errorResponseJson("vat_category_not_supported", result.category.name)
                is RecordVendorObligationResult.ExpenseOrAssetAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("expense_or_asset_account_not_found", result.accountId.value.toString())
                is RecordVendorObligationResult.ApControlAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("ap_control_account_not_found", result.accountId.value.toString())
                is RecordVendorObligationResult.VatControlAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("vat_control_account_not_found", result.accountId.value.toString())
            }
        }
    }

    post("/purchasing/record-payment") {
        val request = call.receive<RecordVendorPaymentRequestDto>()
        val companyUuid = call.parseUuid(request.companyId) ?: return@post
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId, companyId) ?: return@post

        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val apControlAccountUuid = call.parseUuid(request.apControlAccountId) ?: return@post
        val settlementAccountUuid = call.parseUuid(request.settlementAccountId) ?: return@post
        val vendorUuid = call.parseUuid(request.vendorId) ?: return@post
        val amount = call.parseMoney(request.amount, request.currency) ?: return@post
        val date = call.parseLocalDate(request.date) ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, tenantId, "record-vendor-payment",
            Json.encodeToString(RecordVendorPaymentRequestDto.serializer(), request)
        ) {
            val result = recordVendorPaymentUseCase.execute(
                RecordVendorPaymentUseCase.Request(
                    PeriodId(periodUuid), date, AccountId(apControlAccountUuid), AccountId(settlementAccountUuid),
                    amount, CreditorId(vendorUuid), request.description
                )
            )

            when (result) {
                is RecordVendorPaymentResult.Success ->
                    HttpStatusCode.OK to Json.encodeToString(
                        RecordVendorPaymentResponseDto.serializer(),
                        RecordVendorPaymentResponseDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name)
                    )
                is RecordVendorPaymentResult.InvalidAmount -> HttpStatusCode.BadRequest to errorResponseJson("invalid_amount")
                is RecordVendorPaymentResult.PeriodNotFound -> HttpStatusCode.NotFound to errorResponseJson("period_not_found")
                is RecordVendorPaymentResult.PeriodNotOpen -> HttpStatusCode.Conflict to errorResponseJson("period_not_open")
                is RecordVendorPaymentResult.ApControlAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("ap_control_account_not_found", result.accountId.value.toString())
                is RecordVendorPaymentResult.SettlementAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("settlement_account_not_found", result.accountId.value.toString())
            }
        }
    }
}

/** Parses an amount+currency pair into a domain [Money], responding 400 and returning `null` on failure. */
private suspend fun ApplicationCall.parseMoney(amount: String, currency: String): Money? {
    val amountValue = amount.toBigDecimalOrNull()
    if (amountValue == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "amount is not a valid decimal"))
        return null
    }
    val currencyValue = try {
        Currency.getInstance(currency)
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "currency is not a valid ISO currency code"))
        return null
    }
    return Money(amountValue, currencyValue)
}

/** Parses an ISO-8601 date string, responding 400 and returning `null` on failure. */
private suspend fun ApplicationCall.parseLocalDate(value: String): LocalDate? =
    try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "date must be ISO-8601 (YYYY-MM-DD)"))
        null
    }

/** Parses an ISO currency code, responding 400 and returning `null` on failure. */
private suspend fun ApplicationCall.parseCurrency(value: String): Currency? =
    try {
        Currency.getInstance(value)
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "currency is not a valid ISO currency code"))
        null
    }

/**
 * Parses [PurchaseLineDto]s into [RecordVendorObligationUseCase.PurchaseLine]s -
 * the Purchasing mirror of `parseSaleLines`. Responds 400 and returns
 * `null` on a non-positive amount or an unrecognized category name.
 */
private suspend fun ApplicationCall.parsePurchaseLines(lines: List<PurchaseLineDto>, currency: Currency): List<RecordVendorObligationUseCase.PurchaseLine>? {
    if (lines.isEmpty()) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "lines must not be empty"))
        return null
    }
    val parsed = mutableListOf<RecordVendorObligationUseCase.PurchaseLine>()
    for (line in lines) {
        val netAmountValue = line.netAmount.toBigDecimalOrNull()
        if (netAmountValue == null || netAmountValue.signum() <= 0) {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "line netAmount must be a positive decimal"))
            return null
        }
        val category = try {
            VatCategory.valueOf(line.vatCategory)
        } catch (e: IllegalArgumentException) {
            respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'${line.vatCategory}' is not a valid vatCategory"))
            return null
        }
        parsed.add(RecordVendorObligationUseCase.PurchaseLine(Money(netAmountValue, currency), category))
    }
    return parsed
}
