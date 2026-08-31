package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.ComputeInventoryScheduleUseCase
import com.theprodeogroup.fish.application.IssueStockForSaleResult
import com.theprodeogroup.fish.application.IssueStockForSaleUseCase
import com.theprodeogroup.fish.application.PostInventoryIssueResult
import com.theprodeogroup.fish.application.PostInventoryIssueUseCase
import com.theprodeogroup.fish.application.PostInventoryReceiptResult
import com.theprodeogroup.fish.application.PostInventoryReceiptUseCase
import com.theprodeogroup.fish.domain.inventory.StockItem
import com.theprodeogroup.fish.domain.inventory.StockItemId
import com.theprodeogroup.fish.domain.inventory.StockItemRepository
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Currency

/**
 * Inventory Management's standalone posting interface, opened up over
 * HTTP (docs/DDD_Design.md Section 10.21) - the user confirmed both
 * Inventory Management and HR/Payroll must interface with the GL Engine
 * through the API, not any in-process call - `PostInventoryReceiptUseCase`/
 * `PostInventoryIssueUseCase` (Section 10.15/10.16) are Inventory
 * Management's half of that same contract shape `PayrollRoutes`
 * (Section 10.20) already opened for HR/Payroll. Identical auth ->
 * tenant-ownership -> use-case -> `Result`-to-HTTP pattern, applied
 * mechanically now that it's proven across two prior builds.
 *
 * **Two routes, not one** - `PostInventoryReceiptUseCase`/
 * `PostInventoryIssueUseCase` are separate use cases in `application`
 * (mirror images of each other, Section 10.16's own KDoc), so they get
 * separate routes rather than one with a direction flag, the same
 * reasoning already applied to `LeaveAccrual`'s pair.
 *
 * **Idempotency-Key support** (docs/GL_Production_Readiness_Plan.md) -
 * both routes route their final execute-and-respond step through
 * [respondIdempotently].
 *
 * **`GET /companies/{companyId}/stock-items` (2026-08-29)** - added for
 * the SOP dashboard tab's GOODS-sale item picker (`CreateSalesInvoiceUseCase`'s
 * stock check needs a real `stockItemId`) - [authorizeTenantForRead], not
 * [authorizeTenantForWrite], matching `moneyVelocityRoutes`'s reasoning
 * that a READ_ONLY Membership should still be able to view this.
 */
