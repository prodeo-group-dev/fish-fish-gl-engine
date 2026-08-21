package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.PostPurchaseOrderResult
import com.theprodeogroup.fish.application.PostPurchaseOrderUseCase
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderId
import com.theprodeogroup.fish.domain.purchasing.PurchaseOrderRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.infrastructure.persistence.IdempotencyKeyRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json

/**
 * `POST /purchase-orders/{purchaseOrderId}/post` - wraps
 * [PostPurchaseOrderUseCase] (docs/DDD_Design.md Section 10.19), the
 * second of this first pass's two representative endpoints (the other
 * is `journalEntryRoutes`) - deliberately an "ecosystem" use case (one
 * that wraps a domain aggregate, `PurchaseOrder`) rather than a second
 * core-Ledger example, so the demonstrated pattern covers both shapes
 * of use case this codebase has.
 *
 * **Tenant resolution mirrors `journalEntryRoutes` exactly**: the
 * `PurchaseOrder` is loaded first to find its `companyId`, which
 * resolves to the owning Tenant - the same "verify the claimed
 * `X-Tenant-Id` actually owns the resource" check, not just trusted at
 * face value.
 *
 * **Idempotency-Key support** (docs/GL_Production_Readiness_Plan.md) -
 * routes its final execute-and-respond step through [respondIdempotently].
 * `PurchaseOrder` already has its own natural double-post guard
 * (`PurchaseOrderNotDraft`, via its own status), but that's a weaker,
 * different-shaped protection than an idempotency key - it stops a
 * second *different* post attempt after the first one lands, not a
 * literal retry of the exact same request, so both stay in place
 * rather than one substituting for the other.
 */
fun Route.purchaseOrderRoutes(
    postPurchaseOrderUseCase: PostPurchaseOrderUseCase,
    purchaseOrderRepository: PurchaseOrderRepository,
    companyRepository: CompanyRepository,
    idempotencyKeyRepository: IdempotencyKeyRepository
) {
    post("/purchase-orders/{purchaseOrderId}/post") {
        val purchaseOrderIdRaw = call.parameters["purchaseOrderId"]
        if (purchaseOrderIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "purchaseOrderId path parameter is required"))
            return@post
        }
        val purchaseOrderUuid = call.parseUuid(purchaseOrderIdRaw) ?: return@post
        val purchaseOrder = purchaseOrderRepository.findById(PurchaseOrderId(purchaseOrderUuid))
        if (purchaseOrder == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "PurchaseOrder not found"))
            return@post
        }
        val tenantId = call.resolveTenantForCompany(purchaseOrder.companyId, companyRepository) ?: return@post

        val claimedTenantIdRaw = call.request.header("X-Tenant-Id")
        if (claimedTenantIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "X-Tenant-Id header is required"))
            return@post
        }
        val claimedTenantId = call.parseUuid(claimedTenantIdRaw) ?: return@post
        if (claimedTenantId != tenantId.value) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "X-Tenant-Id does not own the requested PurchaseOrder"))
            return@post
        }
        call.authorizeTenantForWrite(tenantId) ?: return@post

        val request = call.receive<PostPurchaseOrderRequestDto>()
        val periodUuid = call.parseUuid(request.periodId) ?: return@post
        val apControlAccountUuid = call.parseUuid(request.apControlAccountId) ?: return@post

        call.respondIdempotently(
            idempotencyKeyRepository, tenantId, "post-purchase-order", Json.encodeToString(PostPurchaseOrderRequestDto.serializer(), request)
        ) {
            val result = postPurchaseOrderUseCase.execute(
                PostPurchaseOrderUseCase.Request(PurchaseOrderId(purchaseOrderUuid), PeriodId(periodUuid), AccountId(apControlAccountUuid))
            )

            when (result) {
                is PostPurchaseOrderResult.Success ->
                    HttpStatusCode.OK to Json.encodeToString(
                        PostPurchaseOrderResponseDto.serializer(),
                        PostPurchaseOrderResponseDto(
                            result.purchaseOrder.id.value.toString(),
                            result.purchaseOrder.status.name,
                            result.journalEntry.id.value.toString()
                        )
                    )
                is PostPurchaseOrderResult.PurchaseOrderNotFound -> HttpStatusCode.NotFound to errorResponseJson("purchase_order_not_found")
                is PostPurchaseOrderResult.PeriodNotFound -> HttpStatusCode.NotFound to errorResponseJson("period_not_found")
                is PostPurchaseOrderResult.PeriodNotOpen -> HttpStatusCode.Conflict to errorResponseJson("period_not_open")
                is PostPurchaseOrderResult.ApControlAccountNotFound ->
                    HttpStatusCode.NotFound to errorResponseJson("ap_control_account_not_found", result.accountId.value.toString())
                is PostPurchaseOrderResult.PurchaseOrderNotDraft -> HttpStatusCode.Conflict to errorResponseJson("purchase_order_not_draft")
            }
        }
    }
}
