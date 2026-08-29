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
 */
enum class ManagedModule {
    GL,
    HR,
    SOP,
    POP,
    IM
}
