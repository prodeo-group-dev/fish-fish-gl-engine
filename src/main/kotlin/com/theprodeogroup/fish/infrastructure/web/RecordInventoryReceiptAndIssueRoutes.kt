package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.RecordInventoryIssueResult
import com.theprodeogroup.fish.application.RecordInventoryIssueUseCase
import com.theprodeogroup.fish.application.RecordInventoryReceiptResult
import com.theprodeogroup.fish.application.RecordInventoryReceiptUseCase
import com.theprodeogroup.fish.domain.inventory.StockItemId
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.common.Money
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
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Currency

/**
 * `POST /inventory/record-receipt` and `POST /inventory/record-issue` -
 * HTTP routes for `RecordInventoryReceiptUseCase`/`RecordInventoryIssueUseCase`
 * (`docs/Ecosystem_Extraction_DDD_Design.md` Section 1.3's Option B
 * resolution), the two thin posting interfaces the separate `fish-
 * inventory-management` (IM) system - which now owns the whole IAS 2
 * costing engine - calls into.
 *
 * **Same tenant-resolution departure as `recordSaleAndCollectionRoutes`/
 * `recordVendorObligationAndPaymentRoutes`, for the same reason:**
 * neither use case has an owning aggregate in this repo (`StockItem`
 * still exists, but these routes deliberately don't look it up - that's
 * the whole point of "thin"), so there's no aggregate to derive
 * `companyId` from the way `inventoryRoutes` (the old, still-coexisting
 * `Post*` pair) does via `stockItem.companyId`. The request body
 * carries `companyId` directly instead.
 *
 * **Idempotency-Key support** (docs/GL_Production_Readiness_Plan.md) -
 * both routes route their final execute-and-respond step through
 * [respondIdempotently], the same treatment every other financial-
 * posting endpoint in this package now gets.
 */
fun Route.recordInventoryReceiptAndIssueRoutes(
    recordInventoryReceiptUseCase: RecordInventoryReceiptUseCase,
    recordInventoryIssueUseCase: RecordInventoryIssueUseCase,
    companyRepository: CompanyRepository,
    idempotencyKeyRepository: IdempotencyKeyRepository
) {
    post("/inventory/record-receipt") {
        val request = call.receive<RecordInventoryReceiptRequestDto>()
        val companyUuid = call.parseUuid(request.companyId) ?: return@post
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId, companyId) ?: return@post

        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val inventoryAssetAccountUuid = call.parseUuid(request.inventoryAssetAccountId) ?: return@post
        val contraAccountUuid = call.parseUuid(request.contraAccountId) ?: return@post
        val itemUuid = call.parseUuid(request.itemId) ?: return@post
        val committedCost = call.parseInventoryMoney(request.committedCost, request.committedCostCurrency) ?: return@post
        val date = call.parseInventoryRecordDate(request.date) ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, tenantId, "record-inventory-receipt",
            Json.encodeToString(RecordInventoryReceiptRequestDto.serializer(), request)
        ) {
            val result = recordInventoryReceiptUseCase.execute(
                RecordInventoryReceiptUseCase.Request(
                    PeriodId(periodUuid), date, AccountId(inventoryAssetAccountUuid), AccountId(contraAccountUuid),
                    committedCost, StockItemId(itemUuid), request.description
                )
            )

            when (result) {
                is RecordInventoryReceiptResult.Success ->
                    HttpStatusCode.OK to Json.encodeToString(
                        RecordInventoryReceiptResponseDto.serializer(),
                        RecordInventoryReceiptResponseDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name)
                    )
                is RecordInventoryReceiptResult.InvalidAmount -> HttpStatusCode.BadRequest to errorResponseJson("invalid_amount")
                is RecordInventoryReceiptResult.PeriodNotFound -> HttpStatusCode.NotFound to errorResponseJson("period_not_found")
                is RecordInventoryReceiptResult.PeriodNotOpen -> HttpStatusCode.Conflict to errorResponseJson("period_not_open")
                is RecordInventoryReceiptResult.InventoryAssetAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("inventory_asset_account_not_found", result.accountId.value.toString())
                is RecordInventoryReceiptResult.ContraAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("contra_account_not_found", result.accountId.value.toString())
            }
        }
    }

    post("/inventory/record-issue") {
        val request = call.receive<RecordInventoryIssueRequestDto>()
        val companyUuid = call.parseUuid(request.companyId) ?: return@post
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId, companyId) ?: return@post

        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val contraAccountUuid = call.parseUuid(request.contraAccountId) ?: return@post
        val inventoryAssetAccountUuid = call.parseUuid(request.inventoryAssetAccountId) ?: return@post
        val itemUuid = call.parseUuid(request.itemId) ?: return@post
        val committedCost = call.parseInventoryMoney(request.committedCost, request.committedCostCurrency) ?: return@post
        val date = call.parseInventoryRecordDate(request.date) ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, tenantId, "record-inventory-issue",
            Json.encodeToString(RecordInventoryIssueRequestDto.serializer(), request)
        ) {
            val result = recordInventoryIssueUseCase.execute(
                RecordInventoryIssueUseCase.Request(
                    PeriodId(periodUuid), date, AccountId(contraAccountUuid), AccountId(inventoryAssetAccountUuid),
                    committedCost, StockItemId(itemUuid), request.description
                )
            )

            when (result) {
                is RecordInventoryIssueResult.Success ->
                    HttpStatusCode.OK to Json.encodeToString(
                        RecordInventoryIssueResponseDto.serializer(),
                        RecordInventoryIssueResponseDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name)
                    )
                is RecordInventoryIssueResult.InvalidAmount -> HttpStatusCode.BadRequest to errorResponseJson("invalid_amount")
                is RecordInventoryIssueResult.PeriodNotFound -> HttpStatusCode.NotFound to errorResponseJson("period_not_found")
                is RecordInventoryIssueResult.PeriodNotOpen -> HttpStatusCode.Conflict to errorResponseJson("period_not_open")
                is RecordInventoryIssueResult.ContraAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("contra_account_not_found", result.accountId.value.toString())
                is RecordInventoryIssueResult.InventoryAssetAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("inventory_asset_account_not_found", result.accountId.value.toString())
            }
        }
    }
}

/** Parses an amount+currency pair into a domain [Money], responding 400 and returning `null` on failure. */
private suspend fun ApplicationCall.parseInventoryMoney(amount: String, currency: String): Money? {
    val amountValue = amount.toBigDecimalOrNull()
    if (amountValue == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "committedCost is not a valid decimal"))
        return null
    }
    val currencyValue = try {
        Currency.getInstance(currency)
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "committedCostCurrency is not a valid ISO currency code"))
        return null
    }
    return Money(amountValue, currencyValue)
}

/** Parses an ISO-8601 date string, responding 400 and returning `null` on failure. */
private suspend fun ApplicationCall.parseInventoryRecordDate(value: String): LocalDate? =
    try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "date must be ISO-8601 (YYYY-MM-DD)"))
        null
    }
