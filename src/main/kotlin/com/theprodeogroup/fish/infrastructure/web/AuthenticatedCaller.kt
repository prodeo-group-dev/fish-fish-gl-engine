package com.theprodeogroup.fish.infrastructure.web

import io.ktor.server.auth.Principal

/**
 * The authenticated identity for one HTTP request
 * (docs/Tenancy_Administration_Extraction_DDD_Design.md) - a Ktor
 * `Principal` carrying only the JWT's verified `email` claim, shared by
 * every JWT provider [installFishJwtAuth] registers: the human-facing
 * one and, since the service-account migration onto EA's
 * `EA_JWT_SERVICE_AUDIENCE_SOP/IM/HR/POP`, POP/SOP/IM/HR's own callers
 * too. Membership resolution happens downstream in
 * `authorizeTenantForWrite`/`ForAdmin`/`ForModule`/`ForRead` (`Auth.kt`),
 * which call EA's own `GET /me` with the request's forwarded bearer
 * token, rather than being resolved once at auth time from a local
 * repository. Nothing in this codebase read `caller.user.id` before this
 * change (confirmed by direct grep), so nothing is lost by dropping the
 * full `User` object here.
 */
data class AuthenticatedCaller(val email: String) : Principal
