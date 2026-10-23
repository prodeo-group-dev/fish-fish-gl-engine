package com.theprodeogroup.fish.infrastructure.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * IM's GL allow-list is exactly what its GL gateway calls (least privilege). Beside the posting context and its two postings, IM reads two
 * things, both GET and read-only (2026-10-23, CM priority, from IM):
 * - `GET /companies/{c}` for the Company's currency, which its item-create currency guard checks (without it every item create is a 503), and
 * - `GET /companies/{c}/accounts` for `lookupAccount`, which validates an item's default expense or revenue account (live since IM :38; with the
 *   old list the call was 403 `forbidden_endpoint` and IM answered 502 `gl_engine_call_failed`).
 * When IM's gateway gains or loses a route, this list and `ServiceEndpointAllowList` change together.
 */
class ServiceEndpointAllowListImTest {

    @Test
    fun `the im service may call exactly the GL routes its gateway calls`() {
        ServiceEndpointAllowList.allowed.getValue("im") shouldBe setOf(
            "GET /companies/{companyId}",
            "GET /companies/{companyId}/accounts",
            "GET /companies/{companyId}/inventory-posting-context",
            "POST /inventory/record-receipt",
            "POST /inventory/record-issue"
        )
    }

    @Test
    fun `the im login is allowed the Company read and the account list, and still not the writes or reads it does not need`() {
        val companyId = "6c1b2c20-0000-4000-8000-000000000001"
        ServiceEndpointAllowList.isAllowed("im", "GET", "/api/companies/$companyId") shouldBe true
        ServiceEndpointAllowList.isAllowed("im", "GET", "/api/companies/$companyId/accounts") shouldBe true

        ServiceEndpointAllowList.isAllowed("im", "PUT", "/api/companies/$companyId") shouldBe false
        ServiceEndpointAllowList.isAllowed("im", "POST", "/api/companies/$companyId/accounts") shouldBe false
        ServiceEndpointAllowList.isAllowed("im", "GET", "/api/companies/$companyId/accounts/$companyId/postings") shouldBe false
        ServiceEndpointAllowList.isAllowed("im", "GET", "/api/companies/$companyId/reports/profit-and-loss") shouldBe false
        ServiceEndpointAllowList.isAllowed("im", "POST", "/api/journal-entries") shouldBe false
    }
}
