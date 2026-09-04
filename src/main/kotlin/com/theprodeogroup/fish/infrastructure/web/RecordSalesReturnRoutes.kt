package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.RecordSalesReturnResult
import com.theprodeogroup.fish.application.RecordSalesReturnUseCase
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.sales.CustomerId
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
 * `POST /sales/record-sales-return` - the HTTP route for
 * [RecordSalesReturnUseCase] (docs/Returns_Inwards_Requirements_Specification.md
 * Section 3.5), the third thin posting interface `fish-sales-order-
 * processing` (SOP) calls into, alongside `record-sale`/`record-collection`
 * (`RecordSaleAndCollectionRoutes.kt`). Same tenant-resolution shape as
 * those two - no owning aggregate here, so `companyId` travels in the
 * request body and `resolveTenantForCompany`/`verifyClaimedTenant`/
 * `authorizeTenantForWrite` are reused unchanged - and the same
 * Idempotency-Key support via [respondIdempotently].
 */
fun Route.recordSalesReturnRoutes(
    recordSalesReturnUseCase: RecordSalesReturnUseCase,
    companyRepository: CompanyRepository,
    idempotencyKeyRepository: IdempotencyKeyRepository
) {
    post("/sales/record-sales-return") {
        val request = call.receive<RecordSalesReturnRequestDto>()
        val companyUuid = call.parseUuid(request.companyId) ?: return@post
        val tenantId = call.resolveTenantForCompany(CompanyId(companyUuid), companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val salesReturnsAccountUuid = call.parseUuid(request.salesReturnsAccountId) ?: return@post
        val arControlAccountUuid = call.parseUuid(request.arControlAccountId) ?: return@post
        val customerUuid = call.parseUuid(request.customerId) ?: return@post
        val amount = call.parseMoney(request.amount, request.currency) ?: return@post
        val date = call.parseLocalDate(request.date) ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, tenantId, "record-sales-return", Json.encodeToString(RecordSalesReturnRequestDto.serializer(), request)
        ) {
            val result = recordSalesReturnUseCase.execute(
                RecordSalesReturnUseCase.Request(
                    PeriodId(periodUuid), date, AccountId(salesReturnsAccountUuid), AccountId(arControlAccountUuid),
                    amount, CustomerId(customerUuid), request.description
                )
            )

            when (result) {
                is RecordSalesReturnResult.Success ->
                    HttpStatusCode.OK to Json.encodeToString(
                        RecordSalesReturnResponseDto.serializer(),
                        RecordSalesReturnResponseDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name)
                    )
                is RecordSalesReturnResult.InvalidAmount -> HttpStatusCode.BadRequest to errorResponseJson("invalid_amount")
                is RecordSalesReturnResult.PeriodNotFound -> HttpStatusCode.NotFound to errorResponseJson("period_not_found")
                is RecordSalesReturnResult.PeriodNotOpen -> HttpStatusCode.Conflict to errorResponseJson("period_not_open")
                is RecordSalesReturnResult.SalesReturnsAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("sales_returns_account_not_found", result.accountId.value.toString())
                is RecordSalesReturnResult.ArControlAccountNotFound ->
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
