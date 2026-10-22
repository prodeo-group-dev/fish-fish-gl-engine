package com.theprodeogroup.fish.infrastructure.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * SOP's GL allow-list is exactly what its GL gateway calls (least privilege). Since 2026-10-22 (GL-T-28, F22-18, GL-FR-TAX-7)
 * SOP works the VAT out itself with `fish-common`'s `VatCalculator`, so it also reads the rate: `GET /companies/{c}/vat-categories`
 * with the sale date as `asOf`. When the gateway gains or loses a route, this list and `ServiceEndpointAllowList` change together.
 */
class ServiceEndpointAllowListSopTest {

    @Test
    fun `the sop service may call exactly the GL routes its gateway calls`() {
        ServiceEndpointAllowList.allowed.getValue("sop") shouldBe setOf(
            "GET /companies/{companyId}",
            "GET /companies/{companyId}/sales-posting-context",
            "GET /companies/{companyId}/sales-invoices",
            "GET /companies/{companyId}/vat-categories",
            "POST /companies/{companyId}/customer-balances",
            "POST /sales/record-sale",
            "POST /sales/record-collection",
            "POST /sales/record-sales-return"
        )
    }
}
