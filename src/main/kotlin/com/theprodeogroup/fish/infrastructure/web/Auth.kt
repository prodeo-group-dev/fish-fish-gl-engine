package com.theprodeogroup.fish.infrastructure.web

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import com.auth0.jwt.interfaces.RSAKeyProvider
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import com.theprodeogroup.fish.domain.tenancy.CompanyRepository
import com.theprodeogroup.fish.domain.tenancy.MembershipRepository
import com.theprodeogroup.fish.domain.tenancy.MembershipStatus
import com.theprodeogroup.fish.domain.tenancy.Role
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

/** The single named JWT auth configuration this app registers - see [installFishJwtAuth]. */
const val FISH_JWT_AUTH_NAME = "fish-jwt"

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
 */
fun Routing.fishAuthenticated(build: Route.() -> Unit): Route =
    authenticate(FISH_JWT_AUTH_NAME, build = build)

/**
 * Resolves the calling [AuthenticatedCaller.memberships] entry matching
 * [tenantId] and requires a role that isn't [Role.READ_ONLY] - the
 * minimal, honest authorization policy for this first pass (docs/DDD_Design.md
 * Section 10.19): `Role` itself is documented as "a label enum only,
 * carrying no behavior; permission enforcement is explicitly an
 * API-layer concern" with no permissions matrix defined anywhere in the
 * spec. Rather than inventing a speculative fine-grained matrix nobody
 * has asked for, this applies the one distinction the `Role` enum
 * itself already documents unambiguously - [Role.READ_ONLY] means
 * read-only - and defers a richer matrix to whenever a real
 * requirement for one shows up.
 *
 * Responds and returns `null` on failure (401 - no `AuthenticatedCaller`
 * at all; 403 - authenticated but no Membership in this Tenant, or a
 * `READ_ONLY` one), matching the "respond inline, caller checks for
 * null" idiom every route in this package uses to keep route bodies
 * linear rather than nested.
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
    if (membership.role == Role.READ_ONLY) {
        respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "READ_ONLY Membership cannot perform this action"))
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
