package com.theprodeogroup.fish.infrastructure.web

import com.theprodeogroup.fish.application.FakeEaMembershipGateway
import com.theprodeogroup.fish.application.FakeMembershipRepository
import com.theprodeogroup.fish.application.FakeUserRepository
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

/**
 * T15 / G0 (F-T15-1): a service provider whose audience is not configured must accept nothing.
 *
 * Until now an unset `FISH_JWT_SERVICE_AUDIENCE*` made that provider fall back to the HUMAN verifier. Today
 * that is harmless only because `fishAuthenticated` lists the human provider first and Ktor takes the first
 * provider that succeeds; it would silently become "a human token is a service account, skip the EA
 * membership check" if the order ever changed, or if a route authenticated with a service provider alone.
 * Each service provider is therefore probed on its own here.
 */
class ServiceProviderFailClosedTest {

    private val serviceProviders = listOf(
        FISH_JWT_SERVICE_AUTH_NAME, FISH_JWT_SERVICE_AUTH_NAME_IM, FISH_JWT_SERVICE_AUTH_NAME_HR, FISH_JWT_SERVICE_AUTH_NAME_POP
    )

    @Test
    fun `a service provider with no audience configured rejects a human token`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installFishJwtAuth(
                verifier = TestJwtSupport.verifier(),
                eaMembershipGateway = FakeEaMembershipGateway(FakeUserRepository(), FakeMembershipRepository())
                // no service verifiers: every service audience is "unset"
            )
            routing {
                for (provider in serviceProviders) {
                    authenticate(provider) {
                        get("/probe/$provider") {
                            call.respondText("service=" + call.principal<AuthenticatedCaller>()?.isServiceAccount)
                        }
                    }
                }
            }
        }

        for (provider in serviceProviders) {
            val response = client.get("/probe/$provider") {
                header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken("a-human@example.com")}")
            }
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    @Test
    fun `a human token still authenticates as a human on the combined provider list`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installFishJwtAuth(
                verifier = TestJwtSupport.verifier(),
                eaMembershipGateway = FakeEaMembershipGateway(FakeUserRepository(), FakeMembershipRepository())
            )
            routing {
                fishAuthenticated {
                    get("/probe") { call.respondText("service=" + call.principal<AuthenticatedCaller>()?.isServiceAccount) }
                }
            }
        }

        val response = client.get("/probe") {
            header(HttpHeaders.Authorization, "Bearer ${TestJwtSupport.signToken("a-human@example.com")}")
        }

        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe "service=false"
    }
}
