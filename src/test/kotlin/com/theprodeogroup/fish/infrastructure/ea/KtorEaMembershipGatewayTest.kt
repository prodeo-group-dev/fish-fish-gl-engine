package com.theprodeogroup.fish.infrastructure.ea

import com.theprodeogroup.fish.domain.tenancy.ManagedModule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.JsonConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Coverage for EA's `GET /me` deserialization policy, reverted to strict
 * 2026-09-29 (direct user instruction: "This is a Multi-Tenanted
 * Cloud-Native Project. Security is paramount and sacrosanct. There
 * should not be a case where there exists unknown keys being
 * processed.") - previously `ignoreUnknownKeys = true` (2026-09-21) after
 * EA's response shape drifted out from under [EaMyProfileResponseDto]'s
 * mirror twice (`kycStatus`, then `userId`) and hard-crashed every GL
 * route calling out to EA for a membership check. That leniency is now
 * judged the wrong trade-off for an authentication/authorization
 * payload specifically - a future EA field could be security-relevant
 * (a revoked/suspended flag, a tightened permission), and GL would
 * silently never see it while still proceeding as authorized. This test
 * uses `Application.kt`'s own real (strict, default) `Json` config, not
 * a locally relaxed one, so it actually proves production behavior.
 */
class KtorEaMembershipGatewayTest {

    private fun clientReturning(body: String): HttpClient {
        val engine = MockEngine { request ->
            respond(content = body, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return HttpClient(engine) { install(ContentNegotiation) { json(Json) } }
    }

    @Test
    fun `a fully-declared response deserializes correctly`() {
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

    @Test
    fun `a response with a field EaMyProfileResponseDto doesn't declare throws, not silently tolerated`() {
        runBlocking {
            val client = clientReturning(
                """
                {
                  "email": "dev@theprodeogroup.com",
                  "name": "Dev",
                  "kycStatus": "PENDING",
                  "userId": "51657c7d-743b-4f32-9f00-000000000000",
                  "somethingSecurityRelevantGlDoesntKnowAboutYet": true,
                  "tenants": []
                }
                """.trimIndent()
            )
            val gateway = KtorEaMembershipGateway(client, "http://ea.test")

            shouldThrow<JsonConvertException> {
                gateway.lookupCaller("test-token")
            }
        }
    }

    @Test
    fun `EDUCATION_RUNTIME in a Company's grantedModules maps correctly, not an enum-gap crash`() {
        runBlocking {
            val client = clientReturning(
                """
                {
                  "email": "owner@school.test",
                  "name": "School Owner",
                  "kycStatus": "VERIFIED",
                  "userId": "51657c7d-743b-4f32-9f00-000000000000",
                  "tenants": [
                    {
                      "tenantId": "9fa2198b-2a6f-467d-97ac-6f6fbce6a9fd",
                      "tenantName": "Test Tenant",
                      "isOwnerAdmin": true,
                      "tenantStatus": "ACTIVE",
                      "kybStatus": "VERIFIED",
                      "adminPhoneNumber": null,
                      "adminPhoneVerificationStatus": "VERIFIED",
                      "phoneVerificationDeadline": null,
                      "companies": [
                        {
                          "id": "ea2846a5-76f7-4f1f-9a5a-15baff987290",
                          "name": "Test School",
                          "schoolId": "some-school-id",
                          "role": null,
                          "accessLevel": null,
                          "grantedModules": ["EDUCATION_RUNTIME"]
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            )
            val gateway = KtorEaMembershipGateway(client, "http://ea.test")

            val result = gateway.lookupCaller("test-token")

            val success = result.shouldBeInstanceOf<EaCallerLookupResult.Success>()
            val company = success.memberships.single().companies.single()
            company.grantedModules shouldBe setOf(ManagedModule.EDUCATION_RUNTIME)
        }
    }
}
