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
import com.theprodeogroup.fish.domain.tenancy.TenantId
import com.theprodeogroup.fish.infrastructure.ea.EaCallerLookupResult
import com.theprodeogroup.fish.infrastructure.ea.EaMembershipGateway
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTCredential
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.util.AttributeKey
import java.net.URI
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/**
 * Where [installFishJwtAuth] stashes the [EaMembershipGateway] so
 * `authorizeTenantForWrite`/`ForAdmin`/`ForModule`/`ForRead` can reach it
 * without every one of their ~146 call sites across the codebase taking
 * a new parameter - the same "shared dependency, resolved from
 * `Application.attributes` rather than threaded through every call site"
 * shape Ktor's own plugin system already uses internally.
 */
val EaMembershipGatewayKey = AttributeKey<EaMembershipGateway>("EaMembershipGateway")

/** The primary JWT auth configuration this app registers - see [installFishJwtAuth]. */
const val FISH_JWT_AUTH_NAME = "fish-jwt"

/** The service-account JWT auth configuration - see [installFishJwtAuth] and [buildJwksServiceVerifier]. */
const val FISH_JWT_SERVICE_AUTH_NAME = "fish-jwt-service"

/** IM's own service-account JWT auth configuration - see [installFishJwtAuth] and [buildJwksServiceVerifierForIm]. A separate provider, not a second audience squeezed into [FISH_JWT_SERVICE_AUTH_NAME]'s verifier - see [buildJwksVerifier]'s own KDoc for why that doesn't work. */
const val FISH_JWT_SERVICE_AUTH_NAME_IM = "fish-jwt-service-im"

/** HR/Payroll's own service-account JWT auth configuration (2026-09-02, "Scope and build HR's HTTP layer") - see [installFishJwtAuth] and [buildJwksServiceVerifierForHr]. Same reasoning as [FISH_JWT_SERVICE_AUTH_NAME_IM]: a separate provider per service caller, not a shared audience. */
const val FISH_JWT_SERVICE_AUTH_NAME_HR = "fish-jwt-service-hr"

/** POP's own service-account JWT auth configuration (docs/POP_GL_Service_Account_Closure_Plan.md) - see [installFishJwtAuth] and [buildJwksServiceVerifierForPop]. Same reasoning as [FISH_JWT_SERVICE_AUTH_NAME_IM]/[FISH_JWT_SERVICE_AUTH_NAME_HR]: a separate provider per service caller, not a shared audience. Closes the gap where POP_GL_ENGINE_BEARER_TOKEN was never actually wired to anything - `/purchasing/record-obligation`/`/purchasing/record-payment` previously had no way for POP to authenticate at all. */
const val FISH_JWT_SERVICE_AUTH_NAME_POP = "fish-jwt-service-pop"

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
 * **Identity resolution: JWT's `email` claim only** - see
 * [toAuthenticatedCaller]. Membership/access resolution happens
 * downstream via EA (`authorizeTenantForWrite`/`ForAdmin`/`ForModule`/
 * `ForRead`), not here.
 */
