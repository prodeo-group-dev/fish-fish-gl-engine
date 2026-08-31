package com.theprodeogroup.fish.infrastructure.web

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import com.auth0.jwt.interfaces.RSAKeyProvider
import com.theprodeogroup.fish.domain.tenancy.AccessLevel
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.domain.tenancy.ManagedModule
import com.theprodeogroup.fish.domain.tenancy.MembershipRepository
import com.theprodeogroup.fish.domain.tenancy.MembershipStatus
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.domain.tenancy.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import java.net.URI
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/** The primary JWT auth configuration this app registers - see [installFishJwtAuth]. */
const val FISH_JWT_AUTH_NAME = "fish-jwt"

/**
 * The onboarding-only JWT auth configuration - see [installFishJwtAuth].
 * Registered separately from [FISH_JWT_AUTH_NAME] because it deliberately
 * skips that config's "must already have an active Membership" check,
 * which every route *except* onboarding correctly relies on.
 */
const val FISH_JWT_ONBOARDING_AUTH_NAME = "fish-jwt-onboarding"

/**
 * JWT authentication (docs/DDD_Design.md Section 10.19) - **verifies
 * tokens in-process**, against whatever [verifier] the caller supplies,
 * confirmed with the user as the chosen approach over delegating
 * verification to an API Gateway authorizer: this app owns its own auth
 * check regardless of what sits in front of it (a plain load balancer
 * today, API Gateway later), rather than baking in an assumption about
 * a specific AWS Gateway configuration.
 *
 * **[verifier] is injected, not built internally** - [buildJwksVerifier]
 * builds the real one (JWKS-backed, for an external IdP like AWS
 * Cognito or Auth0) for production use via `Application.main`; tests
 * supply a locally-constructed static-key verifier instead, so no test
 * needs a real network JWKS endpoint - the same "inject the thing that
 * varies between production and tests" shape already used throughout
 * this codebase's repository interfaces.
 *
 * `User` has no password/credential storage anywhere in this codebase
 * (deliberately - "authentication mechanics are not modeled here",
 * `User`'s own KDoc) - this app cannot issue its own JWTs from a login
 * form. It only ever *verifies* tokens issued by whatever external IdP
 * the deployment is configured against.
 *
 * **Identity resolution: JWT's `email` claim -> `User.email` -> `User`,
 * via the already-built `UserRepository.findByEmail()`** - the natural
 * join key, since `User.email` is explicitly documented as the login
 * identifier (Section 10.3). A JWT with no matching `User`, or naming a
 * `User` with zero `ACTIVE` `Membership`s anywhere, is treated as
 * unauthenticated (401) - user/Membership provisioning is
 * `OnboardTenantUseCase`'s job, not something that happens implicitly
 * on first API call.
 */
fun Application.installFishJwtAuth(
    verifier: JWTVerifier,
    userRepository: UserRepository,
    membershipRepository: MembershipRepository
) {
    install(Authentication) {
        jwt(FISH_JWT_AUTH_NAME) {
            this.verifier(verifier)
            validate { credential ->
                val email = credential.payload.getClaim("email").asString() ?: return@validate null
                val user = userRepository.findByEmail(email) ?: return@validate null
                val activeMemberships = membershipRepository.findAllByUser(user.id)
                    .filter { it.status == MembershipStatus.ACTIVE }
                if (activeMemberships.isEmpty()) return@validate null
                AuthenticatedCaller(user, activeMemberships)
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "Missing or invalid bearer token"))
            }
        }

        // Onboarding's chicken-and-egg problem: FISH_JWT_AUTH_NAME above
        // requires an existing User with at least one ACTIVE Membership,
        // but provisioning that User/Membership is exactly what
        // OnboardTenantUseCase does. This config verifies the token is
        // genuinely signed by the external IdP (same [verifier]) and
        // carries an `email` claim - proving *who the caller is* -
        // without requiring anything to already exist in FiSH's own
        // User/Membership tables. Identity comes entirely from the
        // verified token, never from the request body, so a caller
        // can't onboard a Tenant under an email they don't control.
        jwt(FISH_JWT_ONBOARDING_AUTH_NAME) {
            this.verifier(verifier)
            validate { credential ->
                val email = credential.payload.getClaim("email").asString() ?: return@validate null
                VerifiedIdentity(email)
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "Missing or invalid bearer token"))
            }
        }
    }
}

/**
 * Builds the production [JWTVerifier] - JWKS-backed, per-`kid` key
 * lookup against `FISH_JWT_JWKS_URL`, the standard shape for verifying
 * tokens issued by an external IdP (AWS Cognito, Auth0). Cached/rate-
 * limited per Auth0's own recommended `JwkProviderBuilder` defaults, so
 * a burst of requests doesn't hammer the IdP's JWKS endpoint.
 *
 * `FISH_JWT_ISSUER`/`FISH_JWT_AUDIENCE`/`FISH_JWT_JWKS_URL` are all
 * required with no default - same "no safe default for a
 * security-relevant value" reasoning `DatabaseConfig` already applies
 * to database credentials.
 */
