package com.theprodeogroup.fish.infrastructure.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The Payroll service's GL allow-list is exactly what its GL gateway calls (least privilege; CM's Payroll playbook review,
 * 2026-10-22). Source of truth: PAYROLL `infrastructure/gl/ktor_gl_engine_gateway.kt` and `company_currencies.kt`. When the gateway
 * gains or loses a route, this list and `ServiceEndpointAllowList` change together, or a deploy would either fail closed (403
 * forbidden_endpoint on a route it needs) or hold a door it never uses.
 */
class ServiceEndpointAllowListPayrollTest {

    @Test
    fun `the payroll service may call exactly the GL routes its gateway calls`() {
        ServiceEndpointAllowList.allowed.getValue("payroll") shouldBe setOf(
            "GET /companies/{companyId}",                                 // company_currencies.kt: the currency check
            "GET /companies/{companyId}/payroll-posting-context",         // ktor_gl_engine_gateway.kt:40
            "POST /payroll/record-pay-run",                               // :74
            "POST /payroll/record-pay-run-journal",                       // the purpose-built pay-run journal door (Application.kt keeps it behind its interface)
            "POST /leave-accruals",                                       // :98
            "POST /leave-accruals/{leaveAccrualId}/utilize",              // :120
            "POST /leave-accruals/{leaveAccrualId}/remeasure",            // :147
            "POST /journal-entries"                                       // :178: advance approval, claim approval, advance recovery
        )
    }
}