fun Application.installFishJwtAuth(
    verifier: JWTVerifier,
    // The human-facing path's counterpart to EA
    // (docs/Tenancy_Administration_Extraction_DDD_Design.md) - no
    // default, matching [verifier]'s own "no safe default for a
    // security-relevant value" reasoning. Every existing test fixture
    // needs to pass a fake (see the EA rewiring plan's own "Test
    // migration" step) rather than silently keep working against local
    // repos, which is exactly the point: this should be impossible to
    // forget. Now shared by every JWT provider (human and service-account
    // alike) - POP/SOP/IM/HR's own calls were migrated onto this same
    // path once EA's service-account verifier slots were wired
    // (`EA_JWT_SERVICE_AUDIENCE_SOP/IM/HR/POP`, `infra/terraform/ea.tf`).
    eaMembershipGateway: EaMembershipGateway,
    // Defaults to reusing [verifier] - every existing test call site
    // (fishModule, 18 route test files) passes only the primary
    // verifier, and registering the service provider against the same
    // verifier is harmless (it just accepts the same single audience
    // twice, under two names) rather than a hard requirement to update
    // every test. Production always passes a real, distinct one.
    serviceVerifier: JWTVerifier = verifier,
    // IM's own service-account verifier - same default-to-[verifier]
    // escape hatch as [serviceVerifier], added once IM needed its own
    // GL-Engine-calling service account (a distinct Cognito app client
    // from SOP's, since [buildJwksVerifier]'s own KDoc already
    // established that one verifier can't accept either-of-two
    // audiences - each service caller needs its own verifier/provider).
    imServiceVerifier: JWTVerifier = verifier,
    // HR/Payroll's own service-account verifier, same shape as
    // [imServiceVerifier] - HR gained a real HTTP layer and its own
    // outbound-calling Cognito identity 2026-09-02.
    hrServiceVerifier: JWTVerifier = verifier,
    // POP's own service-account verifier, same shape as [imServiceVerifier]/
    // [hrServiceVerifier] - closes docs/POP_GL_Service_Account_Closure_Plan.md.
    popServiceVerifier: JWTVerifier = verifier
) {
    attributes.put(EaMembershipGatewayKey, eaMembershipGateway)

    install(Authentication) {
        jwt(FISH_JWT_AUTH_NAME) {
            this.verifier(verifier)
            validate { credential -> credential.toAuthenticatedCaller() }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "Missing or invalid bearer token"))
            }
        }

        jwt(FISH_JWT_SERVICE_AUTH_NAME) {
            this.verifier(serviceVerifier)
            validate { credential -> credential.toAuthenticatedCaller() }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "Missing or invalid bearer token"))
            }
        }

        jwt(FISH_JWT_SERVICE_AUTH_NAME_IM) {
            this.verifier(imServiceVerifier)
            validate { credential -> credential.toAuthenticatedCaller() }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "Missing or invalid bearer token"))
            }
        }

        jwt(FISH_JWT_SERVICE_AUTH_NAME_HR) {
            this.verifier(hrServiceVerifier)
            validate { credential -> credential.toAuthenticatedCaller() }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "Missing or invalid bearer token"))
            }
        }

        jwt(FISH_JWT_SERVICE_AUTH_NAME_POP) {
            this.verifier(popServiceVerifier)
            validate { credential -> credential.toAuthenticatedCaller() }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "Missing or invalid bearer token"))
            }
        }
    }
}

/**
 * Shared by every JWT provider [installFishJwtAuth] registers (human
 * and, since the service-account migration, POP/SOP/IM/HR's own callers
 * too) - proves the token is genuinely signed and carries an `email`
 * claim, nothing more. This does **not** check that any `User`/
 * `Membership` exists anywhere in GL's own tables - that check moved to
 * `authorizeTenantForWrite`/`ForAdmin`/`ForModule`/`ForRead`, which
 * resolve membership via EA instead, at the point a request actually
 * needs it (EA now verifies every audience GL itself does -
 * `EA_JWT_SERVICE_AUDIENCE_SOP/IM/HR/POP`, `infra/terraform/ea.tf`). A
 * caller with a validly-signed token but no Membership anywhere passes
 * this layer and is rejected downstream with 403 rather than 401 here -
 * an accepted, deliberate change from the pre-EA behavior, not a
 * regression.
 */
