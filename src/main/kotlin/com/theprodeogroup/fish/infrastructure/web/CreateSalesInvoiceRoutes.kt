package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.CreateSalesInvoiceResult
import com.theprodeogroup.fish.application.CreateSalesInvoiceUseCase
import com.theprodeogroup.fish.application.ListSalesInvoicesUseCase
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.inventory.StockItemId
import com.theprodeogroup.fish.domain.sales.CustomerRepository
import com.theprodeogroup.fish.domain.sales.SaleMethod
import com.theprodeogroup.fish.domain.sales.SaleType
import com.theprodeogroup.fish.domain.tenancy.AccessLevel
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.IdempotencyKeyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency

/**
 * `POST /sales/create-invoice` - the business-owner-facing "record a
 * sale" route behind the SOP dashboard tab, backed by
 * [CreateSalesInvoiceUseCase]. Same `companyId`-in-body/tenant-resolution
 * shape as `recordSaleAndCollectionRoutes` (no owning aggregate in this
 * repo to derive `companyId` from otherwise), and the same
 * Idempotency-Key mechanism every posting route in this package uses.
 *
 * **Ordinary [authorizeTenantForWrite] floor - the APPROVE floor is
 * narrower than that** (corrected 2026-08-29 after an initial, wrong
 * read of the user's instruction as a blanket route-level requirement;
 * the actual rule only applies when a GOODS sale finds insufficient
 * stock - see [CreateSalesInvoiceUseCase]'s own KDoc). Any WRITE-level
 * caller can attempt a sale; `callerCanOverrideStockCheck` (resolved
 * here from the caller's real `Membership.accessLevel` for this Tenant,
 * `>= AccessLevel.APPROVE`) is what the use case actually branches on
 * when stock falls short, not this route's own authorization gate.
 *
 * **Two read routes added alongside it (2026-08-29, user request):**
 * `GET /companies/{companyId}/customers` (the "Schedule of Customers" -
 * the SOP sale form's customer picker source, same summary-DTO shape
 * as `inventoryRoutes`' `/stock-items`) and
 * `GET /companies/{companyId}/sales-invoices` (the "listing of sales
 * (each timestamped)", backed by [ListSalesInvoicesUseCase]/
 * `SalesInvoiceRecord`). Both use [authorizeTenantForRead], matching
 * every other read route in this package.
 */
