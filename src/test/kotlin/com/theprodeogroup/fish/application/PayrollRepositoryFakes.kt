package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.payroll.LeaveAccrual
import com.theprodeogroup.fish.domain.payroll.LeaveAccrualId
import com.theprodeogroup.fish.domain.payroll.LeaveAccrualRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * In-memory stand-ins for Payroll's repository interfaces
 * (docs/DDD_Design.md Section 10.4/10.18) - same discipline as
 * `EcosystemRepositoryFakes.kt`/`LedgerRepositoryFakes.kt`.
 *
 * `FakePayRunRepository` was removed 2026-09-01 alongside `PayRunRepository`
 * itself and `PostPayRunUseCase` - HR/Payroll only ever calls the thin
 * `RecordPayRunUseCase` interface (confirmed via `fish-hr-payroll`'s own
 * `KtorGlEngineGateway`), so the persisted-lookup-by-ID `PayRun` flow was
 * dead code, same pattern as `StockItem`'s retirement earlier that day.
 */
class FakeLeaveAccrualRepository : LeaveAccrualRepository {
    val saveCalls = mutableListOf<LeaveAccrualId>()
    private val store = mutableMapOf<LeaveAccrualId, LeaveAccrual>()
    override fun save(leaveAccrual: LeaveAccrual) {
        saveCalls.add(leaveAccrual.id)
        store[leaveAccrual.id] = leaveAccrual
    }
    override fun findById(id: LeaveAccrualId): LeaveAccrual? = store[id]
    override fun findAllByCompany(companyId: CompanyId): List<LeaveAccrual> = store.values.filter { it.companyId == companyId }
}
