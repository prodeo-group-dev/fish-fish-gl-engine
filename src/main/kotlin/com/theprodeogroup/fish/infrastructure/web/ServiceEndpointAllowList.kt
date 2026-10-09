package com.theprodeogroup.fish.infrastructure.web

/**
 * Whether GL refuses a service call that is off its credential's allow-list ([ENFORCE]), or answers it
 * and only logs `WOULD BLOCK` ([LOG]). [LOG] exists so a wrong list can be found in the logs for one
 * deploy cycle instead of as a production outage; [ENFORCE] is the default when
 * `FISH_SERVICE_ALLOWLIST_MODE` is unset or anything other than `log` (deny by default).
 */
enum class ServiceAllowListMode {
    ENFORCE, LOG;

    companion object {
        fun fromEnvironment(value: String?): ServiceAllowListMode =
            if (value?.trim()?.equals("log", ignoreCase = true) == true) LOG else ENFORCE
    }
}

/**
 * T15 / G3 (Femi's D5, 2026-10-09): service logins (SOP, POP, IM, HR) stay valid for all Tenants, but each
 * is limited to the GL endpoints it actually needs. Anything not listed here is refused to a service with
 * 403 `forbidden_endpoint` - including every route added in future, until someone lists it (the
 * route-inventory test fails the build for a declared route that is neither listed nor classified people-only).
 *
 * The lists are derived from what each service's GL client really calls today
 * (docs/T15_GL_G2_G3_Design.md section 2) and each service confirms its own. Entries are written exactly as
 * the routes are declared in `infrastructure/web` (without the `/api` prefix), so the inventory test can
 * compare them with the source.
 */
object ServiceEndpointAllowList {

    val allowed: Map<String, Set<String>> = mapOf(
        "sop" to setOf(
            "GET /companies/{companyId}/sales-posting-context",
            "GET /companies/{companyId}/sales-invoices",
            "POST /companies/{companyId}/customer-balances",
            "POST /sales/record-sale",
            "POST /sales/record-collection",
            "POST /sales/record-sales-return"
        ),
        "pop" to setOf(
            "GET /companies/{companyId}/purchase-posting-context",
            "POST /purchasing/record-obligation",
            "POST /purchasing/record-payment"
        ),
        "im" to setOf(
            "GET /companies/{companyId}/inventory-posting-context",
            "POST /inventory/record-receipt",
            "POST /inventory/record-issue"
        ),
        "hr" to setOf(
            "GET /companies/{companyId}/payroll-posting-context",
            "POST /journal-entries",
            "POST /leave-accruals",
            "POST /leave-accruals/{leaveAccrualId}/remeasure",
            "POST /leave-accruals/{leaveAccrualId}/utilize",
            "POST /payroll/record-pay-run"
        )
    )

    /** A request path as the router sees it for matching: the `/api` prefix removed. */
    private fun normalise(path: String): String = path.removePrefix("/api")

    /** `{name}` in a declared route matches exactly one path segment. */
    private val compiled: Map<String, List<Pair<String, Regex>>> = allowed.mapValues { (_, routes) ->
        routes.map { route ->
            val method = route.substringBefore(' ')
            val template = route.substringAfter(' ')
            method to Regex("^" + template.split('/').joinToString("/") { segment ->
                if (segment.startsWith("{") && segment.endsWith("}")) "[^/]+" else Regex.escape(segment)
            } + "$")
        }
    }

    /** True only when [service] is a known credential and [method] [path] is on its list. A null or unknown service is never allowed. */
    fun isAllowed(service: String?, method: String, path: String): Boolean {
        val routes = compiled[service] ?: return false
        val normalised = normalise(path)
        return routes.any { (allowedMethod, regex) -> allowedMethod == method && regex.matches(normalised) }
    }

    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /**
     * The log line for a call that is off the list. Service name, method and the path with every id replaced
     * by `{id}` - never an id, a token or a header value.
     */
    fun wouldBlockMessage(service: String?, method: String, path: String): String =
        "WOULD BLOCK service=${service ?: "unknown"} $method ${normalise(path).replace(uuid, "{id}")}"

    fun blockedMessage(service: String?, method: String, path: String): String =
        "BLOCKED service=${service ?: "unknown"} $method ${normalise(path).replace(uuid, "{id}")}"
}