fun Route.inventoryRoutes(
    postInventoryReceiptUseCase: PostInventoryReceiptUseCase,
    postInventoryIssueUseCase: PostInventoryIssueUseCase,
    computeInventoryScheduleUseCase: ComputeInventoryScheduleUseCase,
    issueStockForSaleUseCase: IssueStockForSaleUseCase,
    stockItemRepository: StockItemRepository,
    companyRepository: CompanyRepository,
    idempotencyKeyRepository: IdempotencyKeyRepository
) {
    get("/companies/{companyId}/stock-items") {
        val companyIdRaw = call.parameters["companyId"]
        if (companyIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
            return@get
        }
        val companyUuid = call.parseUuid(companyIdRaw) ?: return@get
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId) ?: return@get

        val items = stockItemRepository.findAllByCompany(companyId).map {
            StockItemSummaryDto(it.id.value.toString(), it.name, it.quantityOnHand.toString(), it.currency.currencyCode)
        }
        call.respond(items)
    }

    get("/companies/{companyId}/inventory-schedule") {
        val companyIdRaw = call.parameters["companyId"]
        if (companyIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyId path parameter is required"))
            return@get
        }
        val companyUuid = call.parseUuid(companyIdRaw) ?: return@get
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId) ?: return@get

        when (val result = computeInventoryScheduleUseCase.execute(companyId)) {
            is ComputeInventoryScheduleUseCase.Result.Success -> {
                val schedule = result.schedule
                call.respond(
                    InventoryScheduleResponseDto(
                        asOfDate = schedule.asOfDate.toString(),
                        currency = schedule.currency.currencyCode,
                        lines = schedule.lines.map {
                            InventoryScheduleLineDto(
                                it.stockItemId.value.toString(), it.name, it.stage.name, it.quantityOnHand.toString(),
                                it.unitCost.amount.toPlainString(), it.totalValue.amount.toPlainString(),
                                it.nrvWriteDownPerUnit.amount.toPlainString(), it.carryingValuePerUnit.amount.toPlainString(),
                                it.totalCarryingValue.amount.toPlainString()
                            )
                        },
                        totalCost = schedule.totalCost.amount.toPlainString(),
                        totalCarryingValue = schedule.totalCarryingValue.amount.toPlainString()
                    )
                )
            }
            ComputeInventoryScheduleUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
        }
    }

    post("/stock-items/{stockItemId}/issue-for-sale") {
        val stockItem = call.loadStockItem(stockItemRepository) ?: return@post
        val tenantId = call.resolveTenantForCompany(stockItem.companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val request = call.receive<IssueStockForSaleRequestDto>()
        val quantity = call.parseBigDecimal(request.quantity, "quantity") ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, tenantId, "issue-stock-for-sale-${stockItem.id.value}",
            Json.encodeToString(IssueStockForSaleRequestDto.serializer(), request)
        ) {
            when (
                val result = issueStockForSaleUseCase.execute(
                    IssueStockForSaleUseCase.Request(
                        stockItem.companyId, stockItem.id, quantity, request.requestedByEmail, request.callerCanOverrideStockCheck
                    )
                )
            ) {
                is IssueStockForSaleResult.Success ->
                    HttpStatusCode.OK to Json.encodeToString(
                        IssueStockForSaleResponseDto.serializer(),
                        IssueStockForSaleResponseDto(result.committedCost.amount.toPlainString(), result.committedCost.currency.currencyCode)
                    )
                is IssueStockForSaleResult.StockItemNotFound -> HttpStatusCode.NotFound to errorResponseJson("stock_item_not_found")
                is IssueStockForSaleResult.InsufficientStock ->
                    HttpStatusCode.Conflict to errorResponseJson(
                        "insufficient_stock", "requested ${result.requestedQuantity}, on hand ${result.quantityOnHand}"
                    )
            }
        }
    }

    post("/stock-items/{stockItemId}/receipts") {
        val stockItem = call.loadStockItem(stockItemRepository) ?: return@post
        val tenantId = call.resolveTenantForCompany(stockItem.companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val request = call.receive<PostInventoryReceiptRequestDto>()
        val quantityReceived = call.parseBigDecimal(request.quantityReceived, "quantityReceived") ?: return@post
        val costReceived = call.parseMoneyDto(request.costReceived, request.costCurrency, "costReceived") ?: return@post
        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val inventoryAssetAccountUuid = call.parseUuid(request.inventoryAssetAccountId) ?: return@post
        val contraAccountUuid = call.parseUuid(request.contraAccountId) ?: return@post
        val date = call.parseInventoryDate(request.date) ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, tenantId, "post-inventory-receipt",
            Json.encodeToString(PostInventoryReceiptRequestDto.serializer(), request)
        ) {
            val result = postInventoryReceiptUseCase.execute(
                PostInventoryReceiptUseCase.Request(
                    stockItem.id, quantityReceived, costReceived,
                    AccountId(inventoryAssetAccountUuid), AccountId(contraAccountUuid), PeriodId(periodUuid), date
                )
            )

            when (result) {
                is PostInventoryReceiptResult.Success ->
                    HttpStatusCode.OK to Json.encodeToString(
                        StockItemJournalEntryResponseDto.serializer(),
                        result.stockItem.toDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name)
                    )
                is PostInventoryReceiptResult.StockItemNotFound -> HttpStatusCode.NotFound to errorResponseJson("stock_item_not_found")
                is PostInventoryReceiptResult.PeriodNotFound -> HttpStatusCode.NotFound to errorResponseJson("period_not_found")
                is PostInventoryReceiptResult.PeriodNotOpen -> HttpStatusCode.Conflict to errorResponseJson("period_not_open")
                is PostInventoryReceiptResult.InventoryAssetAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("inventory_asset_account_not_found", result.accountId.value.toString())
                is PostInventoryReceiptResult.ContraAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("contra_account_not_found", result.accountId.value.toString())
                is PostInventoryReceiptResult.InvalidReceipt ->
                    HttpStatusCode.BadRequest to errorResponseJson("invalid_receipt", result.errors.joinToString("; "))
            }
        }
    }

    post("/stock-items/{stockItemId}/issues") {
        val stockItem = call.loadStockItem(stockItemRepository) ?: return@post
        val tenantId = call.resolveTenantForCompany(stockItem.companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val request = call.receive<PostInventoryIssueRequestDto>()
        val quantityIssued = call.parseBigDecimal(request.quantityIssued, "quantityIssued") ?: return@post
        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val inventoryAssetAccountUuid = call.parseUuid(request.inventoryAssetAccountId) ?: return@post
        val contraAccountUuid = call.parseUuid(request.contraAccountId) ?: return@post
        val date = call.parseInventoryDate(request.date) ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, tenantId, "post-inventory-issue",
            Json.encodeToString(PostInventoryIssueRequestDto.serializer(), request)
        ) {
            val result = postInventoryIssueUseCase.execute(
                PostInventoryIssueUseCase.Request(
                    stockItem.id, quantityIssued,
                    AccountId(inventoryAssetAccountUuid), AccountId(contraAccountUuid), PeriodId(periodUuid), date
                )
            )

            when (result) {
                is PostInventoryIssueResult.Success ->
                    HttpStatusCode.OK to Json.encodeToString(
                        StockItemJournalEntryResponseDto.serializer(),
                        result.stockItem.toDto(result.journalEntry.id.value.toString(), result.journalEntry.status.name)
                    )
                is PostInventoryIssueResult.StockItemNotFound -> HttpStatusCode.NotFound to errorResponseJson("stock_item_not_found")
                is PostInventoryIssueResult.PeriodNotFound -> HttpStatusCode.NotFound to errorResponseJson("period_not_found")
                is PostInventoryIssueResult.PeriodNotOpen -> HttpStatusCode.Conflict to errorResponseJson("period_not_open")
                is PostInventoryIssueResult.InventoryAssetAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("inventory_asset_account_not_found", result.accountId.value.toString())
                is PostInventoryIssueResult.ContraAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("contra_account_not_found", result.accountId.value.toString())
                is PostInventoryIssueResult.InvalidIssue ->
                    HttpStatusCode.BadRequest to errorResponseJson("invalid_issue", result.errors.joinToString("; "))
            }
        }
    }
}

