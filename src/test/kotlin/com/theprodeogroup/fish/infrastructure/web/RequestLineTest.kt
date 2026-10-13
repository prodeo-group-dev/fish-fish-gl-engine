package com.theprodeogroup.fish.infrastructure.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.UUID

/** The structured request line: duration and result class on every request, and nothing that could carry a secret. */
class RequestLineTest {

    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger("request") as Logger

    @BeforeEach
    fun attach() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun io.ktor.server.application.Application.probe() {
        install(CallId) { generate { UUID.randomUUID().toString() } }
        installRequestLine()
        routing {
            get("/health") { call.respondText("ok") }
            get("/api/companies/{companyId}/probe") { call.respondText("ok") }
            get("/api/people/{email}/probe") { call.respondText("ok") }
            get("/api/refused") { call.respond(HttpStatusCode.Forbidden) }
            get("/api/fails") { call.respond(HttpStatusCode.InternalServerError) }
            get("/api/slow") { kotlinx.coroutines.delay(120); call.respondText("ok") }
        }
    }

    private fun lines() = appender.list.toList()

    @Test
    fun `a successful request logs one INFO line with method, route template, status, 2xx, a duration and the Company id`() = testApplication {
        application { probe() }
        val company = UUID.randomUUID()

        client.get("/api/companies/$company/probe")

        val line = lines().single()
        line.level shouldBe Level.INFO
        line.mdcPropertyMap["method"] shouldBe "GET"
        line.mdcPropertyMap["route"] shouldBe "/api/companies/{companyId}/probe"
        line.mdcPropertyMap["status"] shouldBe "200"
        line.mdcPropertyMap["resultClass"] shouldBe "2xx"
        line.mdcPropertyMap["companyId"] shouldBe company.toString()
        (line.mdcPropertyMap["durationMs"]!!.toLong() >= 0) shouldBe true
        (line.mdcPropertyMap["requestId"].isNullOrBlank()) shouldBe false
    }

    @Test
    fun `a refusal is INFO with 4xx, and a server failure is ERROR with 5xx`() = testApplication {
        application { probe() }

        client.get("/api/refused")
        client.get("/api/fails")

        val (refused, failed) = lines()
        refused.level shouldBe Level.INFO
        refused.mdcPropertyMap["resultClass"] shouldBe "4xx"
        failed.level shouldBe Level.ERROR
        failed.mdcPropertyMap["resultClass"] shouldBe "5xx"
        failed.mdcPropertyMap["status"] shouldBe "500"
    }

    @Test
    fun `a request that matches no route is logged as unmatched, never as its raw path`() = testApplication {
        application { probe() }

        client.get("/api/nothing/here/abc")

        val line = lines().single()
        line.mdcPropertyMap["route"] shouldBe "unmatched"
        line.mdcPropertyMap["resultClass"] shouldBe "4xx"
    }

    @Test
    fun `health is skipped`() = testApplication {
        application { probe() }

        client.get("/health")

        lines() shouldBe emptyList()
    }

    @Test
    fun `nothing that could carry a secret reaches the line - no query string, no header, no email, no path value but the Company id`() = testApplication {
        application { probe() }

        client.get("/api/people/someone@example.com/probe?token=SECRET-QUERY") { header("Authorization", "Bearer SECRET-TOKEN") }

        val everything = lines().joinToString(" ") { it.formattedMessage + " " + it.mdcPropertyMap.values.joinToString(" ") }
        listOf("SECRET-QUERY", "SECRET-TOKEN", "someone@example.com", "token=").forEach { (everything.contains(it)) shouldBe false }
        lines().single().mdcPropertyMap.keys shouldBe setOf("requestId", "method", "route", "status", "resultClass", "durationMs")
    }

    @Test
    fun `the result class covers every status family`() {
        resultClass(200) shouldBe "2xx"
        resultClass(301) shouldBe "3xx"
        resultClass(404) shouldBe "4xx"
        resultClass(503) shouldBe "5xx"
        resultClass(0) shouldBe "unknown"
    }

    @Test
    fun `the duration is the real time the request took`() = testApplication {
        application { probe() }

        client.get("/api/slow")

        val ms = lines().single().mdcPropertyMap["durationMs"]!!.toLong()
        (ms >= 100) shouldBe true
        (ms < 5_000) shouldBe true
    }
}
