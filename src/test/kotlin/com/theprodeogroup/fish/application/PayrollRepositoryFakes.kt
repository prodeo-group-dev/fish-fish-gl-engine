package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.payroll.LeaveAccrual
import com.theprodeogroup.fish.domain.payroll.LeaveAccrualId
import com.theprodeogroup.fish.domain.payroll.LeaveAccrualRepository
import com.theprodeogroup.fish.domain.payroll.PayRun
import com.theprodeogroup.fish.domain.payroll.PayRunId
import com.theprodeogroup.fish.domain.payroll.PayRunRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * In-memory stand-ins for Payroll's repository interfaces
 * (docs/DDD_Design.md Section 10.4/10.18) - same discipline as
 * `EcosystemRepositoryFakes.kt`/`LedgerRepositoryFakes.kt`.
 */
class FakePayRunRepository : PayRunRepository {
    val saveCalls = mutableListOf<PayRunId>()
    private val store = mutableMapOf<PayRunId, PayRun>()
    override fun save(payRun: PayRun) {
        saveCalls.add(payRun.id)
        store[payRun.id] = payRun
    }
    override fun findById(id: PayRunId): PayRun? = store[id]
    override fun findAllByCompany(companyId: CompanyId): List<PayRun> = store.values.filter { it.companyId == companyId }
}

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
