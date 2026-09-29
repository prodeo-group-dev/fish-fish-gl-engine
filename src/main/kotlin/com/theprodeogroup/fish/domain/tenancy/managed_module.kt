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
 *
 * **[EDUCATION_RUNTIME] added 2026-09-29** - EA added this value
 * 2026-09-20 (auto-granted by `RegisterCompanyUseCase` for a
 * School-industry Company, see EA's own `ManagedModule` KDoc), but GL's
 * copy of this enum was never updated to match. EA's `GET /me` sends it
 * in a School Company's `grantedModules` for any Membership with
 * intrinsic or explicit access, and GL's `EaCompanySummaryDto.toCompanyAccess()`
 * calls `ManagedModule.valueOf()` on every value EA sends - so this
 * missing case threw `IllegalArgumentException` on every authorized GL
 * request for that Company (money-velocity/expense-velocity/sales-to-expense-ratio
 * among them), the same shape of bug as the `userId`/`schoolId` DTO
 * drift found earlier the same day, but as a hard enum gap rather than
 * an optional field - there's no lenient-decoding workaround for this
 * one, the value has to actually exist. GL never grants or checks this
 * module itself (it's EA/Education-Runtime's own concern), it just
 * needs to exist so deserialization doesn't crash.
 */
enum class ManagedModule {
    GL,
    HR,
    SOP,
    POP,
    IM,
    TAX,
    EDUCATION_RUNTIME
}
