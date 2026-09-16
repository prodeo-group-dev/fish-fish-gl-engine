package com.theprodeogroup.fish.infrastructure.web

import io.ktor.server.auth.Principal

/**
 * The authenticated identity for one HTTP request
 * (docs/Tenancy_Administration_Extraction_DDD_Design.md) - a Ktor
 * `Principal` carrying the JWT's verified `email` claim, shared by every
 * JWT provider [installFishJwtAuth] registers: the human-facing one and
 * POP/SOP/IM/HR's own service-account callers. Membership resolution
 * for human callers happens downstream in `authorizeTenantForWrite`/
 * `ForAdmin`/`ForModule`/`ForRead` (`Auth.kt`), which call EA's own
 * `GET /me` with the request's forwarded bearer token, rather than being
 * resolved once at auth time from a local repository. Nothing in this
 * codebase read `caller.user.id` before this change (confirmed by direct
 * grep), so nothing is lost by dropping the full `User` object here.
 *
 * **[isServiceAccount] (2026-09-16, product decision recorded in
 * `docs/Service_Account_Identity_And_EA_Membership_Design_Note.md` §9.2,
 * scoped in `docs/Service_Account_Principal_Cutover_Scope.md` §4.1):**
 * true only for POP/SOP/IM/HR's own machine callers
 * (`FISH_JWT_SERVICE_AUTH_NAME`/`_IM`/`_HR`/`_POP`), never a human. GL's
 * own `authorizeTenant` (`Auth.kt`) permanently bypasses EA's Membership
 * check for these callers, extending to GL the same Option B already
 * shipped for IM's own inbound POP/SOP callers - a module-to-module call
 * is FiSH GLaaS plumbing serving one Tenant's own workflow, not a
 * business user acting inside a Tenant, so it was never EA's Membership
 * model's question to answer. Trust for these callers is already
 * established correctly by their own dedicated Cognito service-account
 * audience (`FISH_JWT_SERVICE_AUDIENCE_SOP`/`_IM`/`_HR`/`_POP`) - that's
 * the real authorization boundary for inter-module calls, not EA.
 */
data class AuthenticatedCaller(val email: String, val isServiceAccount: Boolean = false) : Principal