fun buildJwksVerifier(): JWTVerifier {
    val issuer = System.getenv("FISH_JWT_ISSUER")
        ?: error("FISH_JWT_ISSUER environment variable is required - no default for a security-relevant value")
    val audience = System.getenv("FISH_JWT_AUDIENCE")
        ?: error("FISH_JWT_AUDIENCE environment variable is required - no default for a security-relevant value")
    val jwksUrl = System.getenv("FISH_JWT_JWKS_URL")
        ?: error("FISH_JWT_JWKS_URL environment variable is required - no default for a security-relevant value")

    val jwkProvider = JwkProviderBuilder(URI(jwksUrl).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    // com.auth0's JWTVerifier needs a fixed Algorithm at build time, but
    // the *key* behind an RS256 algorithm can be resolved per-`kid` via
    // an RSAKeyProvider backed by the JwkProvider above - the standard
    // "JWKS-backed RS256 verifier" construction, since an external IdP
    // may rotate signing keys and publish more than one active `kid`.
    val keyProvider = object : RSAKeyProvider {
        override fun getPublicKeyById(keyId: String?): RSAPublicKey =
            jwkProvider.get(keyId).publicKey as RSAPublicKey
        override fun getPrivateKey(): RSAPrivateKey? = null
        override fun getPrivateKeyId(): String? = null
    }
    return JWT.require(Algorithm.RSA256(keyProvider))
        .withIssuer(issuer)
        .withAudience(audience)
        .build()
}

/**
 * Registers every route under [build] behind [FISH_JWT_AUTH_NAME] JWT
 * auth - a thin wrapper over Ktor's own `authenticate(...)`, named for
 * discoverability from route files without every route file needing to
 * know the auth provider's literal name.
 *
 * Receiver is [Route], not [Routing], specifically so this can be nested
 * inside another `route(...) { }` block (e.g. the `/api` prefix in
 * [com.theprodeogroup.fish.infrastructure.web.fishModule]) and not just
 * called directly inside the top-level `routing { }` block - [Routing]
 * itself is a [Route], so every existing call site still resolves.
 */
fun Route.fishAuthenticated(build: Route.() -> Unit): Route =
    authenticate(FISH_JWT_AUTH_NAME, build = build)

/** [fishAuthenticated]'s counterpart for [FISH_JWT_ONBOARDING_AUTH_NAME] - see that constant's KDoc. */
fun Route.fishOnboarding(build: Route.() -> Unit): Route =
    authenticate(FISH_JWT_ONBOARDING_AUTH_NAME, build = build)

/**
 * Resolves the calling [AuthenticatedCaller.memberships] entry matching
 * [tenantId] and requires [AccessLevel.WRITE] or above (2026-08-29,
 * replacing the old `Role.READ_ONLY` check - see [AccessLevel]'s own
 * KDoc for why enforcement moved off `Role` entirely: `Role` is
 * documented as "a label enum only, carrying no behavior," and never
 * had a permissions matrix defined for it in the spec).
 *
 * Responds and returns `null` on failure (401 - no `AuthenticatedCaller`
 * at all; 403 - authenticated but no Membership in this Tenant, or one
 * below [AccessLevel.WRITE]), matching the "respond inline, caller
 * checks for null" idiom every route in this package uses to keep route
 * bodies linear rather than nested.
 */
suspend fun ApplicationCall.authorizeTenantForWrite(tenantId: TenantId): AuthenticatedCaller? {
    val caller = principal<AuthenticatedCaller>()
    if (caller == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "No authenticated caller"))
        return null
    }
    val membership = caller.memberships.firstOrNull { it.tenantId == tenantId }
    if (membership == null) {
        respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "No active Membership in the requested Tenant"))
        return null
    }
    if (!membership.accessLevel.atLeast(AccessLevel.WRITE)) {
        respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "This Membership's access level cannot perform this action"))
        return null
    }
    return caller
}

/**
 * [authorizeTenantForWrite]'s counterpart for a route that grants real
 * access to others - inviting a staff member ([InviteStaffMemberUseCase])
 * is a materially different kind of action than posting a financial
 * entry, and [AccessLevel.WRITE] (an ordinary Accountant's own level)
 * was never meant to imply "and can also decide who else gets in."
 * Only [AccessLevel.ADMIN] clears this floor - [Role.OWNER_ADMIN] gets
 * it by default (`Membership.defaultAccessLevelFor`), matching the
 * user's own explicit direction (2026-08-31) that only an admin-level
 * Membership may invite staff.
 */
suspend fun ApplicationCall.authorizeTenantForAdmin(tenantId: TenantId): AuthenticatedCaller? {
    val caller = principal<AuthenticatedCaller>()
    if (caller == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "No authenticated caller"))
        return null
    }
    val membership = caller.memberships.firstOrNull { it.tenantId == tenantId }
    if (membership == null) {
        respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "No active Membership in the requested Tenant"))
        return null
    }
    if (!membership.accessLevel.atLeast(AccessLevel.ADMIN)) {
        respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "Only an admin-level Membership can perform this action"))
        return null
    }
    return caller
}

