package com.theprodeogroup.fish.domain.tax

/**
 * Which kind of tax a [TaxRule] configures (docs/DDD_Design.md Section
 * 2.4/3.4; spec Section 7.12 names three examples: corporate income tax,
 * VAT/GST, payroll/PAYE).
 *
 * **Only [CORPORATE_INCOME_TAX] is actually computable so far** -
 * confirmed scope before building `ComputeTaxUseCase`/`TaxComputation`
 * (2026-08-20, docs/DDD_Design.md Section 10.10). The other two aren't
 * named here yet, deliberately, to avoid implying a working computation
 * that doesn't exist:
 * - **VAT/GST** needs a genuinely different, two-sided computation
 *   (output tax on sales minus input tax on purchases), which needs a
 *   way to tag which specific *transactions* are VAT-eligible - a real
 *   new modeling concern this codebase doesn't have yet (`applicable
 *   accounts/periods` per spec 7.12 isn't the same granularity as
 *   `applicable transactions`).
 * - **PAYROLL_PAYE** can't be computed at all with what this system
 *   currently tracks - `PayRun` (`domain.payroll`) is deliberately
 *   company-level total wages/salaries only, with no `Employee`
 *   reference at all (the confirmed HR/Payroll separation). PAYE needs
 *   per-employee/per-payslip figures against tax brackets, data this
 *   codebase never captures by design.
 *
 * Add a value here only alongside the modeling work its actual
 * computation shape needs - not preemptively.
 */
enum class TaxType {
    CORPORATE_INCOME_TAX
}
