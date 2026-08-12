package com.theprodeogroup.fish.domain.lending

import java.util.UUID

/**
 * Identity of a borrower, referenced by ArrearsCase - no Borrower
 * aggregate exists yet (docs/DDD_Design.md Section 2.9, the Party-Role
 * design proposal). Reference-only, same precedent as Payroll's
 * `EmployeeId` (Section 2.7).
 */
@JvmInline
value class BorrowerId(val value: UUID) {
    companion object {
        fun generate(): BorrowerId = BorrowerId(UUID.randomUUID())
    }
}

/**
 * Identity of an ArrearsCase aggregate.
 */
@JvmInline
value class ArrearsCaseId(val value: UUID) {
    companion object {
        fun generate(): ArrearsCaseId = ArrearsCaseId(UUID.randomUUID())
    }
}
