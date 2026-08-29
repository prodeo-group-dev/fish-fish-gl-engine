package com.theprodeogroup.fish.domain.sales

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.time.Instant
import java.util.UUID

/** Identity of a [SalesInvoiceRecord]. */
@JvmInline
value class SalesInvoiceRecordId(val value: UUID) {
    companion object {
        fun generate(): SalesInvoiceRecordId = SalesInvoiceRecordId(UUID.randomUUID())
    }
}

/**
 * A persisted record of one `CreateSalesInvoiceUseCase` success (2026-08-29,
 * user request: "a listing of sales (each timestamped) on the SOP
 * screen") - previously the invoice number/customer/amount were
 * computed fresh on every call and handed straight back in the HTTP
 * response, never stored anywhere queryable. [recordedAt] is a real
 * `Instant`, not [JournalEntryId]'s owning entry's business `date`
 * (`LocalDate`, no time-of-day) - the point of "timestamped" is
 * ordering same-day sales, which a bare date can't do.
 *
 * Deliberately a thin, append-only log alongside the `JournalEntry` it
 * points to via [journalEntryId] - not a new source of financial truth.
 * The posting itself is still exactly what `CreateSalesInvoiceUseCase`
 * already builds; this only makes that fact listable, the same
 * "log the fact, don't recompute it" reasoning [com.theprodeogroup.fish.domain.inventory.StockShortageEscalation]
 * already established for the stock-shortage side of the same feature.
 */
class SalesInvoiceRecord private constructor(
    val id: SalesInvoiceRecordId,
    val companyId: CompanyId,
    val journalEntryId: JournalEntryId,
    val invoiceNumber: String,
    val customerId: CustomerId,
    val customerName: String,
    val saleType: SaleType,
    val saleMethod: SaleMethod,
    val amount: Money,
    val paid: Boolean,
    val description: String?,
    val recordedAt: Instant
) {
    companion object {
        fun create(
            companyId: CompanyId,
            journalEntryId: JournalEntryId,
            invoiceNumber: String,
            customerId: CustomerId,
            customerName: String,
            saleType: SaleType,
            saleMethod: SaleMethod,
            amount: Money,
            paid: Boolean,
            description: String?,
            id: SalesInvoiceRecordId = SalesInvoiceRecordId.generate(),
            recordedAt: Instant = Instant.now()
        ): SalesInvoiceRecord =
            SalesInvoiceRecord(
                id, companyId, journalEntryId, invoiceNumber, customerId, customerName, saleType, saleMethod,
                amount, paid, description, recordedAt
            )

        /** Rebuilds a persisted SalesInvoiceRecord - `internal`, matches every other aggregate's `reconstitute()`. */
        internal fun reconstitute(
            id: SalesInvoiceRecordId,
            companyId: CompanyId,
            journalEntryId: JournalEntryId,
            invoiceNumber: String,
            customerId: CustomerId,
            customerName: String,
            saleType: SaleType,
            saleMethod: SaleMethod,
            amount: Money,
            paid: Boolean,
            description: String?,
            recordedAt: Instant
        ): SalesInvoiceRecord =
            SalesInvoiceRecord(
                id, companyId, journalEntryId, invoiceNumber, customerId, customerName, saleType, saleMethod,
                amount, paid, description, recordedAt
            )
    }
}
