package com.theprodeogroup.fish.domain.tenancy

/**
 * The operational areas a Company owner is asked, during onboarding
 * (2026-08-29), whether they'll run themselves or delegate - GL (this
 * repo's own ledger/bookkeeping), and the four "ecosystem" systems
 * that post into it: HR/Payroll, Sales Order Processing (SOP), Purchase
 * Order Processing (POP), and Inventory Management (IM). Matches this
 * project's five-system split (top-level CLAUDE.md) - HR/SOP/POP/IM
 * are separate repos with no UI/accounts of their own yet, so this is
 * intent capture only (see [ModuleManagementPreference]'s own KDoc for
 * exactly what that does and doesn't mean).
 *
 * Also doubles as [Membership.grantedModules]' vocabulary (2026-08-31) -
 * which modules a staff member can actually see/use, separate from
 * [ModuleManagementPreference]'s own onboarding-intent-only role.
 *
 * **[TAX] (2026-08-31)** - the user's own direction: "Tax management
 * should be its own module," given its own tab rather than living
 * silently inside GL's. Unlike HR/SOP/POP/IM, this isn't a separate
 * repo - `ComputeTaxUseCase`/`TaxRule`/`TaxComputation` already live in
 * GL's own domain layer and stay there; this only gives Tax its own
 * product-facing identity (a dashboard tab, its own access grant),
 * mirroring how IM's tab is real and functional today while still
 * being GL-hosted underneath.
 */
enum class ManagedModule {
    GL,
    HR,
    SOP,
    POP,
    IM,
    TAX
}
