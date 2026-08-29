package com.theprodeogroup.fish.domain.tenancy

/**
 * "Will you manage this yourself, or will someone else?" - captured per
 * [ManagedModule] during Company setup (2026-08-29), before the owner
 * ever reaches the dashboard. **Intent capture only, not access
 * control**: [Role]/[Membership] stay exactly as they are - a single
 * Role per person for the whole Tenant, not scoped per module - and
 * HR/SOP/POP/IM have no accounts or UI of their own to grant access to
 * yet. Choosing "someone else" doesn't send an invite or create a User;
 * it just records who the owner *intends* to hand a module to, so that
 * information exists once those systems (or a real per-module RBAC
 * model here) are ready to act on it, rather than being lost at
 * onboarding time and re-asked for later.
 *
 * [delegateName]/[delegateEmail] are required together when
 * [selfManaged] is false, and both null when it's true - enforced by
 * [of], not by two independent nullable fields callers could
 * inconsistently populate.
 */
class ModuleManagementPreference private constructor(
    val module: ManagedModule,
    val selfManaged: Boolean,
    val delegateName: String?,
    val delegateEmail: String?
) {
    companion object {
        fun selfManaged(module: ManagedModule): ModuleManagementPreference =
            ModuleManagementPreference(module, selfManaged = true, delegateName = null, delegateEmail = null)

        fun delegatedTo(module: ManagedModule, delegateName: String, delegateEmail: String): ModuleManagementPreference {
            require(delegateName.isNotBlank()) { "delegateName cannot be blank when a module is delegated" }
            require(delegateEmail.isNotBlank()) { "delegateEmail cannot be blank when a module is delegated" }
            return ModuleManagementPreference(module, selfManaged = false, delegateName, delegateEmail)
        }
    }
}
