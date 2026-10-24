package com.theprodeogroup.fish.infrastructure.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The Cash and Bank Books service's GL allow-list is exactly what its GL gateway calls (least privilege; CBB Playbook CBB-FR-AU-8,
 * CBB-T-23). Source of truth: CBB `infrastructure/gl/ktor_gl_ledger_gateway.kt`. CBB does not call `GET .../open-period` (Undo's period
 * test uses the `periodOpen` flag on each posting). When the gateway gains or loses a route, this list and `ServiceEndpointAllowList`
 * change together. Since 2026-10-24 it also holds `POST /sales/record-collection` (Cash Sale SPUTO Option A).
 */
class ServiceEndpointAllowListCbbTest {

    @Test
    fun `the cbb service may call exactly the GL routes its gateway calls`() {
        ServiceEndpointAllowList.allowed.getValue("cbb") shouldBe setOf(
            "GET /companies/{companyId}/accounts",
            "GET /companies/{companyId}/accounts/{accountId}/postings",
            "GET /companies/{companyId}/accounts/{accountId}/balance",
            // The rate CBB reads to work out the VAT it sends on a receipt or payment (GL-T-28, GL-FR-TAX-8; CBB-FR-EN-8).
            "GET /companies/{companyId}/vat-categories",
            "GET /companies/{companyId}/cash-books/{accountId}/counter-accounts",
            "POST /companies/{companyId}/cash-books/{accountId}/receipts",
            "POST /companies/{companyId}/cash-books/{accountId}/payments",
            "POST /companies/{companyId}/cash-books/{accountId}/transfers",
            "POST /companies/{companyId}/cash-books/{accountId}/entries/{entryId}/undo",
            // Cash Sale SPUTO Option A (CM, 2026-10-24): a SOP cash sale's settlement is posted by CBB through the existing collection route,
            // with sourceReference {COLLECTION, invoice id, CBR number}.
            "POST /sales/record-collection"
        )
    }
}
