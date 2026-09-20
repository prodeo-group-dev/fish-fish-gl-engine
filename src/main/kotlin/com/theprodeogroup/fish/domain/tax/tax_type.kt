package com.theprodeogroup.fish.domain.tax

/**
 * Which kind of tax a [TaxRule] configures (docs/DDD_Design.md Section
 * 2.4/3.4; spec Section 7.12 names three examples: corporate income tax,
 * VAT/GST, payroll/PAYE).
 *
 * **[CORPORATE_INCOME_TAX] and [VALUE_ADDED_TAX] are both computable now.**
 * CIT confirmed scope before building `ComputeTaxUseCase`/`TaxComputation`
 * (2026-08-20, docs/DDD_Design.md Section 10.10). VAT was added
 * 2026-09-19 (docs/IE/IE_VAT_MVP_Design.md, the RoI MVP threshold) once
 * the two gaps this enum's own prior KDoc flagged were actually closed:
 * a line-level rate lookup ([VatCategory]/[VatRateSchedule], a genuinely
 * different shape from [RateStructure]'s taxpayer-level CIT computation),
 * and per-transaction-line VAT tagging (`SalesOrderLine`/`PurchaseOrderLine`
 * gaining a `vatCategory` field, in `fish-sales-order-processing`/
 * `fish-purchase-order-processing`, not this repo). **[VALUE_ADDED_TAX] is
 * NOT computed via `TaxComputation.of()`** - that path is `ProfitAndLoss`-based
 * (a Period's net profit), the wrong shape for VAT (due on gross
 * transaction value, over an arbitrary filing-period date range, not tied
 * to a GL `Period`). See `VatReturn`/`ComputeVatReturnUseCase` for VAT's
 * own computation path.
 *
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
    CORPORATE_INCOME_TAX,
    VALUE_ADDED_TAX
}
