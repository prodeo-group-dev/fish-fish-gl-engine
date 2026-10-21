package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /companies/{companyId}` (2026-10-21, Femi's currency decision: a screen or a service never assumes a Company's currency;
 * it reads it). The Company's own facts - name, base currency, jurisdiction code, client type, fiscal year start - behind the
 * usual per-Company READ authorization. Read-only and additive. `baseCurrency` is the currency the ledger is kept in, which is its
 * jurisdiction's currency (SRS decision D3), so a payroll or pay-terms currency is validated against this.
 */
fun Route.companyRoutes(companyRepository: CompanyRepository) {
    get("/companies/{companyId}") {
        val companyUuid = call.parseUuid(call.parameters["companyId"] ?: "") ?: return@get
        val companyId = CompanyId(companyUuid)
        val tenantId = call.resolveTenantForCompany(companyId, companyRepository) ?: return@get
        if (!call.verifyClaimedTenant(tenantId)) return@get
        call.authorizeTenantForRead(tenantId, companyId) ?: return@get

        val company = companyRepository.findById(companyId) ?: return@get
        call.respond(
            CompanyResponseDto(
                id = company.id.value.toString(),
                name = company.name,
                baseCurrency = company.baseCurrency.currencyCode,
                jurisdiction = company.jurisdiction.code,
                clientType = company.clientType.name,
                fiscalYearStartMonth = company.fiscalYearStartMonth
            )
        )
    }
}
