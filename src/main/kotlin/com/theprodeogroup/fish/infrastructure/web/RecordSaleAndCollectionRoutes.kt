package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.RecordCollectionResult
import com.theprodeogroup.fish.application.RecordCollectionUseCase
import com.theprodeogroup.fish.application.RecordSaleResult
import com.theprodeogroup.fish.application.RecordSaleUseCase
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.sales.CustomerId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
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
 */
fun Route.recordSaleAndCollectionRoutes(
    recordSaleUseCase: RecordSaleUseCase,
    recordCollectionUseCase: RecordCollectionUseCase,
    companyRepository: CompanyRepository
) {
    post("/sales/record-sale") {
        val request = call.receive<RecordSaleRequestDto>()
        val companyUuid = call.parseUuid(request.companyId) ?: return@post
        val tenantId = call.resolveTenantForCompany(CompanyId(companyUuid), companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val arControlAccountUuid = call.parseUuid(request.arControlAccountId) ?: return@post
        val revenueAccountUuid = call.parseUuid(request.revenueAccountId) ?: return@post
        val customerUuid = call.parseUuid(request.customerId) ?: return@post
        val amount = call.parseMoney(request.amount, request.currency) ?: return@post
        val date = call.parseLocalDate(request.date) ?: return@post

        val result = recordSaleUseCase.execute(
            RecordSaleUseCase.Request(
                PeriodId(periodUuid), date, AccountId(arControlAccountUuid), AccountId(revenueAccountUuid),
                amount, CustomerId(customerUuid), request.description
            )
        )

        when (result) {
            is RecordSaleResult.Success ->
                call.respond(
                    HttpStatusCode.OK,
                    RecordSaleResponseDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name)
                )
            is RecordSaleResult.InvalidAmount -> call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_amount"))
            is RecordSaleResult.PeriodNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("period_not_found"))
            is RecordSaleResult.PeriodNotOpen -> call.respond(HttpStatusCode.Conflict, ErrorResponseDto("period_not_open"))
            is RecordSaleResult.ArControlAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("ar_control_account_not_found", result.accountId.value.toString()))
            is RecordSaleResult.RevenueAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("revenue_account_not_found", result.accountId.value.toString()))
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

        val result = recordCollectionUseCase.execute(
            RecordCollectionUseCase.Request(
                PeriodId(periodUuid), date, AccountId(settlementAccountUuid), AccountId(arControlAccountUuid),
                amount, CustomerId(customerUuid), request.description
            )
        )

        when (result) {
            is RecordCollectionResult.Success ->
                call.respond(
                    HttpStatusCode.OK,
                    RecordCollectionResponseDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name)
                )
            is RecordCollectionResult.InvalidAmount -> call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_amount"))
            is RecordCollectionResult.PeriodNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("period_not_found"))
            is RecordCollectionResult.PeriodNotOpen -> call.respond(HttpStatusCode.Conflict, ErrorResponseDto("period_not_open"))
            is RecordCollectionResult.SettlementAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("settlement_account_not_found", result.accountId.value.toString()))
            is RecordCollectionResult.ArControlAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("ar_control_account_not_found", result.accountId.value.toString()))
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
