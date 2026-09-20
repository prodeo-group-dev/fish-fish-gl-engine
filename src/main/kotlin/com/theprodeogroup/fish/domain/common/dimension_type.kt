package com.theprodeogroup.fish.domain.common

/**
 * Dimension types for multi-dimensional reporting and analysis
 * 
 * Allows transactions to be tagged with multiple dimensions for
 * detailed reporting (e.g., P&L by department, cost center analysis)
 * 
 * Example usage:
 * ```
 * val dimensions = mapOf(
 *     DimensionType.DEPARTMENT to departmentId,
 *     DimensionType.PROJECT to projectId
 * )
 * ```
 */
enum class DimensionType {
    /**
     * Cost center for cost allocation
     * Example: "IT Department", "Sales Region North"
     */
    COST_CENTER,
    
    /**
     * Organizational department
     * Example: "Engineering", "Marketing", "Finance"
     */
    DEPARTMENT,
    
    /**
     * Project or initiative
     * Example: "Website Redesign", "Product Launch 2025"
     */
    PROJECT,
    
    /**
     * Geographic location
     * Example: "London Office", "New York Store"
     */
    LOCATION,
    
    /**
     * Product line or category
     * Example: "Software Subscriptions", "Hardware Sales"
     */
    PRODUCT_LINE,
    
    /**
     * Customer identifier
     * Example: "Enterprise Customer A", "Retail Segment"
     */
    CUSTOMER,
    
    /**
     * Vendor or supplier
     * Example: "AWS", "Office Supplies Inc"
     */
    VENDOR,

    /**
     * Inventory item/SKU identifier - tagged on the Inventory Asset
     * line by `RecordInventoryReceiptUseCase`/`RecordInventoryIssueUseCase`
     * with a caller-supplied, non-authoritative item reference (Option
     * B, `docs/Ecosystem_Extraction_DDD_Design.md` Section 1.3: `IM/`
     * owns item identity, the GL Engine only tags it). Same "dimension
     * tagged on a specific control line to make Ledger-derived
     * reporting possible without retrofitting an aggregate" reasoning
     * as CUSTOMER/VENDOR.
     */
    ITEM,

    /**
     * Employee identifier - tagged on the Salary Advances control
     * account's JournalLines by the HR/Payroll system
     * (fish-hr-payroll, docs/HR_Payroll_DDD_Design.md Section 3.2),
     * same control-account + dimension-tagging pattern already
     * established for CUSTOMER/VENDOR (`AccountsReceivableAging`/
     * `AccountsPayableAging`). Value is the raw `EmployeeId` UUID
     * string, matching `creditorId.value.toString()`'s existing
     * convention - no `Employee` aggregate exists in this repo at all
     * (HR/Payroll owns that entirely, per Section 0's "no Employee
     * reference crosses into the GL Engine" contract for `PayRun`);
     * this tag is the one deliberate, narrow exception, the same way
     * `LeaveAccrual` is `EmployeeId`-scoped because the liability is
     * inherently per-employee - a Salary Advances receivable is too.
     * Example: a Salary Advance disbursement debits the Salary
     * Advances control account tagged `EMPLOYEE` to the advanced
     * Employee's UUID; recovery credits the same account/tag.
     */
    EMPLOYEE,

    /**
     * IAS 7 cash-flow activity classification, tagged on the cash/bank
     * side of a JournalLine (not the counter-account side). Value is a
     * `CashFlowActivity` enum name (`domain.ledger`) - kept in `common`
     * alongside CUSTOMER/VENDOR since both are dimensions tagged on
     * specific control/cash lines to make Ledger-derived reporting
     * possible without retrofitting every aggregate to store its own
     * transaction history.
     */
    CASH_FLOW_ACTIVITY,

    /**
     * VAT category the line was computed under (2026-09-19,
     * docs/IE/IE_VAT_MVP_Design.md) - tagged on the VAT Control Account
     * leg of a `RecordSaleUseCase`/`RecordVendorObligationUseCase`
     * posting. Value is a `domain.tax.VatCategory` enum name. Same
     * "tag the control-account line so reporting can derive a breakdown
     * from already-posted JournalEntry data" reasoning already used for
     * CUSTOMER/VENDOR (AR/AP aging) - `VatReturn` reads this to produce
     * a per-category audit breakdown, not just a net total.
     */
    VAT_CATEGORY,

    /**
     * Custom dimension 1 (client-defined)
     */
    CUSTOM_1,
    
    /**
     * Custom dimension 2 (client-defined)
     */
    CUSTOM_2,
    
    /**
     * Custom dimension 3 (client-defined)
     */
    CUSTOM_3
}