fun Route.createSalesInvoiceRoutes(
    createSalesInvoiceUseCase: CreateSalesInvoiceUseCase,
    listSalesInvoicesUseCase: ListSalesInvoicesUseCase,
    companyRepository: CompanyRepository,
    customerRepository: CustomerRepository,
    idempotencyKeyRepository: IdempotencyKeyRepository
) {
    get("/companies/{companyId}/customers") {
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

        val customers = customerRepository.findAllByCompany(companyId).map {
            CustomerSummaryDto(it.id.value.toString(), it.name, it.balance.amount.toPlainString(), it.balance.currency.currencyCode)
        }
        call.respond(customers)
    }

    get("/companies/{companyId}/sales-invoices") {
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

        when (val result = listSalesInvoicesUseCase.execute(companyId)) {
            is ListSalesInvoicesUseCase.Result.Success -> call.respond(
                result.records.map {
                    SalesInvoiceRecordDto(
                        it.invoiceNumber, it.journalEntryId.value.toString(), it.customerId.value.toString(), it.customerName,
                        it.saleType.name, it.saleMethod.name, it.amount.amount.toPlainString(), it.amount.currency.currencyCode,
                        it.paid, it.description, DateTimeFormatter.ISO_INSTANT.format(it.recordedAt)
                    )
                }
            )
            ListSalesInvoicesUseCase.Result.CompanyNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("company_not_found", "Company not found"))
        }
    }

    post("/sales/create-invoice") {
        val request = call.receive<CreateSalesInvoiceRequestDto>()
        val companyUuid = call.parseUuid(request.companyId) ?: return@post
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        val caller = call.authorizeTenantForWrite(tenantId) ?: return@post
        val callerMembership = caller.memberships.first { it.tenantId == tenantId }
        val callerCanOverrideStockCheck = callerMembership.accessLevel.atLeast(AccessLevel.APPROVE)

        val saleType = try {
            SaleType.valueOf(request.saleType.uppercase())
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "saleType must be GOODS or SERVICE"))
            return@post
        }
        val saleMethod = try {
            SaleMethod.valueOf(request.saleMethod.uppercase())
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "saleMethod must be CASH or CREDIT"))
            return@post
        }
        val amountValue = request.amount.toBigDecimalOrNull()
        if (amountValue == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "amount is not a valid decimal"))
            return@post
        }
        val currency = try {
            Currency.getInstance(request.currency)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "currency is not a valid ISO currency code"))
            return@post
        }
        val date = if (request.date.isNullOrBlank()) LocalDate.now() else {
            try {
                LocalDate.parse(request.date)
            } catch (e: java.time.format.DateTimeParseException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "date must be ISO-8601 (YYYY-MM-DD)"))
                return@post
            }
        }
        val stockItemUuid = if (request.stockItemId.isNullOrBlank()) null else call.parseUuid(request.stockItemId) ?: return@post
        val quantity = if (request.quantity.isNullOrBlank()) null else {
            request.quantity.toBigDecimalOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "quantity is not a valid decimal"))
                return@post
            }
        }

        call.respondIdempotently(
            idempotencyKeyRepository, tenantId, "create-sales-invoice",
            Json.encodeToString(CreateSalesInvoiceRequestDto.serializer(), request)
        ) {
            val result = createSalesInvoiceUseCase.execute(
                CreateSalesInvoiceUseCase.Request(
                    companyId, saleType, saleMethod, request.customerName, Money(amountValue, currency), date,
                    caller.user.email, request.description,
                    stockItemUuid?.let { StockItemId(it) }, quantity, callerCanOverrideStockCheck
                )
            )

            when (result) {
                is CreateSalesInvoiceResult.Success -> {
                    val companyName = companyRepository.findById(companyId)?.name ?: ""
                    HttpStatusCode.OK to Json.encodeToString(
                        CreateSalesInvoiceResponseDto.serializer(),
                        CreateSalesInvoiceResponseDto(
                            result.invoiceNumber,
                            result.journalEntry.id.value.toString(),
                            result.journalEntry.status.name,
                            result.paid,
                            date.toString(),
                            request.companyId,
                            companyName,
                            result.customer.name,
                            saleType.name,
                            saleMethod.name,
                            request.description,
                            request.amount,
                            request.currency
                        )
                    )
                }
                is CreateSalesInvoiceResult.InvalidAmount -> HttpStatusCode.BadRequest to errorResponseJson("invalid_amount")
                is CreateSalesInvoiceResult.BlankCustomerName -> HttpStatusCode.BadRequest to errorResponseJson("blank_customer_name")
                is CreateSalesInvoiceResult.NoOpenPeriod -> HttpStatusCode.Conflict to errorResponseJson("no_open_period")
                is CreateSalesInvoiceResult.ArControlAccountNotConfigured ->
                    HttpStatusCode.Conflict to errorResponseJson("ar_control_account_not_configured")
                is CreateSalesInvoiceResult.CashAccountNotConfigured ->
                    HttpStatusCode.Conflict to errorResponseJson("cash_account_not_configured")
                is CreateSalesInvoiceResult.RevenueAccountNotConfigured ->
                    HttpStatusCode.Conflict to errorResponseJson("revenue_account_not_configured")
                is CreateSalesInvoiceResult.MissingStockItemSelection ->
                    HttpStatusCode.BadRequest to errorResponseJson("missing_stock_item_selection")
                is CreateSalesInvoiceResult.StockItemNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("stock_item_not_found", result.stockItemId.value.toString())
                is CreateSalesInvoiceResult.InsufficientStock ->
                    HttpStatusCode.Conflict to errorResponseJson(
                        "insufficient_stock",
                        "Requested ${result.requestedQuantity}, only ${result.quantityOnHand} on hand - logged and escalated to the owner."
                    )
            }
        }
    }
}
