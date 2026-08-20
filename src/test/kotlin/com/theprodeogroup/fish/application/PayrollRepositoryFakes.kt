package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.payroll.PayRun
import com.theprodeogroup.fish.domain.payroll.PayRunId
import com.theprodeogroup.fish.domain.payroll.PayRunRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId

/**
 * In-memory stand-in for Payroll's GL-facing posting interface
 * (docs/DDD_Design.md Section 10.4) - same discipline as
 * `EcosystemRepositoryFakes.kt`/`LedgerRepositoryFakes.kt`. `LeaveAccrual`
 * has no repository/fake here, matching `PayRunRepository`'s own KDoc -
 * it delegates to `Provision`, which isn't persisted yet either.
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
