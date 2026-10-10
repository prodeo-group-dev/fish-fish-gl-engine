package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.AddCompanyToTenantUseCase
import com.theprodeogroup.fish.domain.common.ClientType
import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.JurisdictionRepository
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.Currency

/**
 * `POST /tenants/{tenantId}/companies` (docs/DDD_Design.md Section 9.1) -
 * the lighter onboarding path, adding a legal entity under a Tenant that
 * already exists (in EA, not GL - see [AddCompanyToTenantUseCase]'s own
 * KDoc). `Company` is the one Tenancy aggregate that stays in GL
 * (load-bearing for Ledger scoping), so this is the one route from the
 * former Tenant/User/Membership onboarding surface that's still live -
 * onboarding itself (`POST /tenants`), staff invite/list
 * (`/tenants/{tenantId}/memberships`), and admin-phone all moved to EA
 * 2026-09-05 (`docs/Tenancy_Administration_Extraction_DDD_Design.md`'s
 * WEB->EA+GL handoff).
 */
fun Route.tenantRoutesAuthenticated(addCompanyToTenantUseCase: AddCompanyToTenantUseCase, jurisdictionRepository: JurisdictionRepository) {
    post("/tenants/{tenantId}/companies") {
        val tenantIdRaw = call.parameters["tenantId"]
        if (tenantIdRaw == null) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "tenantId path parameter is required"))
            return@post
        }
        val tenantUuid = call.parseUuid(tenantIdRaw) ?: return@post
        val tenantId = TenantId(tenantUuid)

        // No companyId exists yet - this route creates one - so the
        // per-Company authorizeTenantForWrite doesn't apply here (2026-09-23,
        // Per_Company_RBAC_Design.md). Matches EA's own equivalent
        // Owner-Admin-only gate on its analogous company-registration route.
        call.authorizeTenantOwnerAdmin(tenantId) ?: return@post

        val request = call.receive<AddCompanyToTenantRequestDto>()
        val clientType = try {
            ClientType.valueOf(request.clientType)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'${request.clientType}' is not a valid clientType"))
            return@post
        }
        // Malformed code and unapproved/disabled code get the identical
        // answer - the registry (`GET /api/jurisdictions`) is the only
        // source of what's accepted here, so nothing can be accepted
        // that wasn't offered.
        val jurisdiction = try {
            Jurisdiction(request.jurisdiction)
        } catch (e: IllegalArgumentException) {
            null
        }?.takeIf { jurisdictionRepository.findEnabledByCode(it) != null }
        if (jurisdiction == null) {
            val offered = jurisdictionRepository.findAllEnabled().joinToString { it.code.code }
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponseDto("bad_request", "'${request.jurisdiction}' is not a supported jurisdiction ($offered)")
            )
            return@post
        }
        // The Company's currency is its jurisdiction's (Femi 2026-10-10, SRS decision D3): seeded on creation
        // from the registry, not chosen. A currency in the request is still accepted but must match; a
        // jurisdiction added with no currency on record needs the creator to name one.
        val jurisdictionCurrency = jurisdictionRepository.findEnabledByCode(jurisdiction)?.currency
        val requestedCurrency = request.companyBaseCurrency?.let {
            try {
                Currency.getInstance(it)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'$it' is not a valid ISO currency code"))
                return@post
            }
        }
        val companyBaseCurrency = when {
            jurisdictionCurrency == null && requestedCurrency == null -> {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "companyBaseCurrency is required: this jurisdiction has no currency on record"))
                return@post
            }
            jurisdictionCurrency != null && requestedCurrency != null && requestedCurrency != jurisdictionCurrency -> {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponseDto(
                        "currency_not_supported_for_jurisdiction",
                        "A Company in ${jurisdiction.code} is created in ${jurisdictionCurrency.currencyCode}, not ${requestedCurrency.currencyCode}"
                    )
                )
                return@post
            }
            else -> jurisdictionCurrency ?: requestedCurrency!!
        }
        val openingCashBalance = request.openingCashBalance?.let {
            it.toBigDecimalOrNull() ?: run {
                call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "'$it' is not a valid openingCashBalance"))
                return@post
            }
        }
        if (request.fiscalYearStartMonth !in 1..12) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "fiscalYearStartMonth must be between 1 (January) and 12 (December)"))
            return@post
        }

        val result = addCompanyToTenantUseCase.execute(
            AddCompanyToTenantUseCase.Request(
                tenantId = tenantId,
                companyName = request.companyName,
                clientType = clientType,
                jurisdiction = jurisdiction,
                companyBaseCurrency = companyBaseCurrency,
                fiscalYearStartMonth = request.fiscalYearStartMonth,
                openingCashBalance = openingCashBalance
            )
        )

        call.respond(
            HttpStatusCode.Created,
            AddCompanyToTenantResponseDto(
                tenantId = tenantId.value.toString(),
                companyId = result.company.id.value.toString(),
                openingBalanceJournalEntryId = result.openingBalanceEntry?.id?.value?.toString()
            )
        )
    }
}
