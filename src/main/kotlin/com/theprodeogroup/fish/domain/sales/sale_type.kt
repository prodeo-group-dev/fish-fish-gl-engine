package com.theprodeogroup.fish.domain.sales

/**
 * Whether a sale recorded via [com.theprodeogroup.fish.application.CreateSalesInvoiceUseCase]
 * is for goods or a service - the business owner's own distinction
 * (explicit user requirement), carried onto the generated eInvoice.
 * Doesn't change the posting itself: both are Dr AR/Cr Revenue, per the
 * user's accounting instruction - Goods-in-Transit/GRNI-style timing
 * nuances (docs/IFRS_GL_Posting_Matrix.md) are the SOP system's concern,
 * not this quick-sale entry point's.
 */
enum class SaleType {
    GOODS,
    SERVICE
}
