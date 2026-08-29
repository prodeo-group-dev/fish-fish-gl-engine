package com.theprodeogroup.fish.domain.tenancy

/**
 * What a Membership can actually do - genuinely separate from [Role]
 * (2026-08-29). [Role]'s own KDoc already flagged this gap: it's "a
 * label enum only, carrying no behavior" quoted directly from the
 * spec's RBAC baseline, with "no permissions matrix... defined anywhere
 * in the spec" - permission *enforcement* was always meant to be a
 * separate concern, just never built until now. Before this, the only
 * enforcement anywhere was a single hardcoded check
 * (`Role.READ_ONLY` blocks writes); `ACCOUNTANT`/`APPROVER`/
 * `COMPLIANCE_ETHICS_REVIEW` carried no actual behavior at all.
 *
 * Declaration order is the escalation order - [atLeast] compares by
 * ordinal, so don't reorder these without checking every caller.
 * [NONE] sits below [READ] deliberately: a Membership can exist (a User
 * is genuinely linked to a Tenant) without yet having been granted any
 * access at all - e.g. a placeholder for someone named as a future
 * module delegate ([ModuleManagementPreference]) who hasn't actually
 * been onboarded into the Tenant's access model yet.
 */
enum class AccessLevel {
    NONE,
    READ,
    WRITE,
    APPROVE,
    ADMIN;

    fun atLeast(required: AccessLevel): Boolean = this.ordinal >= required.ordinal
}
