package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.payroll.EmployeeId
import com.theprodeogroup.fish.domain.payroll.LeaveAccrual
import com.theprodeogroup.fish.domain.payroll.LeaveAccrualRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.util.Currency

/**
 * The other half of the fix HR/Payroll's application layer needs
 * alongside `RecordPayRunUseCase`. `LeaveAccrual`, unlike `PayRun`, is
 * genuinely stateful - its `balance` accumulates across many
 * `remeasure`/`utilize` calls over an employee's tenure - so it can't
 * be treated as ephemeral the way `RecordPayRunUseCase` treats `PayRun`.
 * It has to actually exist, persisted, before `RemeasureLeaveAccrualUseCase`/
 * `UtilizeLeaveAccrualUseCase` can look it up by ID - and no route
 * exists anywhere to create one, the same gap `RecordPayRunUseCase`
 * closes for `PayRun`.
 *
 * **Idempotent by [EmployeeId], not a bare `create`** - HR/Payroll has
 * no reason to track a `LeaveAccrualId` locally against `EmployeeId`
 * (`domain.staffcost.Employee` deliberately carries no such field -
 * `docs/HR_Payroll_DDD_Design.md` Section 0's "no `Employee` reference
 * crosses into the GL Engine" contract cuts both ways). Instead, this
 * use case is safe to call every time HR/Payroll needs an Employee's
 * `LeaveAccrualId` - already-existing or freshly-created, the caller
 * gets the right one back either way, and never risks fragmenting one
 * Employee's true balance across two separate `LeaveAccrual` records
 * from an accidental double-create.
 *
 * **No sealed `Result`, unlike every posting use case in this
 * codebase** - a deliberate, reasoned omission, not an oversight.
 * Nothing here posts a `JournalEntry` (creating an empty-balance
 * `LeaveAccrual` has no financial effect yet), and there is no failure
 * mode to model: `LeaveAccrual.create()`/`Provision.create()` validate
 * nothing that can fail for a well-typed `Currency`, and (matching
 * `RecordSaleUseCase`'s own precedent) company existence is the HTTP
 * route's concern via `resolveTenantForCompany`, not this use case's.
 * Inventing a one-branch `Result` type here would just be ceremony.
 */
class GetOrCreateLeaveAccrualUseCase(
    private val leaveAccrualRepository: LeaveAccrualRepository
) {
    data class Request(
        val companyId: CompanyId,
        val employeeId: EmployeeId,
        val currency: Currency
    )

    fun execute(request: Request): LeaveAccrual {
        val existing = leaveAccrualRepository.findAllByCompany(request.companyId)
            .firstOrNull { it.employeeId == request.employeeId }
        if (existing != null) {
            return existing
        }

        val created = LeaveAccrual.create(request.companyId, request.employeeId, request.currency)
        leaveAccrualRepository.save(created)
        return created
    }
}
