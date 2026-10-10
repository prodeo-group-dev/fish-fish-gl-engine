package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.domain.common.JurisdictionRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /jurisdictions` - the read-only reference list of every ENABLED
 * jurisdiction in the registry ([JurisdictionRepository]), the platform's
 * single source of jurisdictions (2026-10-05, "Only one source should
 * provide this information"): the frontend's country dropdown, EA, HR
 * and Omniview read this rather than each keeping their own list that
 * could drift from what `POST /tenants/{tenantId}/companies` actually
 * accepts - that route validates against the same registry.
 *
 * Only enabled entries are returned, so there is no `enabled` field: the
 * list is exactly what onboarding may offer.
 *
 * Needs a signed-in caller (registered inside `fishAuthenticated`) but no
 * Tenant or Membership - onboarding reads this *before* any Tenant
 * exists, and `fishAuthenticated` only verifies the token, it doesn't
 * resolve membership (that's `authorizeTenantFor*`'s job).
 */
fun Route.jurisdictionRoutes(jurisdictionRepository: JurisdictionRepository) {
    get("/jurisdictions") {
        call.respond(
            HttpStatusCode.OK,
            ListJurisdictionsResponseDto(jurisdictionRepository.findAllEnabled().map { JurisdictionDto(it.code.code, it.name, it.currency?.currencyCode) })
        )
    }
}
