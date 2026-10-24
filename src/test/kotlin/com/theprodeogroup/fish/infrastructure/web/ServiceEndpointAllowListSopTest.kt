package com.theprodeogroup.fish.infrastructure.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * SOP's GL allow-list is exactly what its GL gateway calls (least privilege). Since 2026-10-22 (GL-T-28, F22-18, GL-FR-TAX-7)
 * SOP works the VAT out itself with `fish-common`'s `VatCalculator`, so it also reads the rate: `GET /companies/{c}/vat-categories`
 * with the sale date as `asOf`. Since 2026-10-24 it also reads `GET .../accounts/{id}/balance` for the standing receivables tie-out. When the gateway gains or loses a route, this list and `ServiceEndpointAllowList` change together.
 */
class ServiceEndpointAllowListSopTest {

    @Test
    fun `the sop service may call exactly the GL routes its gateway calls`() {
        ServiceEndpointAllowList.allowed.getValue("sop") shouldBe setOf(
            "GET /companies/{companyId}",
            "GET /companies/{companyId}/sales-posting-context",
            "GET /companies/{companyId}/sales-invoices",
            // The receivables tie-out (GL-T-12a, GL-FR-RPT-5; FR-SOP-AR-26): SOP compares its open total with the 1100 control balance as at a date.
            "GET /companies/{companyId}/accounts/{accountId}/balance",
            "GET /companies/{companyId}/vat-categories",
            "POST /companies/{companyId}/customer-balances",
            "POST /sales/record-sale",
            "POST /sales/record-collection",
            "POST /sales/record-sales-return"
        )
    }
}
