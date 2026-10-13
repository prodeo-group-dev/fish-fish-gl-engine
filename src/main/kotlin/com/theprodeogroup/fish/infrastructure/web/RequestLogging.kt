package com.theprodeogroup.fish.infrastructure.web

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.RoutingApplicationCall
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import org.slf4j.MDC

private val UUID_TEXT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val STARTED_AT = AttributeKey<Long>("requestStartedAtNanos")
private val ROUTE_TEMPLATE = AttributeKey<String>("requestRouteTemplate")
private val requestLog = LoggerFactory.getLogger("request")

/**
 * The matched route as a TEMPLATE, for example `/api/companies/{companyId}/cash-books/{accountId}`. This is the only
 * form of the path that this line logs; a request that matched no route is `unmatched`.
 */
internal fun routeTemplate(call: ApplicationCall): String {
    call.attributes.getOrNull(ROUTE_TEMPLATE)?.let { return it }
    val route = (call as? RoutingApplicationCall)?.route ?: return "unmatched"
    return templateOf(route)
}

/** `/api/companies/{companyId}/(method:GET)` becomes `/api/companies/{companyId}`: selector wrappers removed, placeholders kept. */
private fun templateOf(route: Route): String = route.toString().replace(Regex("""/\([^)]*\)"""), "").ifEmpty { "/" }

/** `2xx`, `3xx`, `4xx` or `5xx`, so a query can group by outcome without parsing the status. */
internal fun resultClass(status: Int): String = if (status in 100..599) "${status / 100}xx" else "unknown"

/**
 * One structured log line per request (the Fixed Assets test, 2026-10-13: GL's access line had no duration, so
 * server time could not be told from client time). Added alongside the existing access line, nothing removed.
 * Fields: `requestId`, `method`, `route` (the template, never the raw path), `status`, `resultClass`, `durationMs`,
 * and `companyId` when the path has one and it is a plain id. 2xx, 3xx and 4xx are INFO, 5xx is ERROR; `/health` is
 * skipped (the load balancer probes it constantly). **Never** logged: bodies, headers (including Authorization), the
 * query string, any path value other than the Company id, or any email. The fields reach the JSON log through
 * logback.xml's `includeMdcKeyName` list.
 */
fun Application.installRequestLine() {
    // Remember the matched route on the call when routing resolves it. A handler that throws is answered by the
    // error handlers with the ORIGINAL call, which is not a routing call, so without this the template would be
    // lost exactly for the 4xx and 5xx responses most worth seeing.
    environment.monitor.subscribe(Routing.RoutingCallStarted) { routingCall ->
        routingCall.attributes.put(ROUTE_TEMPLATE, templateOf(routingCall.route))
    }
    install(RequestLine)
}

private val RequestLine = createApplicationPlugin("RequestLine") {
    onCall { call -> call.attributes.put(STARTED_AT, System.nanoTime()) }
    on(ResponseSent) { call ->
        if (call.request.path() == "/health") return@on
        val status = call.response.status()?.value ?: 0
        val started = call.attributes.getOrNull(STARTED_AT)
        val durationMs = if (started == null) 0L else (System.nanoTime() - started) / 1_000_000
        val company = call.parameters["companyId"]?.takeIf { UUID_TEXT.matches(it) }
        val fields = buildMap {
            put("requestId", call.callId ?: "-")
            put("method", call.request.local.method.value)
            put("route", routeTemplate(call))
            put("status", status.toString())
            put("resultClass", resultClass(status))
            put("durationMs", durationMs.toString())
            if (company != null) put("companyId", company)
        }
        val closers = fields.map { (k, v) -> MDC.putCloseable(k, v) }
        try {
            if (status >= 500) requestLog.error("request") else requestLog.info("request")
        } finally {
            closers.forEach { it.close() }
        }
    }
}
