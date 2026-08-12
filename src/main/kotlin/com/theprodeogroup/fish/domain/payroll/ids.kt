package com.theprodeogroup.fish.domain.payroll

import java.util.UUID

/**
 * Identity of an employee, referenced by Payslip - no Employee master-data
 * aggregate exists yet (docs/DDD_Design.md Section 2.7, deliberately
 * deferred, matching how Increment 3 referenced StockItem without a live
 * PO/SO link).
 */
@JvmInline
value class EmployeeId(val value: UUID) {
    companion object {
        fun generate(): EmployeeId = EmployeeId(UUID.randomUUID())
    }
}

/**
 * Identity of a Payslip aggregate.
 */
@JvmInline
value class PayslipId(val value: UUID) {
    companion object {
        fun generate(): PayslipId = PayslipId(UUID.randomUUID())
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
