package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.RecordVendorObligationResult
import com.theprodeogroup.fish.application.RecordVendorObligationUseCase
import com.theprodeogroup.fish.application.RecordVendorPaymentResult
import com.theprodeogroup.fish.application.RecordVendorPaymentUseCase
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.purchasing.CreditorId
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
 */
fun Route.recordVendorObligationAndPaymentRoutes(
    recordVendorObligationUseCase: RecordVendorObligationUseCase,
    recordVendorPaymentUseCase: RecordVendorPaymentUseCase,
    companyRepository: CompanyRepository
) {
    post("/purchasing/record-obligation") {
        val request = call.receive<RecordVendorObligationRequestDto>()
        val companyUuid = call.parseUuid(request.companyId) ?: return@post
        val tenantId = call.resolveTenantForCompany(CompanyId(companyUuid), companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val expenseOrAssetAccountUuid = call.parseUuid(request.expenseOrAssetAccountId) ?: return@post
        val apControlAccountUuid = call.parseUuid(request.apControlAccountId) ?: return@post
        val vendorUuid = call.parseUuid(request.vendorId) ?: return@post
        val amount = call.parseMoney(request.amount, request.currency) ?: return@post
        val date = call.parseLocalDate(request.date) ?: return@post

        val result = recordVendorObligationUseCase.execute(
            RecordVendorObligationUseCase.Request(
                PeriodId(periodUuid), date, AccountId(expenseOrAssetAccountUuid), AccountId(apControlAccountUuid),
                amount, CreditorId(vendorUuid), request.description
            )
        )

        when (result) {
            is RecordVendorObligationResult.Success ->
                call.respond(
                    HttpStatusCode.OK,
                    RecordVendorObligationResponseDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name)
                )
            is RecordVendorObligationResult.InvalidAmount -> call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_amount"))
            is RecordVendorObligationResult.PeriodNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("period_not_found"))
            is RecordVendorObligationResult.PeriodNotOpen -> call.respond(HttpStatusCode.Conflict, ErrorResponseDto("period_not_open"))
            is RecordVendorObligationResult.ExpenseOrAssetAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("expense_or_asset_account_not_found", result.accountId.value.toString()))
            is RecordVendorObligationResult.ApControlAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("ap_control_account_not_found", result.accountId.value.toString()))
        }
    }

    post("/purchasing/record-payment") {
        val request = call.receive<RecordVendorPaymentRequestDto>()
        val companyUuid = call.parseUuid(request.companyId) ?: return@post
        val tenantId = call.resolveTenantForCompany(CompanyId(companyUuid), companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val apControlAccountUuid = call.parseUuid(request.apControlAccountId) ?: return@post
        val settlementAccountUuid = call.parseUuid(request.settlementAccountId) ?: return@post
        val vendorUuid = call.parseUuid(request.vendorId) ?: return@post
        val amount = call.parseMoney(request.amount, request.currency) ?: return@post
        val date = call.parseLocalDate(request.date) ?: return@post

        val result = recordVendorPaymentUseCase.execute(
            RecordVendorPaymentUseCase.Request(
                PeriodId(periodUuid), date, AccountId(apControlAccountUuid), AccountId(settlementAccountUuid),
                amount, CreditorId(vendorUuid), request.description
            )
        )

        when (result) {
            is RecordVendorPaymentResult.Success ->
                call.respond(
                    HttpStatusCode.OK,
                    RecordVendorPaymentResponseDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name)
                )
            is RecordVendorPaymentResult.InvalidAmount -> call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_amount"))
            is RecordVendorPaymentResult.PeriodNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("period_not_found"))
            is RecordVendorPaymentResult.PeriodNotOpen -> call.respond(HttpStatusCode.Conflict, ErrorResponseDto("period_not_open"))
            is RecordVendorPaymentResult.ApControlAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("ap_control_account_not_found", result.accountId.value.toString()))
            is RecordVendorPaymentResult.SettlementAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("settlement_account_not_found", result.accountId.value.toString()))
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