/** Loads the `StockItem` named by the `stockItemId` path parameter, or responds 400/404 and returns `null`. */
private suspend fun ApplicationCall.loadStockItem(stockItemRepository: StockItemRepository): StockItem? {
    val stockItemIdRaw = parameters["stockItemId"]
    if (stockItemIdRaw == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "stockItemId path parameter is required"))
        return null
    }
    val stockItemUuid = parseUuid(stockItemIdRaw) ?: return null
    val stockItem = stockItemRepository.findById(StockItemId(stockItemUuid))
    if (stockItem == null) {
        respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "StockItem not found"))
        return null
    }
    return stockItem
}

/** Parses a decimal quantity, responding 400 and returning `null` on failure. */
private suspend fun ApplicationCall.parseBigDecimal(value: String, fieldName: String): java.math.BigDecimal? {
    val parsed = value.toBigDecimalOrNull()
    if (parsed == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "$fieldName is not a valid decimal"))
        return null
    }
    return parsed
}

/** Parses an amount+currency pair into a domain [Money], responding 400 and returning `null` on failure. */
private suspend fun ApplicationCall.parseMoneyDto(amount: String, currency: String, fieldName: String): Money? {
    val amountValue = amount.toBigDecimalOrNull()
    if (amountValue == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "$fieldName is not a valid decimal"))
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
private suspend fun ApplicationCall.parseInventoryDate(value: String): LocalDate? =
    try {
        LocalDate.parse(value)
    } catch (e: DateTimeParseException) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "date must be ISO-8601 (YYYY-MM-DD)"))
        null
    }

private fun StockItem.toDto(journalEntryId: String, journalEntryStatus: String) =
    StockItemJournalEntryResponseDto(
        stockItemId = id.value.toString(),
        quantityOnHand = quantityOnHand.toString(),
        unitCost = unitCost.amount.toString(),
        unitCostCurrency = unitCost.currency.currencyCode,
        journalEntryId = journalEntryId,
        journalEntryStatus = journalEntryStatus
    )
