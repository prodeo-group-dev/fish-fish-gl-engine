package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.RecordCollectionResult
import com.theprodeogroup.fish.application.RecordCollectionUseCase
import com.theprodeogroup.fish.application.RecordSaleResult
import com.theprodeogroup.fish.application.RecordSaleUseCase
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.sales.CustomerId
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
 * `POST /sales/record-sale` and `POST /sales/record-collection` - HTTP
 * routes for `RecordSaleUseCase`/`RecordCollectionUseCase`
 * (docs/Sales_Order_Processing_DDD_Design.md Section 0), the two thin
 * posting interfaces the separate `fish-sales-order-processing` (SOP)
 * system calls into.
 *
 * **Departs from every other route in this package on tenant
 * resolution, confirmed with the user before building:** neither use
 * case has an owning aggregate in this repo (that's the whole point of
 * them being "thin" - no `SalesOrder`/`Customer` lookup happens here),
 * so there's no aggregate to derive `companyId` from the way
 * `salesOrderRoutes`/`purchaseOrderRoutes` do. The request body carries
 * `companyId` directly instead; everything downstream of that
 * (`resolveTenantForCompany`/`verifyClaimedTenant`/`authorizeTenantForWrite`)
 * reuses the exact same shared auth helpers unchanged.
 *
 * **Idempotency-Key support** (docs/GL_Production_Readiness_Plan.md) -
 * both routes route their final execute-and-respond step through
 * [respondIdempotently], the shared mechanism every posting route in
 * this package now uses. See its own KDoc for the full contract.
 */
fun Route.recordSaleAndCollectionRoutes(
    recordSaleUseCase: RecordSaleUseCase,
    recordCollectionUseCase: RecordCollectionUseCase,
    companyRepository: CompanyRepository,
    idempotencyKeyRepository: IdempotencyKeyRepository
) {
    post("/sales/record-sale") {
        val request = call.receive<RecordSaleRequestDto>()
        val companyUuid = call.parseUuid(request.companyId) ?: return@post
        val company = companyRepository.findById(CompanyId(companyUuid))
        if (company == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Company not found"))
            return@post
        }
        if (!call.verifyClaimedTenant(company.tenantId)) return@post
        call.authorizeTenantForWrite(company.tenantId) ?: return@post

        val vatRateSchedule = VatRateSchedule.forJurisdiction(company.jurisdiction)
        if (vatRateSchedule == null) {
            call.respond(
                HttpStatusCode.Conflict,
                ErrorResponseDto("no_vat_rate_schedule", "No VAT rate schedule is configured for jurisdiction '${company.jurisdiction}'")
            )
            return@post
        }

        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val arControlAccountUuid = call.parseUuid(request.arControlAccountId) ?: return@post
        val revenueAccountUuid = call.parseUuid(request.revenueAccountId) ?: return@post
        val vatControlAccountUuid = call.parseUuid(request.vatControlAccountId) ?: return@post
        val customerUuid = call.parseUuid(request.customerId) ?: return@post
        val currency = call.parseCurrency(request.currency) ?: return@post
        val date = call.parseLocalDate(request.date) ?: return@post
        val lines = call.parseSaleLines(request.lines, currency) ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, company.tenantId, "record-sale", Json.encodeToString(RecordSaleRequestDto.serializer(), request)
        ) {
            val result = recordSaleUseCase.execute(
                RecordSaleUseCase.Request(
                    PeriodId(periodUuid), date, AccountId(arControlAccountUuid), AccountId(revenueAccountUuid),
                    AccountId(vatControlAccountUuid), lines, CustomerId(customerUuid), vatRateSchedule, request.description
                )
            )

            when (result) {
                is RecordSaleResult.Success ->
                    HttpStatusCode.OK to Json.encodeToString(
                        RecordSaleResponseDto.serializer(),
                        RecordSaleResponseDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name)
                    )
                is RecordSaleResult.InvalidAmount -> HttpStatusCode.BadRequest to errorResponseJson("invalid_amount")
                is RecordSaleResult.PeriodNotFound -> HttpStatusCode.NotFound to errorResponseJson("period_not_found")
                is RecordSaleResult.PeriodNotOpen -> HttpStatusCode.Conflict to errorResponseJson("period_not_open")
                is RecordSaleResult.VatCategoryNotSupported ->
                    HttpStatusCode.BadRequest to errorResponseJson("vat_category_not_supported", result.category.name)
                is RecordSaleResult.ArControlAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("ar_control_account_not_found", result.accountId.value.toString())
                is RecordSaleResult.RevenueAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("revenue_account_not_found", result.accountId.value.toString())
                is RecordSaleResult.VatControlAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("vat_control_account_not_found", result.accountId.value.toString())
            }
        }
    }

    post("/sales/record-collection") {
        val request = call.receive<RecordCollectionRequestDto>()
        val companyUuid = call.parseUuid(request.companyId) ?: return@post
        val tenantId = call.resolveTenantForCompany(CompanyId(companyUuid), companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val settlementAccountUuid = call.parseUuid(request.settlementAccountId) ?: return@post
        val arControlAccountUuid = call.parseUuid(request.arControlAccountId) ?: return@post
        val customerUuid = call.parseUuid(request.customerId) ?: return@post
        val amount = call.parseMoney(request.amount, request.currency) ?: return@post
        val date = call.parseLocalDate(request.date) ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, tenantId, "record-collection", Json.encodeToString(RecordCollectionRequestDto.serializer(), request)
        ) {
            val result = recordCollectionUseCase.execute(
                RecordCollectionUseCase.Request(
                    PeriodId(periodUuid), date, AccountId(settlementAccountUuid), AccountId(arControlAccountUuid),
                    amount, CustomerId(customerUuid), request.description
                )
            )

            when (result) {
                is RecordCollectionResult.Success ->
                    HttpStatusCode.OK to Json.encodeToString(
                        RecordCollectionResponseDto.serializer(),
                        RecordCollectionResponseDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name)
                    )
                is RecordCollectionResult.InvalidAmount -> HttpStatusCode.BadRequest to errorResponseJson("invalid_amount")
                is RecordCollectionResult.PeriodNotFound -> HttpStatusCode.NotFound to errorResponseJson("period_not_found")
                is RecordCollectionResult.PeriodNotOpen -> HttpStatusCode.Conflict to errorResponseJson("period_not_open")
                is RecordCollectionResult.SettlementAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("settlement_account_not_found", result.accountId.value.toString())
                is RecordCollectionResult.ArControlAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("ar_control_account_not_found", result.accountId.value.toString())
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
 * Parses [SaleLineDto]s into [RecordSaleUseCase.SaleLine]s - GL resolves
 * the VAT rate/amount itself (this use case's own KDoc), so the wire
 * shape only ever carries a net amount and a raw [VatCategory] name.
 * Responds 400 and returns `null` on a non-positive amount or an
 * unrecognized category name, rather than letting either throw
 * uncaught out of the route.
 */
private suspend fun ApplicationCall.parseSaleLines(lines: List<SaleLineDto>, currency: Currency): List<RecordSaleUseCase.SaleLine>? {
    if (lines.isEmpty()) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "lines must not be empty"))
        return null
    }
    val parsed = mutableListOf<RecordSaleUseCase.SaleLine>()
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
        parsed.add(RecordSaleUseCase.SaleLine(Money(netAmountValue, currency), category))
    }
    return parsed
}
