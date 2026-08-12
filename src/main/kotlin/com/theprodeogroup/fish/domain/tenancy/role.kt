package com.theprodeogroup.fish.domain.tenancy

/**
 * A Membership's role within a Tenant (docs/DDD_Design.md Section 2.2/3.2) -
 * quoted directly from the spec's own RBAC baseline (Section 7.8, "RBAC
 * at minimum: Owner/Admin, Accountant, Approver, Read-only, plus a
 * Compliance/Ethics-review role"), not invented. No permissions matrix
 * is defined anywhere in the spec - this is a label enum only, carrying
 * no behavior; permission *enforcement* is explicitly an API-layer
 * concern (spec 7.8/8.2), not built here.
 *
 * "Owner/Admin" is kept as one combined value, matching the spec's own
 * comma-separated list structure (five parallel items, not six).
 *
 * [COMPLIANCE_ETHICS_REVIEW] is described in the spec as being "for
 * Purse's board-review and grey-area escalation workflow" - Purse-
 * specific in origin, but kept in this shared enum rather than split
 * out the way Lending was (Section 2.3): unlike Lending, it carries no
 * Purse-specific business logic here, just an unused label for
 * non-Purse tenants - it doesn't compromise the GL Engine's genericness
 * the way embedding actual Lending rules into Ledger would.
 */
enum class Role {
    OWNER_ADMIN,
    ACCOUNTANT,
    APPROVER,
    READ_ONLY,
    COMPLIANCE_ETHICS_REVIEW
}
