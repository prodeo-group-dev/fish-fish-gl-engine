package com.theprodeogroup.fish.infrastructure.ea

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Regression coverage for the 2026-09-21 incident: EA's real `GET /me`
 * response gained a `userId` field, [EaMyProfileResponseDto] didn't
 * mirror it, and the default (strict) `Json` decoder turned that into an
 * unhandled `JsonConvertException` for every GL route that calls out to
 * EA for a membership check (`AddCompanyToTenantUseCase` among them) -
 * this is the second time an EA `GET /me` field GL never reads has taken
 * the whole call down (see [EaMyProfileResponseDto]'s own KDoc for the
 * first). `Application.kt`'s `eaHttpClient` now configures
 * `ignoreUnknownKeys = true` specifically so a future EA field addition
 * degrades gracefully instead of crashing company creation again.
 */
class KtorEaMembershipGatewayTest {

    private fun clientReturning(body: String): HttpClient {
        val engine = MockEngine { request ->
            respond(content = body, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    @Test
    fun `a response with fields EaMyProfileResponseDto doesn't declare is tolerated, not a crash`() {
        runBlocking {
            val client = clientReturning(
                """
                {
                  "email": "dev@theprodeogroup.com",
                  "name": "Dev",
                  "kycStatus": "PENDING",
                  "userId": "51657c7d-743b-4f32-9f00-000000000000",
                  "tenants": []
                }
                """.trimIndent()
            )
            val gateway = KtorEaMembershipGateway(client, "http://ea.test")

            val result = gateway.lookupCaller("test-token")

            val success = result.shouldBeInstanceOf<EaCallerLookupResult.Success>()
            success.email shouldBe "dev@theprodeogroup.com"
            success.memberships shouldBe emptyList()
        }
    }
}
