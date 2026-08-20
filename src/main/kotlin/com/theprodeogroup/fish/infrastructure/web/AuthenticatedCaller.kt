package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.domain.tenancy.Membership
import com.theprodeogroup.fish.domain.tenancy.User
import io.ktor.server.auth.Principal

/**
 * The authenticated identity for one HTTP request (docs/DDD_Design.md
 * Section 10.19) - a Ktor `Principal` carrying the resolved FiSH `User`
 * and every currently-`ACTIVE` `Membership` they hold, across every
 * Tenant. Resolved once per request by [installFishJwtAuth] from a
 * validated JWT's `email` claim, via `UserRepository.findByEmail()` -
 * the join key `User.email` was already built for exactly this purpose
 * (Section 10.3's own KDoc: "mirrors User.email's role as the login
 * identifier").
 *
 * Deliberately carries every Membership, not just one - a request
 * targets a specific Tenant (via the `X-Tenant-Id` header, see
 * `authorizeTenantForWrite`/`authorizeTenantForRead` in `Auth.kt`), and
 * a User may hold Memberships in more than one Tenant (an accountant
 * working across multiple Group entities, for instance) - resolving
 * down to "the one relevant Membership for this request" is a
 * per-request authorization concern, not something to bake into the
 * Principal itself.
 */
data class AuthenticatedCaller(
    val user: User,
    val memberships: List<Membership>
) : Principal
