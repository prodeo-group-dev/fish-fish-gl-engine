package com.theprodeogroup.fish.domain.payroll

import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * Persistence contract for Payroll's GL-facing posting interface
 * (docs/DDD_Design.md Section 10.4). `LeaveAccrual` has no repository
 * here - it's a thin wrapper delegating to `Provision` (`domain.ledger`),
 * which isn't persisted yet either; out of this build's scope.
 */
interface PayRunRepository {
    fun save(payRun: PayRun)
    fun findById(id: PayRunId): PayRun?
    fun findAllByCompany(companyId: CompanyId): List<PayRun>
}
