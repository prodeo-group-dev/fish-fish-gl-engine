package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.FakeIdempotencyKeyRepository
import com.theprodeogroup.fish.domain.tenancy.TenantId
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

/**
 * The `Idempotency-Key` is mandatory on money-posting routes (Femi, 2026-10-22: "mandatory is important for CBB and PAY"; GL-FR-IDM-4,
 * GL-T-24, GL-UC-14). Log-first for everyone, enforced at once for the service logins named in the policy (payroll, cbb), enforced
 * for all when the mode is `enforce`. A request with a key is never affected.
 */
class IdempotencyKeyPolicyTest {

    @Test
    fun `the policy requires a key for a named service always, and for everyone only in enforce mode`() {
        val logFirst = IdempotencyKeyPolicy(mode = PolicyMode.LOG, alwaysRequiredFor = setOf("payroll", "cbb"))
        logFirst.requires("payroll") shouldBe true
        logFirst.requires("cbb") shouldBe true
        logFirst.requires("sop") shouldBe false
        logFirst.requires(null) shouldBe false

        val enforceAll = IdempotencyKeyPolicy(mode = PolicyMode.ENFORCE)
        enforceAll.requires("sop") shouldBe true
        enforceAll.requires(null) shouldBe true
    }

    /** A tiny module whose one route posts through [respondIdempotently]; `callerService` is the service login the request pretends to come from. */
    private fun Application.module(policy: IdempotencyKeyPolicy?, callerService: String?, executed: MutableList<String>) {
        install(ContentNegotiation) { json() }
        if (policy != null) attributes.put(IdempotencyKeyPolicyKey, policy)
        install(Authentication) {
            register(object : AuthenticationProvider(object : Config("fake") {}) {
                override suspend fun onAuthenticate(context: AuthenticationContext) {
                    context.principal(AuthenticatedCaller("caller@example.com", isServiceAccount = callerService != null, service = callerService))
                }
            })
        }
        routing {
            authenticate("fake") {
                post("/post-money") {
                    call.respondIdempotently(FakeIdempotencyKeyRepository(), TenantId.generate(), "post-money", "{}") {
                        executed += "ran"
                        HttpStatusCode.OK to """{"ok":true}"""
                    }
                }
            }
        }
    }

    private suspend fun io.ktor.client.HttpClient.postMoney(key: String?) = post("/post-money") {
        if (key != null) header("Idempotency-Key", key)
    }

    @Test
    fun `given no policy, when a request has no key, then it runs as it always did`() = testApplication {
        val executed = mutableListOf<String>()
        application { module(null, "sop", executed) }

        createClient { install(ClientContentNegotiation) { json() } }.postMoney(null).status shouldBe HttpStatusCode.OK
        executed.size shouldBe 1
    }

    @Test
    fun `given log-first, when a caller outside the named services sends no key, then it runs and the would-refuse line is logged`() = testApplication {
        val executed = mutableListOf<String>()
        val logged = mutableListOf<String>()
        application { module(IdempotencyKeyPolicy(PolicyMode.LOG, setOf("payroll", "cbb")) { logged += it }, "sop", executed) }

        createClient { install(ClientContentNegotiation) { json() } }.postMoney(null).status shouldBe HttpStatusCode.OK

        executed.size shouldBe 1
        logged.size shouldBe 1
        logged.single().startsWith("WOULD REFUSE idempotency_key_required service=sop POST /post-money") shouldBe true
    }

    @Test
    fun `given a named service sends no key, when it posts, then it is 400 idempotency_key_required and nothing runs`() = testApplication {
        val executed = mutableListOf<String>()
        application { module(IdempotencyKeyPolicy(PolicyMode.LOG, setOf("payroll", "cbb")), "payroll", executed) }
        val client = createClient { install(ClientContentNegotiation) { json() } }

        val refused = client.postMoney(null)

        refused.status shouldBe HttpStatusCode.BadRequest
        refused.body<ErrorResponseDto>().error shouldBe "idempotency_key_required"
        executed.size shouldBe 0
        client.postMoney("run-1:approve").status shouldBe HttpStatusCode.OK
        executed.size shouldBe 1
    }

    @Test
    fun `given enforce mode, when a person sends no key, then it is refused, and with a key it runs`() = testApplication {
        val executed = mutableListOf<String>()
        application { module(IdempotencyKeyPolicy(PolicyMode.ENFORCE), null, executed) }
        val client = createClient { install(ClientContentNegotiation) { json() } }

        client.postMoney(null).status shouldBe HttpStatusCode.BadRequest
        client.postMoney("k-1").status shouldBe HttpStatusCode.OK
        executed.size shouldBe 1
    }
}
