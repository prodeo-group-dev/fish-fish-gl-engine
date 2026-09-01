package com.theprodeogroup.fish.domain.payroll

import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * Persistence contract for `LeaveAccrual` (docs/DDD_Design.md Section
 * 10.18) - built alongside `RemeasureLeaveAccrualUseCase`/
 * `UtilizeLeaveAccrualUseCase`, the first time `LeaveAccrual` has had a
 * repository at all. `Provision` (`domain.ledger`) has no repository of
 * its own - confirmed with the user before building: `LeaveAccrual`'s
 * embedded `Provision` is persisted inline as part of this table, since
 * no other aggregate anywhere in this codebase currently references a
 * standalone `Provision`.
 */
interface LeaveAccrualRepository {
    fun save(leaveAccrual: LeaveAccrual)
    fun findById(id: LeaveAccrualId): LeaveAccrual?
    fun findAllByCompany(companyId: CompanyId): List<LeaveAccrual>
}