private fun JWTCredential.toAuthenticatedCaller(): AuthenticatedCaller? {
    val email = payload.getClaim("email").asString() ?: return null
    return AuthenticatedCaller(email)
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
 *
 * **Only ever built with a single audience** - com.auth0's own
 * `withAudience(vararg)` was tried first for a second, service-account
 * audience (2026-09-01) and turned out to require the token's `aud`
 * claim contain *every* listed value, not *any* of them (confirmed via
 * a throwaway `IncorrectClaimException` test) - useless for "accept
 * either of two single-audience tokens." [buildJwksServiceVerifier]
 * builds the second verifier instead; [installFishJwtAuth] registers
 * both as separate Ktor auth providers and [fishAuthenticated] accepts
 * either, which is what Ktor's own multi-provider `authenticate(...)`
 * already exists to do - no custom OR-logic needed once framed this way.
 */
fun buildJwksVerifier(): JWTVerifier {
    val issuer = System.getenv("FISH_JWT_ISSUER")
        ?: error("FISH_JWT_ISSUER environment variable is required - no default for a security-relevant value")
    val audience = System.getenv("FISH_JWT_AUDIENCE")
        ?: error("FISH_JWT_AUDIENCE environment variable is required - no default for a security-relevant value")
    return buildJwksVerifierFor(issuer, audience)
}

/**
 * The service-account counterpart to [buildJwksVerifier] - a second,
 * single-audience verifier for `FISH_JWT_SERVICE_AUDIENCE` (SOP's
 * dedicated Cognito app client, `infra/terraform/sop_service_account.tf`),
 * not a second value squeezed into the same verifier (see
 * [buildJwksVerifier]'s own KDoc for why that doesn't work). Returns
 * `null` when unset - genuinely optional, unlike `FISH_JWT_AUDIENCE`;
 * [Application.productionModule] falls back to reusing the primary
 * verifier when this is `null`, so nothing breaks if it's ever unset.
 */
fun buildJwksServiceVerifier(): JWTVerifier? {
    val issuer = System.getenv("FISH_JWT_ISSUER")
        ?: error("FISH_JWT_ISSUER environment variable is required - no default for a security-relevant value")
    val serviceAudience = System.getenv("FISH_JWT_SERVICE_AUDIENCE")?.takeIf { it.isNotBlank() } ?: return null
    return buildJwksVerifierFor(issuer, serviceAudience)
}

/** [buildJwksServiceVerifier]'s counterpart for IM's own service account (`FISH_JWT_SERVICE_AUDIENCE_IM`, IM's dedicated Cognito app client, `infra/terraform/im_service_account.tf`). Returns `null` when unset, same reasoning. */
fun buildJwksServiceVerifierForIm(): JWTVerifier? {
    val issuer = System.getenv("FISH_JWT_ISSUER")
        ?: error("FISH_JWT_ISSUER environment variable is required - no default for a security-relevant value")
    val serviceAudience = System.getenv("FISH_JWT_SERVICE_AUDIENCE_IM")?.takeIf { it.isNotBlank() } ?: return null
    return buildJwksVerifierFor(issuer, serviceAudience)
}

/** [buildJwksServiceVerifier]'s counterpart for HR/Payroll's own service account (`FISH_JWT_SERVICE_AUDIENCE_HR`, HR's dedicated Cognito app client, `infra/terraform/hr_service_account.tf`). Returns `null` when unset, same reasoning. */
fun buildJwksServiceVerifierForHr(): JWTVerifier? {
    val issuer = System.getenv("FISH_JWT_ISSUER")
        ?: error("FISH_JWT_ISSUER environment variable is required - no default for a security-relevant value")
    val serviceAudience = System.getenv("FISH_JWT_SERVICE_AUDIENCE_HR")?.takeIf { it.isNotBlank() } ?: return null
    return buildJwksVerifierFor(issuer, serviceAudience)
}

/** [buildJwksServiceVerifier]'s counterpart for POP's own service account (`FISH_JWT_SERVICE_AUDIENCE_POP`, POP's dedicated Cognito app client, `infra/terraform/pop_gl_service_account.tf`). Returns `null` when unset, same reasoning - docs/POP_GL_Service_Account_Closure_Plan.md. */
fun buildJwksServiceVerifierForPop(): JWTVerifier? {
    val issuer = System.getenv("FISH_JWT_ISSUER")
        ?: error("FISH_JWT_ISSUER environment variable is required - no default for a security-relevant value")
    val serviceAudience = System.getenv("FISH_JWT_SERVICE_AUDIENCE_POP")?.takeIf { it.isNotBlank() } ?: return null
    return buildJwksVerifierFor(issuer, serviceAudience)
}

private fun buildJwksVerifierFor(issuer: String, audience: String): JWTVerifier {
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
 * auth (or [FISH_JWT_SERVICE_AUTH_NAME], the service-account
 * counterpart - Ktor's own `authenticate(vararg names)` already treats
 * multiple provider names as OR-alternatives, exactly the "accept
 * either of two single-audience tokens" behavior needed here) - a thin
 * wrapper over Ktor's own `authenticate(...)`, named for discoverability
 * from route files without every route file needing to know the auth
 * provider's literal name.
 *
 * Receiver is [Route], not [Routing], specifically so this can be nested
 * inside another `route(...) { }` block (e.g. the `/api` prefix in
 * [com.theprodeogroup.fish.infrastructure.web.fishModule]) and not just
 * called directly inside the top-level `routing { }` block - [Routing]
 * itself is a [Route], so every existing call site still resolves.
 */
fun Route.fishAuthenticated(build: Route.() -> Unit): Route =
    authenticate(FISH_JWT_AUTH_NAME, FISH_JWT_SERVICE_AUTH_NAME, FISH_JWT_SERVICE_AUTH_NAME_IM, FISH_JWT_SERVICE_AUTH_NAME_HR, FISH_JWT_SERVICE_AUTH_NAME_POP, build = build)

/**
 * The authorized identity a successful `authorizeTenantFor*` call
 * returns - deliberately just enough for the handful of call sites that
 * read anything off it afterward (`caller.email`/`caller.name`, e.g.
 * `InviteStaffMemberUseCase.Request.inviterName` in `TenantRoutes.kt`).
 * Populated from EA's response - every caller (human or POP/SOP/IM/HR's
 * own service accounts) now resolves through EA, so there's nothing left
 * for a call site to distinguish here.
 */
data class AuthorizedCaller(val email: String, val name: String)

/** Strips the `Bearer ` prefix off this request's `Authorization` header, or `null` if missing/malformed. */
private fun ApplicationCall.rawBearerToken(): String? =
    request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }

/**
 * The shared body of every `authorizeTenantFor*` function below - looks
 * up the calling token's membership/access via EA, then applies the same
 * [minAccessLevel]/[module] floor. Every JWT provider [installFishJwtAuth]
 * registers (human, and POP/SOP/IM/HR's own service accounts as of the
 * migration onto EA's `EA_JWT_SERVICE_AUDIENCE_SOP/IM/HR/POP`) resolves
 * to the same [AuthenticatedCaller] principal now - there is no longer a
 * separate local-repo path to branch on.
 *
 * **The EA-unreachable case responds 503, not 401/403** - a caller
 * shouldn't have to guess whether "the request failed" means "you lack
 * access" or "the dependency this now relies on is down"
 * (`docs/Tenancy_Administration_Extraction_DDD_Design.md` §3's open
 * latency/availability question, resolved here as fail-closed rather
 * than silently falling back to anything).
 */
private suspend fun ApplicationCall.authorizeTenant(
    tenantId: TenantId,
    minAccessLevel: AccessLevel,
    module: ManagedModule? = null
): AuthorizedCaller? {
    val caller = principal<AuthenticatedCaller>()
    if (caller == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "No authenticated caller"))
        return null
    }
    val token = rawBearerToken()
    if (token == null) {
        respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "No authenticated caller"))
        return null
    }

    val gateway = application.attributes[EaMembershipGatewayKey]
    return when (val result = gateway.lookupCaller(token)) {
        is EaCallerLookupResult.Unauthorized -> {
            respond(HttpStatusCode.Unauthorized, ErrorResponseDto("unauthorized", "No authenticated caller"))
            null
        }
        is EaCallerLookupResult.Failure -> {
            respond(HttpStatusCode.ServiceUnavailable, ErrorResponseDto("service_unavailable", "Could not reach the authorization service"))
            null
        }
        is EaCallerLookupResult.Success -> {
            val membership = result.memberships.firstOrNull { it.tenantId == tenantId }
            if (membership == null) {
                respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "No active Membership in the requested Tenant"))
                return null
            }
            if (!membership.accessLevel.atLeast(minAccessLevel)) {
                respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "This Membership's access level cannot perform this action"))
                return null
            }
            if (module != null && module !in membership.grantedModules) {
                respond(HttpStatusCode.Forbidden, ErrorResponseDto("forbidden", "This Membership is not granted access to the $module module"))
                return null
            }
            AuthorizedCaller(result.email, result.name)
        }
    }
}

/**
 * Requires [AccessLevel.WRITE] or above in [tenantId] (2026-08-29,
 * replacing the old `Role.READ_ONLY` check - see [AccessLevel]'s own
 * KDoc for why enforcement moved off `Role` entirely). Responds and
 * returns `null` on failure (401/403/503 - see [authorizeTenant]),
 * matching the "respond inline, caller checks for null" idiom every
 * route in this package uses to keep route bodies linear rather than
 * nested.
 */
suspend fun ApplicationCall.authorizeTenantForWrite(tenantId: TenantId): AuthorizedCaller? =
    authorizeTenant(tenantId, AccessLevel.WRITE)

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
suspend fun ApplicationCall.authorizeTenantForAdmin(tenantId: TenantId): AuthorizedCaller? =
    authorizeTenant(tenantId, AccessLevel.ADMIN)

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
suspend fun ApplicationCall.authorizeTenantForModule(tenantId: TenantId, module: ManagedModule, minAccessLevel: AccessLevel): AuthorizedCaller? =
    authorizeTenant(tenantId, minAccessLevel, module)

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
suspend fun ApplicationCall.authorizeTenantForRead(tenantId: TenantId): AuthorizedCaller? =
    authorizeTenant(tenantId, AccessLevel.READ)

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
