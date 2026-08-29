package com.theprodeogroup.fish.domain.sales

/**
 * How a sale recorded via [com.theprodeogroup.fish.application.CreateSalesInvoiceUseCase]
 * was settled - a second, independent axis from [SaleType] (goods vs
 * service). Confirmed by the user (2026-08-29): unregistered cash
 * customers are still tagged against a shared, per-Company
 * "Unregistered Cash Customer" [Customer] record (find-or-created on
 * first use) rather than left untracked - the same dimension-tagging
 * mechanism a registered [Customer] gets, so both fold into the same
 * customer-level reporting, and the pooled record's own accumulating
 * history is exactly what the eReceipt's "register for the loyalty
 * programme" nudge is selling: register, and future purchases attach
 * to a name of your own instead of the shared pool.
 *
 * - [CREDIT]: Dr Accounts Receivable Control (tagged [DimensionType.CUSTOMER])
 *   / Cr Revenue. [Customer.recordSale] runs, raising a real
 *   receivable - `customerName` is required, since there's no such
 *   thing as an anonymous debtor.
 * - [CASH]: Dr Cash / Cr Revenue (tagged [DimensionType.CUSTOMER] on
 *   the credit line instead of the debit line, since Cash - not the
 *   customer - is what was actually debited). Invoice and receipt in
 *   one flow, settled on the spot: [Customer.recordSale] does **not**
 *   run, since no receivable is ever created. `customerName` is
 *   optional; a blank name resolves to the pooled "Unregistered Cash
 *   Customer" record.
 */
enum class SaleMethod {
    CASH,
    CREDIT
}
