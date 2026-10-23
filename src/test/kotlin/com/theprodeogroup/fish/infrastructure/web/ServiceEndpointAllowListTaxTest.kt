package com.theprodeogroup.fish.infrastructure.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The Tax service's GL allow-list is exactly the three reads Tax makes (least privilege; docs/GL_Tax_Extraction_Plan.md in
 * fish-tax, Femi's "split by kind", 2026-10-23): the company, the profit and loss for a date range, and the VAT lines of a
 * filing window. Read-only. Source of truth: Tax's GL reader. When Tax gains or loses a read, this list and
 * `ServiceEndpointAllowList` change together, or a deploy would fail closed on a route it needs or hold a door it never uses.
 * The credential is inert until the Tax audience is provisioned (`FISH_JWT_SERVICE_AUDIENCE_TAX`): no verifier, no caller.
 */
class ServiceEndpointAllowListTaxTest {

    @Test
    fun `the tax service may call exactly the three GL reads Tax makes, all GET`() {
        ServiceEndpointAllowList.allowed.getValue("tax") shouldBe setOf(
            "GET /companies/{companyId}",                       // the Company: jurisdiction and base currency
            "GET /companies/{companyId}/reports/profit-and-loss", // the range form (?from&to) for the corporate tax computation
            "GET /companies/{companyId}/vat-lines"              // the VAT control account lines of a filing window, paged
        )
        ServiceEndpointAllowList.allowed.getValue("tax").all { it.startsWith("GET ") } shouldBe true
    }

    @Test
    fun `the tax service is refused every posting route, and every other service keeps its own list`() {
        for (route in listOf("POST /sales/record-sale", "POST /purchasing/record-payment", "POST /journal-entries", "POST /companies/{companyId}/vat-return", "GET /companies/{companyId}/journal-entries")) {
            val method = route.substringBefore(' ')
            val path = route.substringAfter(' ').replace("{companyId}", "00000000-0000-4000-8000-0000000000c0")
            ServiceEndpointAllowList.isAllowed("tax", method, path) shouldBe false
        }
        ServiceEndpointAllowList.isAllowed("tax", "GET", "/api/companies/00000000-0000-4000-8000-0000000000c0/vat-lines") shouldBe true
        // The other services are not widened: SOP still cannot read the VAT lines.
        ServiceEndpointAllowList.isAllowed("sop", "GET", "/api/companies/00000000-0000-4000-8000-0000000000c0/vat-lines") shouldBe false
    }
}