/**
 * [authorizeTenantForWrite]'s counterpart for a route scoped to one
 * [ManagedModule] specifically (2026-08-31, Tax's own route - the
 * first route in this codebase to actually enforce
 * [com.theprodeogroup.fish.domain.tenancy.Membership.grantedModules],
 * not just [AccessLevel]). Existing GL routes (journal entries,
 * reports, etc.) predate this and were **not** retrofitted to check
 * module grants - they're all implicitly "the GL module," and
 * `grantedModules` has so far only gated WEB's own tab visibility for
 * them. This is a real, known gap, not an oversight: closing it for
 * every existing route is a separate piece of work.
 */
suspend fun ApplicationCall.authorizeTenantForModule(tenantId: TenantId, module: ManagedModule, minAccessLevel: AccessLevel): AuthenticatedCaller? {
    val caller = principal<AuthenticatedCaller>()
    if (caller == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "No authenticated caller"))
        return null
    }
    val membership = caller.memberships.firstOrNull { it.tenantId == tenantId }
    if (membership == null) {
        respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "No active Membership in the requested Tenant"))
        return null
    }
    if (!membership.accessLevel.atLeast(minAccessLevel)) {
        respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "This Membership's access level cannot perform this action"))
        return null
    }
    if (module !in membership.grantedModules) {
        respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "This Membership is not granted access to the $module module"))
        return null
    }
    return caller
}

/**
 * [authorizeTenantForWrite]'s counterpart for a route that only reads
 * data - same Membership-in-Tenant check, but the floor is
 * [AccessLevel.READ] rather than [AccessLevel.WRITE]. Before
 * [AccessLevel.NONE] existed (2026-08-29) every real Membership implied
 * at least read access, so this had no rejection of its own; now a
 * Membership can genuinely sit below read (e.g. a placeholder for a
 * named-but-not-yet-onboarded module delegate), so this needs its own
 * explicit floor rather than inheriting one for free. Added for the
 * money-velocity KPI route (2026-08-27) - this codebase's first
 * read-only, Company-scoped `GET` endpoint; every prior route was
 * either a write ([authorizeTenantForWrite]) or needed no Tenant scope
 * at all ([MeRoutes]).
 */
suspend fun ApplicationCall.authorizeTenantForRead(tenantId: TenantId): AuthenticatedCaller? {
    val caller = principal<AuthenticatedCaller>()
    if (caller == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "No authenticated caller"))
        return null
    }
    val membership = caller.memberships.firstOrNull { it.tenantId == tenantId }
    if (membership == null) {
        respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "No active Membership in the requested Tenant"))
        return null
    }
    if (!membership.accessLevel.atLeast(AccessLevel.READ)) {
        respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "This Membership's access level cannot perform this action"))
        return null
    }
    return caller
}

/**
 * Resolves which [TenantId] owns [companyId] - `Membership` is
 * Tenant-scoped only (Section 3.2, no per-Company role granularity
 * exists in the domain model), so authorizing a request that targets a
 * specific `Company`/`Period`/order aggregate means walking up to that
 * aggregate's owning Tenant first. Responds 404 and returns `null` if
 * [companyId] doesn't resolve to a real, persisted `Company` - a
 * `checkNotNull()`-style internal-invariant treatment doesn't apply
 * here, since `companyId` in this context is derived from a raw
 * caller-supplied identifier (a Period/PurchaseOrder looked up from the
 * request), not a reference already known to be valid.
 */
suspend fun ApplicationCall.resolveTenantForCompany(companyId: CompanyId, companyRepository: CompanyRepository): TenantId? {
    val company = companyRepository.findById(companyId)
    if (company == null) {
        respond(HttpStatusCode.NotFound, ErrorResponseDto("not_found", "Company not found"))
        return null
    }
    return company.tenantId
}

/**
 * Checks the caller-supplied `X-Tenant-Id` header against
 * [actualTenantId] (already resolved from the targeted resource's real
 * owner via [resolveTenantForCompany]) - the real multi-tenancy-leak
 * check Section 10.19 introduced, extracted here (Section 10.21) once
 * a third route file (`InventoryRoutes`, after `PayrollRoutes`) needed
 * the identical check - previously inlined separately in
 * `journalEntryRoutes`/`purchaseOrderRoutes` and duplicated once more
 * as a private helper in `PayrollRoutes`; this is the shared version
 * every route in `infrastructure.web` should call from here on.
 * Responds 400/403 and returns `false` on failure.
 */
suspend fun ApplicationCall.verifyClaimedTenant(actualTenantId: TenantId): Boolean {
    val claimedTenantIdRaw = request.header("X-Tenant-Id")
    if (claimedTenantIdRaw == null) {
        respond(HttpStatusCode.BadRequest, ErrorResponseDto("bad_request", "X-Tenant-Id header is required"))
        return false
    }
    val claimedTenantId = parseUuid(claimedTenantIdRaw) ?: return false
    if (claimedTenantId != actualTenantId.value) {
        respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "X-Tenant-Id does not own the requested resource"))
        return false
    }
    return true
}
