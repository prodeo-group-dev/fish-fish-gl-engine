package com.theprodeogroup.fish.infrastructure.web

import io.ktor.util.AttributeKey

/**
 * Whether a money-posting request must carry an `Idempotency-Key` (Femi, 2026-10-22: "mandatory is important for CBB and PAY";
 * GL Playbook GL-FR-IDM-4, GL-T-24). A retry after a timeout with no key posts twice, so the key is the only thing that makes a retry safe.
 *
 * Introduced log-first, like the settlement-account and bank-only rules ([CashBookPolicies]), so nothing breaks on deploy:
 *  - [alwaysRequiredFor] names service logins that must always send a key (default `payroll` and `cbb`, which are known to send one on
 *    every posting call); for them a missing key is `400 idempotency_key_required` at once.
 *  - [mode] applies to every other caller: [PolicyMode.LOG] only logs `WOULD REFUSE idempotency_key_required service=<name|person> <METHOD> <path>`
 *    and lets the request run; [PolicyMode.ENFORCE] refuses it. Flip to enforce only after the logs show every caller sends a key.
 *
 * Configured by `FISH_IDEMPOTENCY_KEY_MODE` (`enforce`, else log) and `FISH_IDEMPOTENCY_KEY_REQUIRED_SERVICES` (comma list; unset means `payroll,cbb`).
 * [onViolation] receives each logged line (tests read it); every line also goes to the application log.
 */
class IdempotencyKeyPolicy(
    val mode: PolicyMode = PolicyMode.LOG,
    val alwaysRequiredFor: Set<String> = emptySet(),
    internal val onViolation: ((String) -> Unit)? = null
) {
    /** Whether a request from [service] (the service login, or null for a person) must carry a key. */
    fun requires(service: String?): Boolean = mode == PolicyMode.ENFORCE || (service != null && service in alwaysRequiredFor)

    companion object {
        fun fromEnvironment(mode: String?, requiredServices: String?): IdempotencyKeyPolicy = IdempotencyKeyPolicy(
            mode = PolicyMode.fromEnvironment(mode),
            alwaysRequiredFor = (requiredServices ?: "payroll,cbb").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        )
    }
}

/** Where the module stashes the [IdempotencyKeyPolicy]; absent means no rule (every request without a key runs, as before). */
val IdempotencyKeyPolicyKey = AttributeKey<IdempotencyKeyPolicy>("IdempotencyKeyPolicy")
