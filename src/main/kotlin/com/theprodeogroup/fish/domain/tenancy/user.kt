package com.theprodeogroup.fish.domain.tenancy

/**
 * A person who can log in (docs/DDD_Design.md Section 3.2) - a global
 * identity, not scoped to any Tenant; access is granted per-Tenant via
 * [Membership]. The spec's own User definition (Section 10) is this
 * bare: "A person who can log in; may belong to one or more Tenants via
 * Membership" - no fields beyond identity are specified. [email] and
 * [name] are the minimal addition needed for a login identifier and a
 * display name; authentication mechanics (password, MFA, SSO) are
 * deliberately not modeled here, matching how every other aggregate in
 * this codebase keeps infrastructure/security concerns out of the
 * domain layer.
 *
 * Does **not** track its own `Set<MembershipId>` - which Memberships a
 * User holds is discoverable by querying Memberships referencing this
 * `userId`, not stored redundantly here. Same reasoning as Section 2.9's
 * "roles are derived, not stored on Party."
 */
class User private constructor(
    val id: UserId,
    val email: String,
    val name: String
) {
    companion object {
        fun create(
            email: String,
            name: String,
            id: UserId = UserId.generate()
        ): User {
            require(email.isNotBlank() && email.contains("@")) {
                "email must be a non-blank address containing '@'"
            }
            return User(id, email, name)
        }
    }
}
