package com.theprodeogroup.fish.domain.payroll

import java.util.UUID

/**
 * Identity of an employee, referenced by `LeaveAccrual` - no Employee
 * master-data aggregate exists here (docs/DDD_Design.md Section 2.7),
 * and per the confirmed HR/Payroll separation (2026-08-12), never will:
 * `Employee` master data belongs to the separate HR/Payroll system, not
 * this repo. This ID stays only because `LeaveAccrual`'s liability is
 * inherently per-employee; `PayRun` (the wages/salaries posting) has no
 * need for it at all - a pay run's financial effect is a company-level
 * total, not a per-employee breakdown.
 */
@JvmInline
value class EmployeeId(val value: UUID) {
    companion object {
        fun generate(): EmployeeId = EmployeeId(UUID.randomUUID())
    }
}

/**
 * Identity of a PayRun aggregate.
 */
@JvmInline
value class PayRunId(val value: UUID) {
    companion object {
        fun generate(): PayRunId = PayRunId(UUID.randomUUID())
    }
}

/**
 * Identity of a LeaveAccrual aggregate.
 */
@JvmInline
value class LeaveAccrualId(val value: UUID) {
    companion object {
        fun generate(): LeaveAccrualId = LeaveAccrualId(UUID.randomUUID())
    }
}
