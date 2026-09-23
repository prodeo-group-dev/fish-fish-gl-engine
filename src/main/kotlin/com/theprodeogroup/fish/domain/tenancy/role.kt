package com.theprodeogroup.fish.domain.tenancy

/**
 * A Membership's role at a Company, mirrored from EA's own source-of-truth
 * enum (`EA/.../domain/tenancy/role.kt`) - EA owns `Membership`/`Role`
 * entirely as of the Tenancy Administration extraction, so this is a
 * deliberate duplicate of EA's wire vocabulary, not an independently
 * evolving concept (same reasoning [ManagedModule]'s own duplication
 * across repos already follows).
 *
 * **Redefined 2026-09-23** (`Per_Company_RBAC_Design.md`) from the
 * original spec's RBAC-baseline label enum (`OWNER_ADMIN`/`ACCOUNTANT`/
 * `APPROVER`/`READ_ONLY`/`COMPLIANCE_ETHICS_REVIEW`, Section 7.8) to six
 * real organisational functions - direct instruction: "These are not
 * roles. They are functions." `APPROVER`/`READ_ONLY` were redundant with
 * [AccessLevel.APPROVE]/[AccessLevel.READ], which already exist and
 * needed no change; `COMPLIANCE_ETHICS_REVIEW` was dropped outright, not
 * migrated to anything.
 *
 * Kept purely for GL's own `/me` pass-through display (`MeRoutes.kt`) -
 * GL's own authorization (`Auth.kt`) has never branched on `Role`, only
 * [AccessLevel]/[ManagedModule], and still doesn't.
 */
enum class Role {
    OWNER_ADMIN,
    ACCOUNTANT,
    SALES_OFFICER,
    PURCHASING_OFFICER,
    INVENTORY_MANAGER,
    HR_OFFICER
}
