package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.domain.tenancy.Membership
import com.theprodeogroup.fish.domain.tenancy.User
import io.ktor.server.auth.Principal

/**
 * The authenticated *human* identity for one HTTP request
 * (docs/Tenancy_Administration_Extraction_DDD_Design.md) - a Ktor
 * `Principal` carrying only the JWT's verified `email` claim. As of the
 * EA rewiring, this is deliberately all this type carries: membership
 * resolution moved downstream to `authorizeTenantForWrite`/`ForAdmin`/
 * `ForModule`/`ForRead` (`Auth.kt`), which call EA's own `GET /me` with
 * this request's forwarded bearer token, rather than being resolved
 * once at auth time from a local repository. Nothing in this codebase
 * read `caller.user.id` before this change (confirmed by direct grep),
 * so nothing is lost by dropping the full `User` object here.
 *
 * See [ServiceAccountCaller] for POP/SOP/IM/HR's own service-account
 * callers, which still resolve the old way (local `Membership` lookup)
 * this pass - only the human-facing path calls out to EA so far.
 */
data class AuthenticatedCaller(val email: String) : Principal

/**
 * The authenticated identity for a POP/SOP/IM/HR service-account
 * request - carries the resolved FiSH `User` and every currently-`ACTIVE`
 * `Membership` it holds, exactly as [AuthenticatedCaller] did for every
 * caller before the EA rewiring. Kept on the old local-repository path
 * deliberately: these callers authenticate with a Cognito audience EA
 * doesn't yet verify (`docs/Tenancy_Administration_Extraction_DDD_Design.md`'s
 * still-open service-account migration) - see the EA rewiring plan's
 * own "Deferred" section.
 */
data class ServiceAccountCaller(
    val user: User,
    val memberships: List<Membership>
) : Principal

/**
 * The onboarding-only counterpart to [AuthenticatedCaller] - a [Principal]
 * carrying nothing but a verified JWT's `email` claim, resolved by
 * [installFishJwtAuth]'s [FISH_JWT_ONBOARDING_AUTH_NAME] config. No `User`
 * exists to resolve to yet; that's exactly what onboarding creates.
 */
data class VerifiedIdentity(val email: String) : Principal
