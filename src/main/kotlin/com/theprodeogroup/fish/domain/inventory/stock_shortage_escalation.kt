package com.theprodeogroup.fish.domain.inventory

import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Identity of a StockShortageEscalation. */
@JvmInline
value class StockShortageEscalationId(val value: UUID) {
    companion object {
        fun generate(): StockShortageEscalationId = StockShortageEscalationId(UUID.randomUUID())
    }
}

/**
 * A record that someone tried to sell more of a [StockItem] than was on
 * hand (docs/CreateSalesInvoiceUseCase, 2026-08-29 - explicit user
 * instruction: "It must have an approver status in the least to pass...
 * only against a sale with no stock available... the request for the
 * item is logged and escalated to the owner").
 *
 * Created every time [CreateSalesInvoiceUseCase] finds insufficient
 * stock, whether the sale ultimately proceeds or not - [overridden]
 * distinguishes the two: `false` means a WRITE-level caller was hard-
 * blocked (403/409, nothing posted); `true` means an APPROVE-level
 * caller pushed the sale through anyway (`CreateSalesInvoiceResult.Success`,
 * revenue posted) despite the shortfall. Either way, this is what "the
 * request for the item is logged and escalated to the owner" means
 * concretely: a real, queryable record, not just a server log line -
 * `StockShortageEscalationRepository.findAllByCompany` is what an
 * eventual Owner-facing "requests" screen would read from (not built
 * yet - this is the record only, no notification/UI on top of it).
 *
 * Deliberately doesn't touch [StockItem.quantityOnHand] itself - an
 * override sale posts revenue without decrementing stock (`recordIssue`
 * already refuses to go negative), leaving the shortfall visible rather
 * than papering over it with a fabricated negative-quantity concept
 * nobody asked for. Physically restocking, and recording the eventual
 * goods issue once stock exists, stays the Owner's own follow-up action
 * via the already-built `RecordInventoryIssueUseCase`/inventory routes.
 */
class StockShortageEscalation private constructor(
    val id: StockShortageEscalationId,
    val companyId: CompanyId,
    val stockItemId: StockItemId,
    val requestedQuantity: BigDecimal,
    val quantityOnHandAtRequest: BigDecimal,
    val requestedByEmail: String,
    val overridden: Boolean,
    val requestedAt: Instant
) {
    companion object {
        fun create(
            companyId: CompanyId,
            stockItemId: StockItemId,
            requestedQuantity: BigDecimal,
            quantityOnHandAtRequest: BigDecimal,
            requestedByEmail: String,
            overridden: Boolean,
            id: StockShortageEscalationId = StockShortageEscalationId.generate(),
            requestedAt: Instant = Instant.now()
        ): StockShortageEscalation =
            StockShortageEscalation(id, companyId, stockItemId, requestedQuantity, quantityOnHandAtRequest, requestedByEmail, overridden, requestedAt)

        /** Rebuilds a persisted StockShortageEscalation - `internal`, matches every other aggregate's `reconstitute()`. */
        internal fun reconstitute(
            id: StockShortageEscalationId,
            companyId: CompanyId,
            stockItemId: StockItemId,
            requestedQuantity: BigDecimal,
            quantityOnHandAtRequest: BigDecimal,
            requestedByEmail: String,
            overridden: Boolean,
            requestedAt: Instant
        ): StockShortageEscalation =
            StockShortageEscalation(id, companyId, stockItemId, requestedQuantity, quantityOnHandAtRequest, requestedByEmail, overridden, requestedAt)
    }
}
