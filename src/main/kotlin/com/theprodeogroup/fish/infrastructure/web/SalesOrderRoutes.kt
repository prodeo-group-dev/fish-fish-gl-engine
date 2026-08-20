package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.PostSalesOrderResult
import com.theprodeogroup.fish.application.PostSalesOrderUseCase
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.sales.SalesOrderId
import com.theprodeogroup.fish.domain.sales.SalesOrderRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * `POST /sales-orders/{salesOrderId}/post` - wraps
 * [PostSalesOrderUseCase] (docs/DDD_Design.md Section 10.22), the last
 * of the four "ecosystem" posting use cases to get an HTTP route -
 * Purchase Order Processing (Section 10.19) and Inventory Management
 * (Section 10.21) were already open, Payroll's equivalent (Section
 * 10.20) is `PayrollRoutes`. Confirmed with the user directly: the GL
 * Engine stays a pure General Ledger - Sales Order Processing's actual
 * order/fulfillment logic lives in a separate consuming system, the
 * same relationship HR/Payroll already has, and this route is exactly
 * that thin posting interface, nothing more.
 *
 * **One line per call, not the whole order** - matches
 * `PostSalesOrderUseCase.Request.lineIndex` directly, since
 * `SalesOrder.deliverLine()` itself only recognizes one line's income
 * per call by design (Section 10.14). A caller delivering a multi-line
 * order calls this route once per line.
 *
 * **Same auth -> tenant-ownership -> use-case -> `Result`-to-HTTP
 * pattern as every other route in this layer.**
 */
fun Route.salesOrderRoutes(
    postSalesOrderUseCase: PostSalesOrderUseCase,
    salesOrderRepository: SalesOrderRepository,
    companyRepository: CompanyRepository
) {
    post("/sales-orders/{salesOrderId}/post") {
        val salesOrderIdRaw = call.parameters["salesOrderId"]
        if (salesOrderIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "salesOrderId path parameter is required"))
            return@post
        }
        val salesOrderUuid = call.parseUuid(salesOrderIdRaw) ?: return@post
        val salesOrder = salesOrderRepository.findById(SalesOrderId(salesOrderUuid))
        if (salesOrder == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "SalesOrder not found"))
            return@post
        }
        val tenantId = call.resolveTenantForCompany(salesOrder.companyId, companyRepository) ?: return@post
        if (!call.verifyClaimedTenant(tenantId)) return@post
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val request = call.receive<PostSalesOrderRequestDto>()
        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val arControlAccountUuid = call.parseUuid(request.arControlAccountId) ?: return@post
        val cogsExpenseAccountUuid = request.cogsExpenseAccountId?.let { call.parseUuid(it) ?: return@post }
        val inventoryAssetAccountUuid = request.inventoryAssetAccountId?.let { call.parseUuid(it) ?: return@post }

        val result = postSalesOrderUseCase.execute(
            PostSalesOrderUseCase.Request(
                SalesOrderId(salesOrderUuid), request.lineIndex, PeriodId(periodUuid), AccountId(arControlAccountUuid),
                cogsExpenseAccountUuid?.let { AccountId(it) }, inventoryAssetAccountUuid?.let { AccountId(it) }
            )
        )

        when (result) {
            is PostSalesOrderResult.Success ->
                call.respond(
                    HttpStatusCode.OK,
                    PostSalesOrderResponseDto(
                        result.salesOrder.id.value.toString(),
                        result.salesOrder.status.name,
                        result.journalEntry.id.value.toString()
                    )
                )
            is PostSalesOrderResult.SalesOrderNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("sales_order_not_found"))
            is PostSalesOrderResult.PeriodNotFound -> call.respond(HttpStatusCode.NotFound, ErrorResponseDto("period_not_found"))
            is PostSalesOrderResult.PeriodNotOpen -> call.respond(HttpStatusCode.Conflict, ErrorResponseDto("period_not_open"))
            is PostSalesOrderResult.ArControlAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("ar_control_account_not_found", result.accountId.value.toString()))
            is PostSalesOrderResult.InvalidLineIndex -> call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("invalid_line_index"))
            is PostSalesOrderResult.LineAlreadyDelivered -> call.respond(HttpStatusCode.Conflict, ErrorResponseDto("line_already_delivered"))
            is PostSalesOrderResult.GoodsLineRequiresInventoryAccounts ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("goods_line_requires_inventory_accounts"))
            is PostSalesOrderResult.InventoryAccountNotFound ->
                call.respond(HttpStatusCode.NotFound, ErrorResponseDto("inventory_account_not_found", result.accountId.value.toString()))
            is PostSalesOrderResult.InsufficientStock -> call.respond(HttpStatusCode.Conflict, ErrorResponseDto("insufficient_stock"))
        }
    }
}
