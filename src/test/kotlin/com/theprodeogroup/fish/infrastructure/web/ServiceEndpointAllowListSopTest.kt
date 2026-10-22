package com.theprodeogroup.fish.infrastructure.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * SOP's GL allow-list: its posting and read routes, plus the account-balance read (2026-10-22, GL-T-12a, GL-FR-RPT-5, GL-UC-8) that
 * lets SOP compare its open receivables total with the 1100 control balance as at a date. Read-only; SOP's side (FR-SOP-AR-20) is
 * held, so nothing calls it yet. The answer states its currency, so SOP can refuse to compare across currencies.
 */
class ServiceEndpointAllowListSopTest {

    @Test
    fun `the sop service may call its posting and read routes and the account balance read`() {
        ServiceEndpointAllowList.allowed.getValue("sop") shouldBe setOf(
            "GET /companies/{companyId}",
            "GET /companies/{companyId}/sales-posting-context",
            "GET /companies/{companyId}/sales-invoices",
            "GET /companies/{companyId}/accounts/{accountId}/balance",
            "POST /companies/{companyId}/customer-balances",
            "POST /sales/record-sale",
            "POST /sales/record-collection",
            "POST /sales/record-sales-return"
        )
    }
}
