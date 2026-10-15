package com.theprodeogroup.fish.infrastructure.ea

import com.theprodeogroup.fish.domain.tenancy.AccessLevel
import com.theprodeogroup.fish.domain.tenancy.ManagedModule
import com.theprodeogroup.fish.domain.tenancy.Role
import com.theprodeogroup.fish.infrastructure.web.ErrorResponseDto
import contracts.ContractFixtures
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
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * Consumer side of the cross-service contract fixtures (docs/Cross_Service_Contract_Fixtures_SPUTO.md, T9): EA is the
 * SOURCE of `GET /me` and its refusal body, GL decodes them. The fixture is EA's real output from EA's own golden test;
 * here it is answered from a MockEngine through GL's REAL `KtorEaMembershipGateway` and the same strict, default
 * `Json` configuration `Application.kt` builds `eaHttpClient` with (no `ignoreUnknownKeys`). If EA adds a field GL's
 * mirror does not declare, this fails here, in CI, instead of as a 500 on every signed-in request (the 2026-09-29
 * incident). The fix is always to declare the missing field in `ea_dtos.kt` (consumer first), never to relax the decoder.
 */
class EaContractFixturesTest {

    private fun clientReturning(status: HttpStatusCode, body: String): HttpClient {
        val engine = MockEngine { respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json")) }
        return HttpClient(engine) { install(ContentNegotiation) { json(Json) } }
    }

    @Test
    fun `EA's me fixture decodes through the real strict gateway, and the values GL reads map`() { runBlocking {
        val gateway = KtorEaMembershipGateway(clientReturning(HttpStatusCode.OK, ContractFixtures.read("contracts/ea/me.json")), "http://ea.test")

        val success = gateway.lookupCaller("test-token").shouldBeInstanceOf<EaCallerLookupResult.Success>()

        success.email shouldBe "owner@example.test"
        success.name shouldBe "Olivia Owner"
        success.memberships.size shouldBe 2
        val springfield = success.memberships.first { it.tenantName == "Springfield Group" }
        springfield.isOwnerAdmin shouldBe true
        springfield.tenantStatus shouldBe "ACTIVE"
        springfield.phoneVerificationDeadline shouldBe null
        springfield.companies.size shouldBe 3
        val primary = springfield.companies.first { it.name == "Springfield Primary" }
        primary.role shouldBe Role.OWNER_ADMIN
        primary.accessLevel shouldBe AccessLevel.ADMIN
        primary.grantedModules shouldBe ManagedModule.entries.toSet()
        val newVenture = springfield.companies.first { it.name == "Springfield New Venture" }
        newVenture.role shouldBe null
        newVenture.accessLevel shouldBe null
        newVenture.grantedModules shouldBe emptySet()
        val brighton = success.memberships.first { it.tenantName == "Brighton Shop" }
        brighton.isOwnerAdmin shouldBe false
        brighton.phoneVerificationDeadline shouldBe "2026-10-31T09:00:00Z"
        brighton.companies.single().role shouldBe Role.ACCOUNTANT
        brighton.companies.single().accessLevel shouldBe AccessLevel.WRITE
    } }

    @Test
    fun `the fixture is also read by the bare strict DTO, so a field GL does not declare would fail the DTO itself`() {
        val decoded = Json.decodeFromString(EaMyProfileResponseDto.serializer(), ContractFixtures.read("contracts/ea/me.json"))

        decoded.tenants.size shouldBe 2
        decoded.userId shouldBe "00000000-0000-0000-0000-000000000001"
    }

    @Test
    fun `the strict decoder really is strict - the same fixture with one extra key is refused`() {
        val withExtra = ContractFixtures.read("contracts/ea/me.json").replaceFirst("{", """{ "aFieldGlDoesNotDeclare": true,""")

        shouldThrow<SerializationException> { Json.decodeFromString(EaMyProfileResponseDto.serializer(), withExtra) }
    }

    @Test
    fun `EA's refusal body answers as a refusal through the gateway, and decodes strictly with its two fields`() { runBlocking {
        val body = ContractFixtures.read("contracts/ea/error-body.json")
        val gateway = KtorEaMembershipGateway(clientReturning(HttpStatusCode.Forbidden, body), "http://ea.test")

        gateway.lookupCaller("test-token") shouldBe EaCallerLookupResult.Failure(HttpStatusCode.Forbidden.value)
        val decoded = Json.decodeFromString(ErrorResponseDto.serializer(), body)
        decoded.error shouldBe "forbidden"
        decoded.detail shouldBe "No active Membership in the requested Tenant"
    } }
}
